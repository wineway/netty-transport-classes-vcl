package dev.vcl.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Wire path: Linux TCP <-> veth <-> VPP AF_PACKET <-> VCL <-> Netty. */
@EnabledIfEnvironmentVariable(named = "VCL_WIRE_PEER", matches = ".+")
@Timeout(20)
class KernelInteropIT {
    @Test void partialSharedBindFailureRollsBackOnlyNewListeners() throws Exception {
        try (VclRuntime runtime = VclRuntime.open("vcl-wire-bind-rollback")) {
            var group = new MultiThreadIoEventLoopGroup(2, VclIoHandler.newFactory(runtime));
            var loops = new java.util.ArrayList<EventLoop>();
            for (var loop : group) loops.add((EventLoop) loop);
            ChannelHandler echo = new ChannelInitializer<VclSocketChannel>() {
                @Override protected void initChannel(VclSocketChannel channel) {
                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override public void channelRead(ChannelHandlerContext ctx, Object message) { ctx.writeAndFlush(message); }
                        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
                    });
                }
            };
            try {
                // Occupy the endpoint on the second worker; first-worker bind will initially succeed.
                Channel existing = new ServerBootstrap().group(loops.get(1), loops.get(1)).channel(VclServerSocketChannel.class)
                        .childHandler(echo).bind(new InetSocketAddress("0.0.0.0", 19203)).sync().channel();
                var attempt = new VclServerBootstrap(group).childHandler(echo).bind(new InetSocketAddress("0.0.0.0", 19203));
                assertTrue(attempt.await(3, TimeUnit.SECONDS));
                assertFalse(attempt.isSuccess());
                for (EventLoop loop : loops) loop.submit(() -> {}).sync();
                assertEquals(1, runtime.metrics().activeRegistrations(), "new listeners should have been rolled back");
                try (Socket client = new Socket()) {
                    client.connect(new InetSocketAddress(System.getenv("VCL_WIRE_LOCAL"), 19203), 3000);
                    client.setSoTimeout(5000);
                    client.getOutputStream().write(new byte[]{1, 2, 3});
                    assertArrayEquals(new byte[]{1, 2, 3}, client.getInputStream().readNBytes(3));
                }
                existing.close().sync();
            } finally { group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
        }
    }

    @Test void pendingConnectCanTimeoutAndBeCancelled() throws Exception {
        try (VclRuntime runtime = VclRuntime.open("vcl-wire-timeout")) {
            var group = new MultiThreadIoEventLoopGroup(1, VclIoHandler.newFactory(runtime));
            ChannelHandler handler = new ChannelInitializer<VclSocketChannel>() {
                @Override protected void initChannel(VclSocketChannel channel) {
                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
                    });
                }
            };
            try {
                Bootstrap bootstrap = new Bootstrap().group(group).channel(VclSocketChannel.class).handler(handler);
                ChannelFuture timeout = bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100).connect("10.222.0.99", 19202);
                assertTrue(timeout.await(2, TimeUnit.SECONDS));
                assertInstanceOf(ConnectTimeoutException.class, timeout.cause());
                assertTrue(timeout.channel().closeFuture().await(2, TimeUnit.SECONDS));
                ChannelFuture cancelled = bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000).connect("10.222.0.98", 19202);
                cancelled.channel().eventLoop().submit(() -> {}).sync();
                assertTrue(cancelled.cancel(false));
                assertTrue(cancelled.channel().closeFuture().await(2, TimeUnit.SECONDS));
                group.next().submit(() -> {}).get(1, TimeUnit.SECONDS);
            } finally { group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
        }
    }

    @Test void kernelClientFinAllowsNettyServerReply() throws Exception {
        try (VclRuntime runtime = VclRuntime.open("vcl-wire-server")) {
            var group = new MultiThreadIoEventLoopGroup(2, VclIoHandler.newFactory(runtime));
            CompletableFuture<Void> fin = new CompletableFuture<>();
            try {
                VclServerBinding binding = new VclServerBootstrap(group).childOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
                        .childHandler(new ChannelInitializer<VclSocketChannel>() {
                            @Override protected void initChannel(VclSocketChannel channel) {
                                channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                    @Override public void channelRead(ChannelHandlerContext ctx, Object message) { ((ByteBuf) message).release(); }
                                    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                                        if (event == ChannelInputShutdownEvent.INSTANCE) {
                                            fin.complete(null);
                                            ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4})).addListener(future -> {
                                                if (!future.isSuccess()) fin.obtrudeException(future.cause());
                                                ((VclSocketChannel) ctx.channel()).shutdownOutput();
                                            });
                                        }
                                    }
                                    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { fin.completeExceptionally(cause); ctx.close(); }
                                });
                            }
                        }).bind(new InetSocketAddress("0.0.0.0", 19200)).sync().getNow();
                try {
                    // Exercise VPP listener fanout with multiple ordinary kernel TCP connections.
                    for (int i = 0; i < 8; i++) {
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress(System.getenv("VCL_WIRE_LOCAL"), 19200), 3000);
                            socket.setSoTimeout(5000);
                            socket.getOutputStream().write(new byte[]{9, 8, 7});
                            socket.shutdownOutput();
                            assertArrayEquals(new byte[]{1, 2, 3, 4}, socket.getInputStream().readAllBytes());
                        }
                    }
                    fin.get(5, TimeUnit.SECONDS);
                } finally { binding.closeAsync().sync(); }
            } finally { group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
        }
    }

    @Test void nettyClientFinAllowsKernelServerReply() throws Exception {
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(System.getenv("VCL_WIRE_PEER"), 19201));
            listener.setSoTimeout(5000);
            CompletableFuture<Void> peer = new CompletableFuture<>();
            Thread server = Thread.ofPlatform().start(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    assertArrayEquals(new byte[]{4, 5, 6}, socket.getInputStream().readAllBytes());
                    socket.getOutputStream().write(new byte[]{1, 2, 3, 4});
                    socket.shutdownOutput();
                    peer.complete(null);
                } catch (Throwable e) { peer.completeExceptionally(e); }
            });
            try (VclRuntime runtime = VclRuntime.open("vcl-wire-client")) {
                var group = new MultiThreadIoEventLoopGroup(1, VclIoHandler.newFactory(runtime));
                CompletableFuture<byte[]> received = new CompletableFuture<>();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try {
                    VclSocketChannel channel = (VclSocketChannel) new Bootstrap().group(group).channel(VclSocketChannel.class)
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                            .handler(new ChannelInitializer<VclSocketChannel>() {
                                @Override protected void initChannel(VclSocketChannel channel) {
                                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                                            ByteBuf buffer = (ByteBuf) message;
                                            try { while (buffer.isReadable()) bytes.write(buffer.readByte()); }
                                            finally { buffer.release(); }
                                        }
                                        @Override public void channelInactive(ChannelHandlerContext ctx) { received.complete(bytes.toByteArray()); }
                                        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { received.completeExceptionally(cause); ctx.close(); }
                                    });
                                }
                            }).connect(System.getenv("VCL_WIRE_PEER"), 19201).sync().channel();
                    channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{4, 5, 6})).sync();
                    channel.shutdownOutput().sync();
                    assertArrayEquals(new byte[]{1, 2, 3, 4}, received.get(5, TimeUnit.SECONDS));
                    peer.get(5, TimeUnit.SECONDS);
                    channel.close().sync();
                } finally { group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
            } finally { server.join(6000); }
        }
    }
}

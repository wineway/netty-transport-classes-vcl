package dev.vcl.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Regression cases for the supported TCP contracts shared with Netty's epoll transport. */
@EnabledIfEnvironmentVariable(named = "VCL_WIRE_PEER", matches = ".+")
@Timeout(20)
class TransportSemanticsIT {
    private VclRuntime runtime;
    private MultiThreadIoEventLoopGroup group;
    private VclSocketChannel channel;

    @BeforeEach void start() throws Exception {
        runtime = VclRuntime.open("vcl-epoll-semantics");
        group = new MultiThreadIoEventLoopGroup(1, VclIoHandler.newFactory(runtime));
    }

    @AfterEach void stop() throws Exception {
        try {
            if (channel != null) channel.close().sync();
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            assertEquals(0, runtime.activeWorkers());
        } finally { runtime.close(); }
    }

    private ServerSocket listen() throws Exception {
        ServerSocket listener = new ServerSocket();
        listener.bind(new InetSocketAddress(System.getenv("VCL_WIRE_PEER"), 0));
        listener.setSoTimeout(5000);
        return listener;
    }

    private VclSocketChannel register(ChannelHandler handler) throws Exception {
        channel = (VclSocketChannel) new Bootstrap().group(group).channel(VclSocketChannel.class)
                .handler(handler).register().sync().channel();
        return channel;
    }

    @Test void invalidAndRepeatedConnectLeaveTheChannelUsable() throws Exception {
        CompletableFuture<Integer> response = new CompletableFuture<>();
        try (ServerSocket listener = listen()) {
            register(new ChannelInboundHandlerAdapter() {
                @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                    ByteBuf bytes = (ByteBuf) message;
                    try { response.complete((int) bytes.readUnsignedByte()); }
                    finally { bytes.release(); }
                }
            });
            ChannelFuture invalid = channel.connect(InetSocketAddress.createUnresolved("unresolved.invalid", 1));
            assertTrue(invalid.await(1, TimeUnit.SECONDS));
            assertInstanceOf(UnresolvedAddressException.class, invalid.cause());
            assertTrue(channel.isOpen());
            InetSocketAddress peerAddress = (InetSocketAddress) listener.getLocalSocketAddress();
            InetSocketAddress namedPeer = new InetSocketAddress(InetAddress.getByAddress("wire-peer.test",
                    peerAddress.getAddress().getAddress()), peerAddress.getPort());
            record ListenerRetry(Class<? extends Throwable> expected, Throwable cause) { }
            CompletableFuture<ListenerRetry> listenerConnectFailure = new CompletableFuture<>();
            ChannelPromise connected = channel.newPromise();
            channel.eventLoop().execute(() -> {
                boolean[] connectCallReturned = {false};
                connected.addListener(future -> {
                    if (future.isSuccess()) {
                        Class<? extends Throwable> expected = connectCallReturned[0]
                                ? ConnectionPendingException.class : AlreadyConnectedException.class;
                        channel.connect(namedPeer).addListener(repeated -> listenerConnectFailure.complete(
                                new ListenerRetry(expected, repeated.cause())));
                    }
                });
                channel.connect(namedPeer, connected);
                connectCallReturned[0] = true;
            });
            connected.sync();
            ListenerRetry retry = listenerConnectFailure.get(2, TimeUnit.SECONDS);
            assertInstanceOf(retry.expected(), retry.cause());
            assertEquals("wire-peer.test", channel.remoteAddress().getHostString());
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(5000);
                ChannelFuture duplicate = channel.connect(listener.getLocalSocketAddress());
                assertTrue(duplicate.await(1, TimeUnit.SECONDS));
                assertInstanceOf(AlreadyConnectedException.class, duplicate.cause());
                assertTrue(channel.isActive());
                channel.config().setWriteSpinCount(1);
                channel.eventLoop().submit(() -> {
                    List<ChannelFuture> emptyWrites = new ArrayList<>();
                    for (int i = 0; i < 32; i++) emptyWrites.add(channel.write(Unpooled.EMPTY_BUFFER));
                    ChannelFuture data = channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{7}));
                    assertTrue(data.isSuccess(), "empty buffers must not consume the native write quantum");
                    for (ChannelFuture empty : emptyWrites) assertTrue(empty.isSuccess());
                }).sync();
                assertEquals(7, peer.getInputStream().read());
                peer.getOutputStream().write(9);
                assertEquals(9, response.get(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test void aSecondPendingConnectDoesNotCancelTheFirstOrFireAnException() throws Exception {
        AtomicInteger exceptions = new AtomicInteger();
        register(new ChannelInboundHandlerAdapter() {
            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { exceptions.incrementAndGet(); }
        });
        channel.config().setConnectTimeoutMillis(10_000);
        ChannelFuture first = channel.connect(new InetSocketAddress("10.222.0.99", 19230));
        channel.eventLoop().submit(() -> { }).sync();
        assertFalse(first.isDone());
        assertNotNull(channel.localAddress());
        ChannelFuture second = channel.connect(new InetSocketAddress("10.222.0.98", 19230));
        assertTrue(second.await(1, TimeUnit.SECONDS));
        assertInstanceOf(ConnectionPendingException.class, second.cause());
        assertTrue(channel.isOpen());
        assertFalse(first.isDone());
        assertTrue(first.cancel(false));
        assertTrue(channel.closeFuture().await(2, TimeUnit.SECONDS));
        assertEquals(0, exceptions.get());
    }

    @Test void shutdownStillAttemptsInputWhenOutputShutdownFails() throws Exception {
        try (ServerSocket listener = listen()) {
            register(new ChannelInboundHandlerAdapter());
            channel.connect(listener.getLocalSocketAddress()).sync();
            try (Socket peer = listener.accept()) {
                channel.shutdownOutput().sync();
                assertFalse(channel.isInputShutdown());
                ChannelFuture repeated = channel.shutdownOutput();
                assertTrue(repeated.await(1, TimeUnit.SECONDS));
                assertInstanceOf(ClosedChannelException.class, repeated.cause());
                ChannelFuture both = channel.shutdown();
                assertTrue(both.await(1, TimeUnit.SECONDS));
                assertInstanceOf(ClosedChannelException.class, both.cause());
                assertTrue(channel.isInputShutdown(), "input shutdown must run even if output shutdown failed");
            }
        }
    }

    @Test void remoteFinDrainsBufferedDataBeforeInputShutdownEventsWithAutoReadDisabled() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger received = new AtomicInteger();
        try (ServerSocket listener = listen()) {
            register(new ChannelInboundHandlerAdapter() {
                @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                    ByteBuf data = (ByteBuf) message;
                    try {
                        while (data.isReadable()) assertEquals((byte) received.getAndIncrement(), data.readByte());
                        events.add("data");
                    } finally { data.release(); }
                }
                @Override public void channelReadComplete(ChannelHandlerContext ctx) { events.add("readComplete"); }
                @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                    if (event == ChannelInputShutdownEvent.INSTANCE) events.add("inputShutdown");
                    if (event == ChannelInputShutdownReadComplete.INSTANCE) { events.add("inputComplete"); done.complete(null); }
                }
                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { done.completeExceptionally(cause); }
            });
            channel.config().setAllowHalfClosure(true).setAutoRead(false)
                    .setRecvByteBufAllocator(new FixedRecvByteBufAllocator(32));
            channel.connect(listener.getLocalSocketAddress()).sync();
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(5000);
                byte[] payload = new byte[8192];
                for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
                peer.getOutputStream().write(payload);
                peer.shutdownOutput();
                done.get(5, TimeUnit.SECONDS);
                assertEquals(payload.length, received.get());
                assertTrue(events.lastIndexOf("data") < events.indexOf("inputShutdown"), events.toString());
                assertTrue(events.indexOf("inputShutdown") < events.indexOf("inputComplete"), events.toString());
                assertEquals(1, Collections.frequency(events, "inputShutdown"));
                assertEquals(1, Collections.frequency(events, "inputComplete"));
                assertTrue(channel.isActive());
                channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{3})).sync();
                assertEquals(3, peer.getInputStream().read());
            }
        }
    }

    @Test void deregisterAndReregisterOnTheSameLoopRestoresReadiness() throws Exception {
        CompletableFuture<Integer> response = new CompletableFuture<>();
        try (ServerSocket listener = listen()) {
            register(new ChannelInboundHandlerAdapter() {
                @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                    ByteBuf data = (ByteBuf) message;
                    try { response.complete((int) data.readUnsignedByte()); }
                    finally { data.release(); }
                }
            });
            channel.connect(listener.getLocalSocketAddress()).sync();
            try (Socket peer = listener.accept()) {
                EventLoop loop = channel.eventLoop();
                int mask = NativeVcl.IN | NativeVcl.RDHUP;
                assertEquals((long) mask, loop.submit(() -> channel.registration.submit(new VclIoHandler.Ops(mask)))
                        .get(2, TimeUnit.SECONDS));
                channel.deregister().sync();
                assertFalse(channel.isRegistered());
                loop.register(channel).sync();
                peer.getOutputStream().write(42);
                assertEquals(42, response.get(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test void refusedConnectionReportsConnectExceptionWithoutExtraPipelineException() throws Exception {
        int port;
        try (ServerSocket unused = listen()) { port = unused.getLocalPort(); }
        AtomicInteger exceptions = new AtomicInteger();
        register(new ChannelInboundHandlerAdapter() {
            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { exceptions.incrementAndGet(); }
        });
        ChannelFuture connect = channel.connect(new InetSocketAddress(System.getenv("VCL_WIRE_PEER"), port));
        assertTrue(connect.await(3, TimeUnit.SECONDS));
        assertInstanceOf(ConnectException.class, connect.cause());
        assertTrue(connect.cause().getMessage().contains(Integer.toString(port)));
        channel.eventLoop().submit(() -> { }).sync();
        assertEquals(0, exceptions.get());
    }
}

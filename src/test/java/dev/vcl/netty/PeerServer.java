package dev.vcl.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Separate application: VPP's local transport intentionally avoids self-connect. */
public final class PeerServer {
    private PeerServer() { }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        List<Channel> children = new CopyOnWriteArrayList<>();
        Map<EventLoop, AtomicInteger> counts = new ConcurrentHashMap<>();
        try (VclRuntime runtime = VclRuntime.open("netty-vcl-integration-server")) {
            MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, VclIoHandler.newFactory(runtime));
            try {
                VclServerBinding binding = new VclServerBootstrap(group)
                        .childOption(ChannelOption.AUTO_READ, !mode.equals("paused"))
                        .childOption(ChannelOption.ALLOW_HALF_CLOSURE, mode.equals("half"))
                        .childHandler(new ChannelInitializer<VclSocketChannel>() {
                            @Override protected void initChannel(VclSocketChannel channel) {
                                children.add(channel);
                                counts.computeIfAbsent(channel.eventLoop(), ignored -> new AtomicInteger()).incrementAndGet();
                                channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                        if (mode.equals("half") || mode.equals("local-shutdown")) ((ByteBuf) msg).release(); else ctx.write(msg);
                                    }
                                    @Override public void channelReadComplete(ChannelHandlerContext ctx) { ctx.flush(); }
                                    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                                        if (event == ChannelInputShutdownEvent.INSTANCE) ctx.writeAndFlush(TransportIT.payload(64)).addListener(f -> ((VclSocketChannel) ctx.channel()).shutdownOutput());
                                    }
                                    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { cause.printStackTrace(); ctx.close(); }
                                });
                            }
                        }).bind(new InetSocketAddress("0.0.0.0", Integer.parseInt(args[1]))).sync().getNow();
                System.out.println("READY");
                try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                    for (String command; (command = input.readLine()) != null;) {
                        if (command.equals("STOP")) break;
                        if (command.equals("READ")) for (Channel child : children) child.config().setAutoRead(true);
                        if (command.equals("REPLY")) for (Channel child : children) child.writeAndFlush(TransportIT.payload(64));
                    }
                }
                binding.closeAsync().sync();
                for (Channel child : children) child.close().sync();
                for (var entry : counts.entrySet()) System.out.println("WORKER " + entry.getKey() + " " + entry.getValue().get());
            } finally { group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
        }
    }
}

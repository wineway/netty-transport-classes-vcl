package dev.vcl.netty.example;

import dev.vcl.netty.*;
import io.netty.channel.*;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/** Interactive echo server; press Enter to stop. */
public final class EchoServer {
    private EchoServer() { }
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 9090 : Integer.parseInt(args[0]);
        int workers = args.length < 2 ? 2 : Integer.parseInt(args[1]);
        try (VclRuntime runtime = VclRuntime.open("netty-vcl-echo")) {
            var group = new MultiThreadIoEventLoopGroup(workers, VclIoHandler.newFactory(runtime));
            try {
                VclServerBinding binding = new VclServerBootstrap(group)
                        .childHandler(new ChannelInitializer<VclSocketChannel>() {
                            @Override protected void initChannel(VclSocketChannel channel) {
                                channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                    @Override public void channelRead(ChannelHandlerContext ctx, Object message) { ctx.write(message); }
                                    @Override public void channelReadComplete(ChannelHandlerContext ctx) { ctx.flush(); }
                                    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { cause.printStackTrace(); ctx.close(); }
                                });
                            }
                        }).bind(new InetSocketAddress("0.0.0.0", port)).sync().getNow();
                System.out.println("Listening on VPP TCP port " + port + ", workers=" + workers + ". Press Enter to stop.");
                System.in.read();
                binding.closeAsync().sync();
                System.out.println(runtime.metrics());
            } finally { group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
        }
    }
}

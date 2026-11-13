/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl.example;

import me.wineway.vcl.*;
import io.netty.channel.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Arguments: libvppcom.so.26.10 sapi.socket interrupt|poll address port workers. */
public final class EchoServer {
    private EchoServer() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("library socket interrupt|poll address port workers");
        VclMode mode = VclMode.valueOf(args[2].toUpperCase(java.util.Locale.ROOT));
        int workers = Integer.parseInt(args[5]);
        var stop = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        Thread hook = Thread.ofPlatform().unstarted(() -> {
            stop.countDown();
            try { stopped.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Runtime.getRuntime().addShutdownHook(hook);
        try (var runtime = VclRuntime.open(Path.of(args[0]), Path.of(args[1]), mode, workers)) {
            var group = new MultiThreadIoEventLoopGroup(workers,
                    Thread.ofPlatform().name("vcl-eventloop-", 0).factory(), runtime.ioHandlerFactory());
            VclServer server = null;
            try {
                server = VclServer.bind(group, new InetSocketAddress(args[3], Integer.parseInt(args[4])),
                        () -> new ChannelInboundHandlerAdapter() {
                            @Override public void channelRead(ChannelHandlerContext ctx, Object message) { ctx.write(message); }
                            @Override public void channelReadComplete(ChannelHandlerContext ctx) { ctx.flush(); }
                            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable failure) {
                                failure.printStackTrace(System.err); ctx.close();
                            }
                        }).sync().getNow();
                System.out.println("VCL " + mode + " listening on " + server.localAddress() + " with " + workers + " workers");
                stop.await();
            } finally {
                try { if (server != null) server.close().sync(); }
                finally { group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync(); }
            }
        } finally {
            stopped.countDown();
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { }
        }
    }
}

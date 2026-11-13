package dev.vcl.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs last alphabetically. Only kills the dedicated VPP created by verify-vpp.sh. */
@EnabledIfEnvironmentVariable(named = "VCL_TEST_VPP_PID", matches = "[0-9]+")
class VppDetachIT {
    @Test @Timeout(20) void vppExitFailsPendingWritesAndClosesChannels() throws Exception {
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(System.getenv("VCL_WIRE_PEER"), 19210));
            listener.setSoTimeout(5000);
            CompletableFuture<Socket> accepted = new CompletableFuture<>();
            Thread server = Thread.ofPlatform().start(() -> {
                try { accepted.complete(listener.accept()); }
                catch (Throwable e) { accepted.completeExceptionally(e); }
            });
            try (VclRuntime runtime = VclRuntime.open("vcl-detach-test")) {
                var group = new MultiThreadIoEventLoopGroup(1, VclIoHandler.newFactory(runtime));
                CompletableFuture<Throwable> failure = new CompletableFuture<>();
                Socket peer = null;
                try {
                    Channel channel = new Bootstrap().group(group).channel(VclSocketChannel.class)
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                            .handler(new ChannelInboundHandlerAdapter() {
                                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { failure.complete(cause); ctx.close(); }
                            }).connect(System.getenv("VCL_WIRE_PEER"), 19210).sync().channel();
                    peer = accepted.get(5, TimeUnit.SECONDS);
                    ChannelFuture write = channel.writeAndFlush(TransportIT.payload(16 * 1024 * 1024));
                    assertFalse(write.await(150, TimeUnit.MILLISECONDS));
                    ProcessHandle vpp = ProcessHandle.of(Long.parseLong(System.getenv("VCL_TEST_VPP_PID"))).orElseThrow();
                    assertTrue(vpp.destroy(), "could not stop the test VPP");
                    assertNotNull(failure.get(5, TimeUnit.SECONDS));
                    assertTrue(channel.closeFuture().await(5, TimeUnit.SECONDS));
                    // AbstractUnsafe completes closeFuture before failing the outbound buffer.
                    assertTrue(write.await(5, TimeUnit.SECONDS));
                    assertFalse(write.isSuccess());
                    group.next().submit(() -> { }).sync();
                    assertTrue(runtime.metrics().ioFailures() > 0);
                } finally {
                    if (peer != null) peer.close();
                    group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
                    server.join(6000);
                }
            }
        }
    }
}

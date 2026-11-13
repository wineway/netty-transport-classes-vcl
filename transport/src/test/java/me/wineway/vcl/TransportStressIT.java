/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Sustained load with independent progress checks on every fixed EventLoop. */
@Timeout(120)
class TransportStressIT {
    @Test void sustainedTrafficDoesNotStarveAnyLoop() throws Exception {
        var peer = new NetworkTransportIT();
        int seconds = Integer.getInteger("vcl.stress.seconds", 60);
        if (seconds < 1 || seconds > 90) throw new IllegalArgumentException("stress seconds must be 1..90");
        var streams = new ArrayList<Load>(); var timers = new ArrayList<ScheduledFuture<?>>();
        var ticks = new ArrayList<AtomicLong>(); var lag = new ArrayList<AtomicLong>();
        try {
            peer.startPeer();
            try (var transport = new RealTransportSupport()) {
                long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
                for (var loop : transport.group) {
                    var count = new AtomicLong(); var max = new AtomicLong(); var due = new AtomicLong(System.nanoTime() + 10_000_000L);
                    ticks.add(count); lag.add(max);
                    timers.add(loop.scheduleAtFixedRate(() -> {
                        max.accumulateAndGet(System.nanoTime() - due.getAndAdd(10_000_000L), Math::max); count.incrementAndGet();
                    }, 10, 10, TimeUnit.MILLISECONDS));
                }
                var address = new InetSocketAddress(transport.backend.equals("epoll") ? "127.0.0.1" : "10.88.0.2", 32100);
                for (int i = 0; i < 8; i++) {
                    var stream = new Load(stop); streams.add(stream); transport.connect(address, stream);
                }
                CompletableFuture.allOf(streams.stream().map(s -> s.done).toArray(CompletableFuture[]::new))
                        .get(seconds + 15L, TimeUnit.SECONDS);
                for (var timer : timers) timer.cancel(false);
                for (int i = 0; i < ticks.size(); i++) {
                    assertTrue(ticks.get(i).get() >= seconds * 80L, "timer progress on loop " + i);
                    assertTrue(lag.get(i).get() < TimeUnit.SECONDS.toNanos(2), "timer starvation on loop " + i);
                }
                long messages = 0;
                for (Load stream : streams) {
                    assertEquals(stream.sent * 1024, stream.received); assertTrue(stream.sent > 1000);
                    messages += stream.sent; stream.context.close().sync();
                }
                Path output = Path.of(System.getProperty("vcl.it.output", "target/stress")); Files.createDirectories(output);
                Files.writeString(output.resolve("stress.txt"), "seconds=" + seconds + "\nconnections=8\nbytes_per_message=1024\nwindow_per_connection=16\nmessages=" + messages
                        + "\ntimer_ticks=" + ticks + "\ntimer_max_lag_ns=" + lag + "\n");
            }
        } finally {
            for (var timer : timers) timer.cancel(false);
            peer.stopPeer();
        }
    }
    private static final class Load extends ChannelInboundHandlerAdapter {
        final long stop;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        ChannelHandlerContext context;
        long sent, received;
        Load(long stop) { this.stop = stop; }
        @Override public void channelActive(ChannelHandlerContext ctx) { context = ctx; pump(); }
        void pump() {
            if (done.isDone()) return;
            while (System.nanoTime() < stop && sent - received / 1024 < 16 && context.channel().isWritable()) {
                sent++;
                context.write(context.alloc().ioBuffer(1024).writeZero(1024)).addListener(f -> {
                    if (!f.isSuccess()) done.completeExceptionally(f.cause());
                });
            }
            context.flush();
            if (System.nanoTime() >= stop && received == sent * 1024) done.complete(null);
        }
        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf bytes = (ByteBuf) msg;
            try {
                received += bytes.readableBytes();
                while (bytes.readableBytes() >= 8) assertEquals(0, bytes.readLong());
                while (bytes.isReadable()) assertEquals(0, bytes.readByte());
                pump();
            } catch (Throwable failure) { done.completeExceptionally(failure); }
            finally { bytes.release(); }
        }
        @Override public void channelWritabilityChanged(ChannelHandlerContext ctx) { if (context != null) pump(); }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable failure) { done.completeExceptionally(failure); ctx.close(); }
        @Override public void channelInactive(ChannelHandlerContext ctx) {
            if (!done.isDone()) done.completeExceptionally(new java.io.IOException("Closed during load"));
        }
    }
}

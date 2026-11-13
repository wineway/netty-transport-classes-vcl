/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit opt-in benchmark. Raw samples include scheduled-arrival latency and RTT. */
@Timeout(180)
class TransportBenchmarkIT {
    record Scenario(String name, int bytes, int connections, int count, int window, long intervalNanos, int burst) { }
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("request-response", 64, 1, 8000, 1, 0, 1),
            new Scenario("fixed-arrival", 64, 1, 5000, 256, 100_000, 1),
            new Scenario("burst", 64, 1, 8192, 256, 1_000_000, 32),
            new Scenario("multi-connection", 64, 8, 2000, 16, 0, 1),
            new Scenario("bulk", 65536, 1, 2048, 16, 0, 1));

    @Test void measureRealTransports() throws Exception {
        Path output = Path.of(System.getProperty("vcl.it.output", "target/benchmark"));
        Files.createDirectories(output);
        var peer = new NetworkTransportIT();
        try {
            peer.startPeer();
            try (var transport = new RealTransportSupport();
                 var rows = Files.newBufferedWriter(output.resolve("benchmark.csv"));
                 var raw = Files.newBufferedWriter(output.resolve("latency.csv"))) {
                rows.write("scenario,repetition,bytes,connections,attempted,received,dropped,errors,backpressure,window_stalls,seconds,messages_per_second,payload_bytes_per_second,p50_ns,p99_ns,p999_ns,rtt_p99_ns,client_cpu_ns,peer_cpu_ns,vpp_cpu_ns,client_allocated_bytes,peer_allocated_bytes,gc_count,gc_ms,timer_max_lag_ns\n");
                raw.write("scenario,repetition,connection,sequence,scheduled_latency_ns,rtt_ns\n");
                ProcessMetrics idlePeer = peerMetrics(transport), idleClient = ProcessMetrics.current();
                long idleVpp = ProcessMetrics.vppCpuNanos(), idleStart = System.nanoTime();
                Thread.sleep(250);
                long idleElapsed = System.nanoTime() - idleStart;
                ProcessMetrics idleAfter = ProcessMetrics.current().minus(idleClient);
                long idleVppDelta = ProcessMetrics.vppCpuNanos() - idleVpp;
                ProcessMetrics idlePeerDelta = peerMetrics(transport).minus(idlePeer);
                Files.writeString(output.resolve("idle.csv"), "elapsed_ns,client_cpu_ns,peer_cpu_ns,vpp_cpu_ns\n" +
                        idleElapsed + "," + idleAfter.cpuNanos() + "," + idlePeerDelta.cpuNanos() + "," + idleVppDelta + "\n");
                int repetitions = Integer.getInteger("vcl.bench.repetitions", 3);
                for (Scenario scenario : SCENARIOS) {
                    run(transport, new Scenario(scenario.name, scenario.bytes, scenario.connections,
                            Math.min(1000, scenario.count), scenario.window, scenario.intervalNanos, scenario.burst), -1, null, null);
                    for (int repetition = 0; repetition < repetitions; repetition++) {
                        run(transport, scenario, repetition, rows, raw); rows.flush(); raw.flush();
                    }
                }
            }
        } finally { peer.stopPeer(); }
    }

    private static InetSocketAddress peerAddress(RealTransportSupport transport, int port) {
        return new InetSocketAddress(transport.backend.equals("epoll") ? "127.0.0.1" : "10.88.0.2", port);
    }
    private static ProcessMetrics peerMetrics(RealTransportSupport transport) throws Exception {
        var capture = new ChannelStateTest.Capture(); Channel channel = transport.connect(peerAddress(transport, 32103), capture);
        ByteBuf result = io.netty.buffer.Unpooled.buffer(32);
        try {
            channel.writeAndFlush(io.netty.buffer.Unpooled.buffer(1).writeByte(1)).sync();
            while (result.readableBytes() < 32) {
                ByteBuf part = (ByteBuf) capture.reads.poll(5, TimeUnit.SECONDS);
                if (part == null) throw new TimeoutException("Peer metrics: " + capture.failures);
                try { result.writeBytes(part); } finally { part.release(); }
            }
            return new ProcessMetrics(result.readLong(), result.readLong(), result.readLong(), result.readLong());
        } finally { result.release(); channel.close().sync(); }
    }
    private static void run(RealTransportSupport transport, Scenario scenario, int repetition,
                            BufferedWriter rows, BufferedWriter raw) throws Exception {
        var streams = new ArrayList<Stream>();
        var timerLag = new AtomicLong(); var tick = new AtomicLong(System.nanoTime() + 10_000_000L);
        var timer = transport.group.next().scheduleAtFixedRate(() -> {
            long lag = System.nanoTime() - tick.getAndAdd(10_000_000L);
            timerLag.accumulateAndGet(lag, Math::max);
        }, 10, 10, TimeUnit.MILLISECONDS);
        try {
            for (int i = 0; i < scenario.connections; i++) {
                var stream = new Stream(scenario); streams.add(stream);
                transport.connect(peerAddress(transport, 32100), stream);
            }
            ProcessMetrics peerBefore = peerMetrics(transport), before = ProcessMetrics.current();
            long vppBefore = ProcessMetrics.vppCpuNanos(), start = System.nanoTime();
            for (Stream stream : streams) stream.context.executor().execute(() -> stream.start(start));
            CompletableFuture.allOf(streams.stream().map(s -> s.done).toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            long elapsed = System.nanoTime() - start, vpp = ProcessMetrics.vppCpuNanos() - vppBefore;
            ProcessMetrics client = ProcessMetrics.current().minus(before), peer = peerMetrics(transport).minus(peerBefore);
            if (rows != null) {
                long[] latency = streams.stream().flatMapToLong(s -> Arrays.stream(s.latency).filter(n -> n > 0)).toArray();
                long[] rtt = streams.stream().flatMapToLong(s -> Arrays.stream(s.rtt).filter(n -> n > 0)).toArray();
                Arrays.sort(latency); Arrays.sort(rtt);
                long dropped = streams.stream().mapToLong(s -> s.dropped).sum();
                long pressure = streams.stream().mapToLong(s -> s.backpressure).sum();
                long windowStalls = streams.stream().mapToLong(s -> s.windowStalls).sum();
                double seconds = elapsed / 1e9;
                rows.write(String.format(Locale.ROOT,
                        "%s,%d,%d,%d,%d,%d,%d,0,%d,%d,%.9f,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                        scenario.name, repetition, scenario.bytes, scenario.connections, scenario.count * scenario.connections,
                        latency.length, dropped, pressure, windowStalls, seconds, latency.length / seconds, latency.length * (double) scenario.bytes / seconds,
                        percentile(latency, .5), percentile(latency, .99), percentile(latency, .999), percentile(rtt, .99),
                        client.cpuNanos(), peer.cpuNanos(), vpp, client.allocatedBytes(), peer.allocatedBytes(),
                        client.gcCount() + peer.gcCount(), client.gcMillis() + peer.gcMillis(), timerLag.get()));
                for (int c = 0; c < streams.size(); c++) {
                    Stream stream = streams.get(c);
                    for (int i = 0; i < scenario.count; i++) raw.write(scenario.name + "," + repetition + "," + c + "," + i + "," + stream.latency[i] + "," + stream.rtt[i] + "\n");
                }
                System.out.println("Measured " + scenario.name + " repetition " + repetition + ": " + latency.length + " replies, " + dropped + " dropped arrivals");
            }
        } finally {
            timer.cancel(false);
            for (Stream stream : streams) if (stream.context != null) stream.context.close().sync();
        }
    }
    private static long percentile(long[] samples, double quantile) {
        return samples.length == 0 ? 0 : samples[Math.min(samples.length - 1, (int) Math.ceil(samples.length * quantile) - 1)];
    }
    private static final class Stream extends ChannelInboundHandlerAdapter {
        final Scenario scenario;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final long[] sent, planned, latency, rtt;
        ChannelHandlerContext context;
        ByteBuf receive;
        int next, outstanding, received, dropped, backpressure, windowStalls;
        ScheduledFuture<?> scheduled;
        long start;
        boolean pumping;
        Stream(Scenario scenario) {
            this.scenario = scenario;
            sent = new long[scenario.count]; planned = new long[scenario.count];
            latency = new long[scenario.count]; rtt = new long[scenario.count];
        }
        @Override public void handlerAdded(ChannelHandlerContext ctx) { context = ctx; receive = ctx.alloc().buffer(); }
        @Override public void handlerRemoved(ChannelHandlerContext ctx) {
            if (scheduled != null) scheduled.cancel(false);
            receive.release();
        }
        void start(long start) { this.start = start; pump(); }
        void pump() {
            if (pumping || done.isDone() || start == 0) return;
            pumping = true;
            try {
                int batch = 0;
                while (next < scenario.count && batch++ < 64) {
                    long now = System.nanoTime();
                    long due = scenario.intervalNanos == 0 ? now : start + (next / scenario.burst) * scenario.intervalNanos;
                    if (due > now) {
                        if (scheduled == null) scheduled = context.executor().schedule(() -> { scheduled = null; pump(); }, due - now, TimeUnit.NANOSECONDS);
                        break;
                    }
                    if (outstanding >= scenario.window || !context.channel().isWritable()) {
                        if (!context.channel().isWritable()) backpressure++;
                        else windowStalls++;
                        if (scenario.intervalNanos == 0) break;
                        // An arrival that cannot be admitted remains visible as a dropped sample (-1).
                        latency[next] = rtt[next] = -1; next++; dropped++; continue;
                    }
                    int sequence = next++; planned[sequence] = due; sent[sequence] = now; outstanding++;
                    ByteBuf message = context.alloc().ioBuffer(scenario.bytes);
                    message.writeInt(sequence).writeInt(~sequence).writeZero(scenario.bytes - 8);
                    context.write(message).addListener(f -> { if (!f.isSuccess()) done.completeExceptionally(f.cause()); });
                }
                context.flush();
                if (next == scenario.count && outstanding == 0) done.complete(null);
                else if (batch > 64) context.executor().execute(this::pump);
            } catch (Throwable failure) { done.completeExceptionally(failure); }
            finally { pumping = false; }
        }
        @Override public void channelWritabilityChanged(ChannelHandlerContext ctx) { pump(); }
        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf bytes = (ByteBuf) msg;
            try {
                receive.writeBytes(bytes);
                while (receive.readableBytes() >= scenario.bytes) {
                    int sequence = receive.readInt(), check = receive.readInt();
                    if (sequence < 0 || sequence >= next || check != ~sequence || latency[sequence] != 0) throw new IOException("Corrupt or duplicate response");
                    receive.skipBytes(scenario.bytes - 8);
                    long now = System.nanoTime(); latency[sequence] = now - planned[sequence]; rtt[sequence] = now - sent[sequence];
                    outstanding--; received++;
                }
                receive.discardReadBytes(); pump();
            } catch (Throwable failure) { done.completeExceptionally(failure); }
            finally { bytes.release(); }
        }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { done.completeExceptionally(cause); ctx.close(); }
        @Override public void channelInactive(ChannelHandlerContext ctx) {
            if (!done.isDone()) done.completeExceptionally(new IOException("Connection closed before all replies"));
        }
    }
}

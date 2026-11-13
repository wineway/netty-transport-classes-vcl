package dev.vcl.netty.benchmark;

import dev.vcl.netty.*;
import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.epoll.*;
import org.HdrHistogram.Histogram;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

/** Separate-process, fixed-frame closed-loop echo benchmark. Not a microbenchmark. */
public final class TransportBenchmark {
    private static final int MAGIC = 0x56434c42;
    private TransportBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("server TRANSPORT HOST PORT WORKERS | client HOST PORT BYTES CONNECTIONS WINDOW WARMUP_SECONDS SECONDS");
        if (args[0].equals("server")) server(args);
        else if (args[0].equals("client")) client(args);
        else throw new IllegalArgumentException(args[0]);
    }

    private static void server(String[] args) throws Exception {
        String transport = args[1];
        InetSocketAddress address = new InetSocketAddress(args[2], Integer.parseInt(args[3]));
        int workers = Integer.parseInt(args[4]);
        VclRuntime runtime = transport.equals("vcl") ? VclRuntime.open("vcl-benchmark-server") : null;
        if (runtime == null && !transport.equals("epoll")) throw new IllegalArgumentException(transport);
        if (runtime == null) Epoll.ensureAvailability();
        var group = new MultiThreadIoEventLoopGroup(workers, runtime == null ? EpollIoHandler.newFactory() : VclIoHandler.newFactory(runtime));
        ChannelHandler echo = new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext ctx, Object message) { ctx.write(message); }
                    @Override public void channelReadComplete(ChannelHandlerContext ctx) { ctx.flush(); }
                    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { cause.printStackTrace(); ctx.close(); }
                });
            }
        };
        Channel epoll = null;
        VclServerBinding vcl = null;
        try {
            if (runtime != null) vcl = new VclServerBootstrap(group).childHandler(echo).bind(address).sync().getNow();
            else epoll = new ServerBootstrap().group(group, group).channel(EpollServerSocketChannel.class).childHandler(echo).bind(address).sync().channel();
            System.out.println("READY");
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                while (true) {
                    String command = input.readLine();
                    if (command == null || command.equals("STOP")) break;
                }
            }
        } finally {
            if (epoll != null) epoll.close().sync();
            if (vcl != null) vcl.closeAsync().sync();
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            if (runtime != null) runtime.close();
        }
    }

    private static void client(String[] args) throws Exception {
        String host = args[1];
        int port = Integer.parseInt(args[2]), bytes = Integer.parseInt(args[3]);
        int connections = Integer.parseInt(args[4]), window = Integer.parseInt(args[5]);
        int warmup = Integer.parseInt(args[6]), seconds = Integer.parseInt(args[7]);
        if (bytes < 32 || connections < 1 || window < 1 || warmup < 1 || seconds < 1) throw new IllegalArgumentException("Invalid benchmark dimensions");
        Epoll.ensureAvailability();
        var group = new MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory());
        State state = new State(bytes, window, warmup, seconds);
        List<Channel> channels = new ArrayList<>();
        try {
            for (int i = 0; i < connections; i++) {
                Client handler = new Client(state, i);
                Channel channel = new Bootstrap().group(group).channel(EpollSocketChannel.class)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                        .handler(new ChannelInitializer<Channel>() {
                            @Override protected void initChannel(Channel channel) { channel.pipeline().addLast(handler); }
                        }).connect(host, port).sync().channel();
                state.clients.add(handler); channels.add(channel);
            }
            group.next().execute(state::start);
            String result = state.result.get(warmup + seconds + 20L, TimeUnit.SECONDS);
            System.out.println("RESULT " + result);
        } finally {
            for (Channel channel : channels) channel.close().sync();
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }

    private static long cpuNanos() {
        return ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
    }

    private static final class State {
        final int bytes, window, warmup, seconds;
        final List<Client> clients = new ArrayList<>();
        final Histogram rtt = new Histogram(TimeUnit.SECONDS.toNanos(60), 3);
        final CompletableFuture<String> result = new CompletableFuture<>();
        boolean running, measuring;
        long start, end, completed, cpuStart, cpuEnd, outstanding;
        String json;
        State(int bytes, int window, int warmup, int seconds) {
            this.bytes = bytes; this.window = window; this.warmup = warmup; this.seconds = seconds;
        }
        void start() {
            running = true;
            for (Client client : clients) {
                for (int i = 0; i < window; i++) client.send();
                client.ctx.flush();
            }
            clients.getFirst().ctx.executor().schedule(this::measure, warmup, TimeUnit.SECONDS);
        }
        void measure() {
            if (result.isDone()) return;
            rtt.reset(); completed = 0; cpuStart = cpuNanos(); start = System.nanoTime(); measuring = true;
            System.out.println("MEASURE_START");
            clients.getFirst().ctx.executor().schedule(this::finish, seconds, TimeUnit.SECONDS);
        }
        void finish() {
            end = System.nanoTime(); cpuEnd = cpuNanos(); measuring = false; running = false;
            double elapsed = (end - start) / 1e9;
            json = String.format(Locale.ROOT,
                    "{\"bytes\":%d,\"connections\":%d,\"window\":%d,\"warmup_seconds\":%d,\"elapsed_seconds\":%.9f,\"messages\":%d,\"messages_per_second\":%.3f,\"payload_mib_per_second\":%.3f,\"p50_us\":%.3f,\"p99_us\":%.3f,\"max_us\":%.3f,\"client_cpu_seconds\":%.9f}",
                    bytes, clients.size(), window, warmup, elapsed, completed, completed / elapsed,
                    completed * (double) bytes / elapsed / 1048576, rtt.getValueAtPercentile(50) / 1e3,
                    rtt.getValueAtPercentile(99) / 1e3, rtt.getMaxValue() / 1e3, (cpuEnd - cpuStart) / 1e9);
            System.out.println("MEASURE_END");
            drained();
            if (!result.isDone()) clients.getFirst().ctx.executor().schedule(
                    () -> result.completeExceptionally(new IllegalStateException("in-flight replies did not drain: " + outstanding)), 5, TimeUnit.SECONDS);
        }
        void response(long sent, long now) {
            outstanding--;
            if (measuring && sent >= start) {
                completed++;
                rtt.recordValue(now - sent);
            }
        }
        void drained() {
            if (!running && json != null && outstanding == 0) {
                if (completed == 0) result.completeExceptionally(new IllegalStateException("no measured replies"));
                else result.complete(json);
            }
        }
        void fail(Throwable error) { running = false; result.completeExceptionally(error); }
    }

    private static final class Client extends ChannelInboundHandlerAdapter {
        final State state;
        final int connection;
        final byte[] header = new byte[24];
        ChannelHandlerContext ctx;
        long sent, received, timestamp;
        int headerBytes, bodyRemaining;
        Client(State state, int connection) { this.state = state; this.connection = connection; }
        @Override public void handlerAdded(ChannelHandlerContext context) { ctx = context; }
        void send() {
            ByteBuf buffer = ctx.alloc().directBuffer(state.bytes, state.bytes);
            buffer.writeZero(state.bytes);
            buffer.setInt(0, MAGIC); buffer.setInt(4, connection); buffer.setLong(8, sent);
            buffer.setByte(state.bytes - 1, (byte) (sent ^ connection));
            buffer.setLong(16, System.nanoTime());
            sent++; state.outstanding++;
            ctx.write(buffer).addListener(future -> { if (!future.isSuccess()) state.fail(future.cause()); });
        }
        @Override public void channelRead(ChannelHandlerContext context, Object message) {
            ByteBuf input = (ByteBuf) message;
            try {
                while (input.isReadable()) {
                    if (headerBytes < header.length) {
                        int count = Math.min(input.readableBytes(), header.length - headerBytes);
                        input.readBytes(header, headerBytes, count); headerBytes += count;
                        if (headerBytes != header.length) break;
                        ByteBuf view = Unpooled.wrappedBuffer(header);
                        try {
                            if (view.readInt() != MAGIC || view.readInt() != connection || view.readLong() != received) throw new IllegalStateException("response header mismatch");
                            timestamp = view.readLong();
                        } finally { view.release(); }
                        bodyRemaining = state.bytes - header.length;
                    }
                    int count = Math.min(input.readableBytes(), bodyRemaining);
                    if (count < bodyRemaining) { input.skipBytes(count); bodyRemaining -= count; break; }
                    input.skipBytes(count - 1);
                    if (input.readByte() != (byte) (received ^ connection)) throw new IllegalStateException("response sentinel mismatch");
                    state.response(timestamp, System.nanoTime()); received++; headerBytes = 0; bodyRemaining = 0;
                    if (state.running) send();
                    state.drained();
                }
            } catch (Throwable e) { state.fail(e); context.close(); }
            finally { input.release(); }
        }
        @Override public void channelReadComplete(ChannelHandlerContext context) { context.flush(); }
        @Override public void channelInactive(ChannelHandlerContext context) {
            if (state.running || state.outstanding != 0) state.fail(new IllegalStateException("peer closed before drain"));
        }
        @Override public void exceptionCaught(ChannelHandlerContext context, Throwable cause) { state.fail(cause); context.close(); }
    }
}

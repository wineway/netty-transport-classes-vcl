package dev.vcl.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "VCL_CONFIG", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(30)
class TransportIT {
    static VclRuntime runtime;
    static MultiThreadIoEventLoopGroup clients;
    static int nextPort = 19100;
    final List<Channel> open = new ArrayList<>();
    Peer peer;

    @BeforeAll static void start() throws Exception {
        runtime = VclRuntime.open("netty-vcl-integration");
        clients = new MultiThreadIoEventLoopGroup(2, VclIoHandler.newFactory(runtime));
    }
    @AfterEach void cleanChannels() throws Exception {
        for (Channel channel : open) channel.close().sync();
        if (peer != null) peer.close();
    }
    @AfterAll static void stop() throws Exception {
        try {
            if (clients != null) clients.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            assertEquals(0, runtime.activeWorkers());
        } finally { if (runtime != null) runtime.close(); }
    }

    @Test @Order(1) void sharedListenersAndRepeatedConnections() throws Exception {
        int port = listen("echo");
        for (int i = 0; i < 64; i++) {
            Collector collector = new Collector(64 * 1024, false);
            Channel channel = connect(port, collector, true);
            channel.writeAndFlush(payload(64 * 1024)).sync();
            collector.done.get(5, TimeUnit.SECONDS);
            channel.close().sync();
        }
        peer.close();
        List<String> counts = peer.lines.stream().filter(line -> line.startsWith("WORKER ")).toList();
        assertEquals(2, counts.size(), peer.lines.toString());
        assertEquals(64, counts.stream().mapToInt(line -> Integer.parseInt(line.substring(line.lastIndexOf(' ') + 1))).sum());
        for (String count : counts) assertTrue(Integer.parseInt(count.substring(count.lastIndexOf(' ') + 1)) > 0);
    }

    @Test @Order(2) void manualReadDoesNotConsumeUntilRequested() throws Exception {
        int port = listen("echo");
        Collector collector = new Collector(64, true);
        Channel channel = connect(port, collector, false);
        channel.writeAndFlush(payload(64)).sync();
        assertThrows(TimeoutException.class, () -> collector.done.get(150, TimeUnit.MILLISECONDS));
        channel.read();
        collector.done.get(5, TimeUnit.SECONDS);
    }

    @Test @Order(3) void largeWriteBackpressureAndCrossThreadWakeup() throws Exception {
        int port = listen("paused");
        int bytes = 16 * 1024 * 1024;
        Collector collector = new Collector(bytes, false);
        Channel channel = connect(port, collector, true);
        channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1024, 4096));
        ChannelFuture write = channel.writeAndFlush(payload(bytes));
        assertFalse(write.await(150, TimeUnit.MILLISECONDS), "write should block behind the paused reader");
        assertFalse(channel.isWritable());
        channel.eventLoop().submit(() -> {}).get(1, TimeUnit.SECONDS);
        peer.command("READ");
        write.sync();
        collector.done.get(15, TimeUnit.SECONDS);
        assertTrue(channel.isWritable());
    }

    @Test @Order(4) void outputShutdownRejectsFutureWrites() throws Exception {
        int port = listen("local-shutdown");
        Collector collector = new Collector(0, false);
        VclSocketChannel channel = (VclSocketChannel) connect(port, collector, true);
        channel.writeAndFlush(payload(128)).sync();
        channel.shutdownOutput().sync();
        assertTrue(channel.isOutputShutdown());
        // VPP cut-through cannot establish remote half-close semantics; wire tests cover those.
        ChannelFuture writeAfterShutdown = channel.writeAndFlush(payload(64));
        assertTrue(writeAfterShutdown.await(1, TimeUnit.SECONDS));
        assertFalse(writeAfterShutdown.isSuccess());
    }

    @Test @Order(5) void refusedConnectCompletesAndEventLoopRemainsUsable() throws Exception {
        ChannelFuture future = new Bootstrap().group(clients).channel(VclSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .handler(new ChannelInboundHandlerAdapter() { @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); } })
                .connect("127.0.0.1", 19999);
        assertTrue(future.await(3, TimeUnit.SECONDS));
        assertFalse(future.isSuccess());
        future.channel().close().sync();
        clients.next().submit(() -> {}).get(1, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, () -> runtime.close());
    }

    @Test @Order(6) void virtualThreadsCannotOwnVclWorkers() throws Exception {
        int before = runtime.activeWorkers();
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try (VclPoller ignored = new VclPoller(runtime)) { result.complete(null); }
            catch (Throwable e) { result.complete(e); }
        });
        assertInstanceOf(IllegalArgumentException.class, result.get(2, TimeUnit.SECONDS));
        thread.join();
        assertEquals(before, runtime.activeWorkers());
    }

    private int listen(String mode) throws Exception {
        int port = nextPort++;
        peer = new Peer(mode, port);
        return port;
    }
    private Channel connect(int port, Collector collector, boolean autoRead) throws Exception {
        Channel channel = new Bootstrap().group(clients).channel(VclSocketChannel.class).option(ChannelOption.AUTO_READ, autoRead)
                .handler(new ChannelInitializer<VclSocketChannel>() {
                    @Override protected void initChannel(VclSocketChannel channel) { channel.pipeline().addLast(collector); }
                }).connect("127.0.0.1", port).sync().channel();
        open.add(channel); return channel;
    }
    static ByteBuf payload(int size) {
        ByteBuf buffer = Unpooled.buffer(size);
        for (int i = 0; i < size; i++) buffer.writeByte(i);
        return buffer;
    }
    private static final class Peer implements AutoCloseable {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final Process process;
        final Thread reader;
        final BufferedWriter commands;
        boolean closed;
        Peer(String mode, int port) throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            process = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "-cp", cp, PeerServer.class.getName(), mode, Integer.toString(port)).redirectErrorStream(true).start();
            commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            CompletableFuture<Void> ready = new CompletableFuture<>();
            reader = Thread.ofPlatform().daemon().start(() -> {
                try (BufferedReader input = process.inputReader()) {
                    for (String line; (line = input.readLine()) != null;) {
                        lines.add(line);
                        if (line.equals("READY")) ready.complete(null);
                    }
                    if (!ready.isDone()) ready.completeExceptionally(new IllegalStateException(lines.toString()));
                } catch (Exception e) { ready.completeExceptionally(e); }
            });
            try { ready.get(10, TimeUnit.SECONDS); }
            catch (Exception e) { process.destroyForcibly(); throw new IllegalStateException(lines.toString(), e); }
        }
        void command(String command) throws IOException { commands.write(command); commands.newLine(); commands.flush(); }
        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            try {
                command("STOP");
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), lines.toString());
                reader.join(1000);
                assertEquals(0, process.exitValue(), lines.toString());
            } finally { process.destroyForcibly(); commands.close(); }
        }
    }
    private static final class Collector extends ChannelInboundHandlerAdapter {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final int expected;
        final boolean manual;
        int received;
        Collector(int expected, boolean manual) { this.expected = expected; this.manual = manual; }
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            ByteBuf buffer = (ByteBuf) message;
            try {
                while (buffer.isReadable()) {
                    if (buffer.readByte() != (byte) received++) throw new IllegalStateException("payload mismatch at " + received);
                }
                if (received > expected) throw new IllegalStateException("too many bytes");
                if (received == expected) done.complete(null);
            } catch (Exception e) { done.completeExceptionally(e); ctx.close(); }
            finally { buffer.release(); }
        }
        @Override public void channelReadComplete(ChannelHandlerContext ctx) { if (manual && received < expected) ctx.read(); }
        @Override public void channelInactive(ChannelHandlerContext ctx) { if (received < expected) done.completeExceptionally(new IllegalStateException("early EOF: " + received)); }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { done.completeExceptionally(cause); ctx.close(); }
    }
}

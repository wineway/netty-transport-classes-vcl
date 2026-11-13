/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual TCP/UDP protocol path: two VPPs joined with memif, or two kernel epoll endpoints for the reference. */
@Timeout(45)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NetworkTransportIT {
    private RealTransportSupport transport;
    @BeforeAll void openTransport() throws Exception { transport = new RealTransportSupport(); }
    @AfterAll void closeTransport() throws Exception { if (transport != null) transport.close(); }
    private Process peer;
    private Path ready;
    private String backend;
    @BeforeAll void startPeer() throws Exception {
        backend = System.getProperty("vcl.it.backend", "epoll");
        Path directory = Path.of(System.getProperty("vcl.it.output", "target/network-it")).toAbsolutePath();
        Files.createDirectories(directory); ready = directory.resolve("peer.ready"); Files.deleteIfExists(ready);
        var command = new ArrayList<String>();
        String peerCpus = System.getProperty("vcl.it.peerCpus");
        if (peerCpus != null) command.addAll(List.of("taskset", "-c", peerCpus));
        command.addAll(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dvcl.it.backend=" + backend));
        if (!backend.equals("epoll")) {
            command.add("-Dvcl.it.library=" + System.getProperty("vcl.it.library"));
            command.add("-Dvcl.it.socket=" + Objects.requireNonNull(System.getProperty("vcl.it.peerSocket"), "peer VPP socket"));
            command.add("-Dvcl.it.ipv4=10.88.0.2"); command.add("-Dvcl.it.ipv6=fd88::2");
        }
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), NetworkPeerMain.class.getName(), ready.toString()));
        peer = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("peer.log.txt").toFile()).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(ready)) {
            if (!peer.isAlive()) throw new IllegalStateException("Peer exited " + peer.exitValue() + "; see " + directory);
            if (System.nanoTime() >= deadline) throw new TimeoutException("Peer did not start");
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }
    @AfterAll void stopPeer() throws Exception {
        if (peer != null) {
            peer.destroy();
            if (!peer.waitFor(10, TimeUnit.SECONDS)) { peer.destroyForcibly(); peer.waitFor(); fail("Peer cleanup timed out"); }
        }
    }
    private InetSocketAddress peer(boolean ipv6, int port) {
        return new InetSocketAddress(backend.equals("epoll") ? ipv6 ? "::1" : "127.0.0.1" : ipv6 ? "fd88::2" : "10.88.0.2", port);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void tcpPayloadAndAddresses(boolean ipv6) throws Exception {
        { var transport = this.transport;
            var capture = new ChannelStateTest.Capture(); Channel client = transport.connect(peer(ipv6, 32100), capture);
            var local = (InetSocketAddress) client.localAddress();
            assertTrue(local.getPort() > 0); assertFalse(local.getAddress().isAnyLocalAddress());
            byte[] expected = new byte[128 * 1024]; for (int i = 0; i < expected.length; i++) expected[i] = (byte) i;
            client.writeAndFlush(Unpooled.wrappedBuffer(expected)).sync();
            int received = 0;
            while (received < expected.length) {
                ByteBuf bytes = (ByteBuf) capture.reads.poll(10, TimeUnit.SECONDS); assertNotNull(bytes, () -> capture.failures.toString());
                try { while (bytes.isReadable()) assertEquals(expected[received++], bytes.readByte()); }
                finally { bytes.release(); }
            }
            client.close().sync();
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void udpBindZeroTruncationConnectAndDisconnect(boolean ipv6) throws Exception {
        { var transport = this.transport;
            var capture = new ChannelStateTest.Capture(); var channel = (DatagramChannel) transport.udp(ipv6, capture);
            assertTrue(channel.localAddress().getPort() > 0);
            channel.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(8));
            channel.writeAndFlush(new DatagramPacket(Unpooled.buffer(32).writeZero(32), peer(ipv6, 32101))).sync();
            DatagramPacket truncated = (DatagramPacket) capture.reads.poll(10, TimeUnit.SECONDS); assertNotNull(truncated, () -> capture.failures.toString());
            try { assertEquals(8, truncated.content().readableBytes()); assertEquals(peer(ipv6, 32101), truncated.sender()); }
            finally { truncated.release(); }
            InetSocketAddress bound = channel.localAddress();
            channel.connect(peer(ipv6, 32101)).sync(); assertTrue(channel.isConnected());
            channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{7})).sync();
            DatagramPacket connected = (DatagramPacket) capture.reads.poll(10, TimeUnit.SECONDS); assertNotNull(connected);
            try { assertEquals(7, connected.content().getByte(0)); } finally { connected.release(); }
            channel.disconnect().sync(); assertFalse(channel.isConnected()); assertFalse(channel.isActive());
            channel.connect(peer(ipv6, 32101)).sync(); assertTrue(channel.isActive());
            channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(new byte[]{8}), peer(ipv6, 32101))).sync();
            DatagramPacket disconnected = (DatagramPacket) capture.reads.poll(10, TimeUnit.SECONDS); assertNotNull(disconnected);
            try { assertEquals(8, disconnected.content().getByte(0)); } finally { disconnected.release(); }
            channel.close().sync();
        }
    }
    @Test void realTcpHalfCloseKeepsReceiving() throws Exception {
        { var transport = this.transport;
            var capture = new ChannelStateTest.Capture(); var channel = (SocketChannel) transport.connect(peer(false, 32102), capture);
            channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{1,2,3})).sync(); channel.shutdownOutput().sync();
            ByteBuf reply = (ByteBuf) capture.reads.poll(10, TimeUnit.SECONDS); assertNotNull(reply, () -> capture.failures.toString());
            try { assertEquals(9, reply.getByte(0)); } finally { reply.release(); }
            assertTrue(channel.isActive()); channel.close().sync();
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unboundUdpRejectsWriteThenConnects(boolean ipv6) throws Exception {
        { var transport = this.transport;
            for (boolean connect : new boolean[]{false, true}) {
                var capture = new ChannelStateTest.Capture();
                var bootstrap = new io.netty.bootstrap.Bootstrap().group(transport.group)
                        .channel(transport.datagramClass).handler(capture);
                Channel channel = connect ? bootstrap.connect(peer(ipv6, 32101)).sync().channel()
                        : bootstrap.register().sync().channel();
                try {
                    if (!connect) {
                        var packet = new DatagramPacket(Unpooled.wrappedBuffer(new byte[]{42}), peer(ipv6, 32101));
                        var rejected = channel.writeAndFlush(packet); rejected.await();
                        assertInstanceOf(java.nio.channels.NotYetConnectedException.class, rejected.cause());
                        assertEquals(0, packet.refCnt()); assertTrue(channel.isOpen());
                        channel.connect(peer(ipv6, 32101)).sync();
                    }
                    channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{42})).sync();
                    DatagramPacket reply = (DatagramPacket) capture.reads.poll(5, TimeUnit.SECONDS);
                    assertNotNull(reply, () -> capture.failures.toString());
                    try { assertEquals(42, reply.content().readByte()); assertEquals(peer(ipv6, 32101), reply.sender()); }
                    finally { reply.release(); }
                    var local = (InetSocketAddress) channel.localAddress(); assertTrue(local.getPort() > 0);
                } finally { channel.close().sync(); }
            }
        }
    }
    @Test void manualReadPauseResumeAndRetainedBuffer() throws Exception {
        { var transport = this.transport;
            var capture = new ChannelStateTest.Capture();
            Channel channel = new io.netty.bootstrap.Bootstrap().group(transport.group).channel(transport.socketClass)
                    .option(ChannelOption.AUTO_READ, false).handler(capture).connect(peer(false, 32100)).sync().channel();
            try {
                channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{1,2,3,4})).sync();
                assertNull(capture.reads.poll(50, TimeUnit.MILLISECONDS));
                channel.read(); ByteBuf first = (ByteBuf) capture.reads.poll(5, TimeUnit.SECONDS); assertNotNull(first);
                ByteBuf retained = first.retainedSlice(); java.nio.ByteBuffer nio = retained.nioBuffer(); first.release();
                try {
                    channel.config().setAutoRead(true);
                    channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{5})).sync();
                    ByteBuf second = (ByteBuf) capture.reads.poll(5, TimeUnit.SECONDS); assertNotNull(second);
                    second.release(); channel.close().sync();
                    assertEquals(1, retained.getByte(0)); assertEquals(1, nio.get(0));
                } finally { retained.release(); }
            } finally { channel.close().sync(); }
        }
    }
    @Test void refusedConnectionFailsAndCloses() throws Exception {
        { var transport = this.transport;
            var capture = new ChannelStateTest.Capture();
            var connection = new io.netty.bootstrap.Bootstrap().group(transport.group).channel(transport.socketClass)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000).handler(capture).connect(peer(false, 32999));
            assertTrue(connection.await(5, TimeUnit.SECONDS)); assertFalse(connection.isSuccess());
            assertInstanceOf(java.net.ConnectException.class, connection.cause());
            assertFalse(connection.cause() instanceof ConnectTimeoutException, "RST must report refusal, not timeout");
            assertTrue(connection.channel().closeFuture().await(5, TimeUnit.SECONDS));
            assertFalse(connection.channel().isOpen());
        }
    }

    @Test void blockedWriteResumesWithoutStarvingTasksOrTimers() throws Exception {
        int size = 16 * 1024 * 1024;
        var received = new CompletableFuture<Void>();
        var bytes = new java.util.concurrent.atomic.AtomicInteger();
        Channel channel = transport.connect(peer(false, 32104), new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ByteBuf buffer = (ByteBuf) msg;
                try {
                    int count = buffer.readableBytes();
                    while (buffer.readableBytes() >= 8) assertEquals(0, buffer.readLong());
                    while (buffer.isReadable()) assertEquals(0, buffer.readByte());
                    if (bytes.addAndGet(count) == size) received.complete(null);
                } catch (Throwable failure) { received.completeExceptionally(failure); }
                finally { buffer.release(); }
            }
            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable failure) { received.completeExceptionally(failure); }
        });
        ByteBuf payload = Unpooled.directBuffer(size).writeZero(size);
        try {
            ChannelFuture write = channel.writeAndFlush(payload);
            assertFalse(channel.eventLoop().submit(channel::isWritable).get(5, TimeUnit.SECONDS));
            assertFalse(write.isDone(), "remote read is paused and the large write must be pending");
            channel.eventLoop().submit(() -> {}).get(1, TimeUnit.SECONDS);
            channel.eventLoop().schedule(() -> {}, 10, TimeUnit.MILLISECONDS).get(1, TimeUnit.SECONDS);
            assertTrue(write.await(20, TimeUnit.SECONDS)); assertTrue(write.isSuccess(), () -> String.valueOf(write.cause()));
            received.get(20, TimeUnit.SECONDS);
            assertEquals(0, payload.refCnt());
            assertTrue(channel.eventLoop().submit(channel::isWritable).get(5, TimeUnit.SECONDS));
        } finally { channel.close().sync(); }
    }

}

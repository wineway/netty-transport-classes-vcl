/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ChannelStateTest {
    static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 23456);
    static final class Capture extends ChannelInboundHandlerAdapter {
        final BlockingQueue<Object> reads = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();
        final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        final List<String> order = new CopyOnWriteArrayList<>();
        @Override public void channelRegistered(ChannelHandlerContext ctx) { order.add("registered"); ctx.fireChannelRegistered(); }
        @Override public void channelActive(ChannelHandlerContext ctx) { order.add("active"); ctx.fireChannelActive(); }
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) { reads.add(message); }
        @Override public void channelInactive(ChannelHandlerContext ctx) { order.add("inactive"); ctx.fireChannelInactive(); }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) { failures.add(error); }
        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) { events.add(event); }
    }
    static final class Fixture implements AutoCloseable {
        final MultiThreadIoEventLoopGroup group;
        final EventLoop loop;
        final CompletableFuture<SimulatedIo> ready = new CompletableFuture<>();
        final SimulatedIo io;
        final List<AbstractVclChannel> channels = new ArrayList<>();
        Fixture(VclMode mode) throws Exception {
            group = new MultiThreadIoEventLoopGroup(1, Thread.ofPlatform().name("vcl-test-", 0).factory(),
                    executor -> new VclIoHandler(executor, mode, () -> { var value = new SimulatedIo(); ready.complete(value); return value; }));
            loop = group.next(); loop.submit(() -> {}).sync(); io = ready.get();
        }
        <T extends AbstractVclChannel> T register(T channel, Capture capture) throws Exception {
            channels.add(channel); channel.pipeline().addLast(capture); loop.register(channel).sync(); return channel;
        }
        void run(Consumer<SimulatedIo> action) throws Exception { loop.submit(() -> action.accept(io)).sync(); }
        @Override public void close() throws Exception {
            for (var channel : channels) channel.close().sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            assertTrue(io.closed); assertTrue(io.sessions.isEmpty());
        }
    }
    @ParameterizedTest @EnumSource(VclMode.class)
    void partialWritesHeapCompositeAndBackpressurePreserveOwnership(VclMode mode) throws Exception {
        try (var f = new Fixture(mode)) {
            var capture = new Capture(); var channel = f.register(new VclSocketChannel(), capture);
            channel.connect(PEER).sync(); f.run(io -> {});
            assertEquals(List.of("registered", "active"), capture.order);
            channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(2, 4));
            f.run(io -> { io.maxWrite = 2; io.writable = false; });
            ByteBuf heap = Unpooled.copiedBuffer("hello", StandardCharsets.US_ASCII);
            var written = channel.writeAndFlush(heap);
            f.run(io -> {});
            assertFalse(written.isDone()); assertFalse(channel.isWritable()); assertEquals(0, heap.refCnt());
            f.run(io -> io.writable = true); written.sync();
            var composite = Unpooled.compositeBuffer().addComponents(true,
                    Unpooled.wrappedBuffer(new byte[]{1, 2}), Unpooled.directBuffer(2).writeBytes(new byte[]{3, 4}));
            channel.writeAndFlush(composite).sync(); assertEquals(0, composite.refCnt());
            f.run(io -> assertArrayEquals(new byte[]{'h','e','l','l','o',1,2,3,4}, io.sessions.get(channel.session).output.toByteArray()));
            assertTrue(channel.isWritable());
        }
    }
    @ParameterizedTest @EnumSource(VclMode.class)
    void manualReadAndRetainedDirectBufferSurviveChannelClose(VclMode mode) throws Exception {
        try (var f = new Fixture(mode)) {
            var capture = new Capture(); var channel = new VclSocketChannel(); channel.config().setAutoRead(false);
            f.register(channel, capture).connect(PEER).sync();
            f.run(io -> io.sessions.get(channel.session).input.add(ByteBuffer.wrap(new byte[]{4,5,6})));
            f.run(io -> {}); assertTrue(capture.reads.isEmpty());
            channel.read(); ByteBuf read = (ByteBuf) capture.reads.poll(3, TimeUnit.SECONDS); assertNotNull(read); assertTrue(read.isDirect());
            ByteBuf slice = read.retainedSlice(); ByteBuffer exported = read.nioBuffer(); read.release();
            channel.close().sync(); assertEquals(4, exported.get()); assertEquals(6, slice.getByte(2)); slice.release();
        }
    }
    @Test void halfClosePreservesOutputAndRejectsWritesAfterOutputShutdown() throws Exception {
        try (var f = new Fixture(VclMode.INTERRUPT)) {
            var capture = new Capture(); var channel = f.register(new VclSocketChannel(), capture);
            channel.config().setAllowHalfClosure(true); channel.connect(PEER).sync();
            f.run(io -> io.sessions.get(channel.session).eof = true);
            assertSame(ChannelInputShutdownEvent.INSTANCE, capture.events.poll(3, TimeUnit.SECONDS));
            assertTrue(channel.isActive()); assertTrue(channel.isInputShutdown());
            channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{7})).sync();
            channel.shutdownOutput().sync(); assertTrue(channel.isShutdown());
            ByteBuf rejected = Unpooled.wrappedBuffer(new byte[]{8}); var future = channel.writeAndFlush(rejected).await();
            assertFalse(future.isSuccess()); assertEquals(0, rejected.refCnt());
        }
    }
    @Test void connectTimeoutFailsAndClosesWithoutLeaking() throws Exception {
        try (var f = new Fixture(VclMode.INTERRUPT)) {
            f.run(io -> io.delayConnect = true);
            var channel = f.register(new VclSocketChannel(), new Capture()); channel.config().setConnectTimeoutMillis(25);
            var connect = channel.connect(PEER).await(); assertInstanceOf(ConnectTimeoutException.class, connect.cause());
            channel.closeFuture().sync(); assertFalse(channel.isOpen());
        }
    }
    @Test void inFlightConnectCancellationClosesSessionAndTimeout() throws Exception {
        try (var f = new Fixture(VclMode.INTERRUPT)) {
            f.run(io -> io.delayConnect = true);
            var channel = f.register(new VclSocketChannel(), new Capture());
            var connect = channel.connect(PEER); f.run(io -> {});
            assertFalse(connect.isDone()); assertTrue(connect.cancel(false));
            channel.closeFuture().sync(); f.run(io -> assertTrue(io.sessions.isEmpty()));
        }
    }
    @Test void connectListenerClosingChannelStillReceivesActiveBeforeInactive() throws Exception {
        try (var f = new Fixture(VclMode.INTERRUPT)) {
            f.run(io -> io.delayConnect = true);
            var capture = new Capture(); var channel = f.register(new VclSocketChannel(), capture);
            channel.connect(PEER).addListener(future -> channel.close()); f.run(io -> {});
            f.run(io -> io.sessions.get(channel.session).connecting = false);
            channel.closeFuture().sync(); f.run(io -> {});
            assertEquals(List.of("registered", "active", "inactive"), capture.order);
        }
    }
    @Test void transientHupDoesNotFailAnInProgressNativeConnect() throws Exception {
        try (var f = new Fixture(VclMode.POLL)) {
            f.run(io -> { io.delayConnect = true; io.transientConnectHup = true; });
            var channel = f.register(new VclDatagramChannel(), new Capture());
            channel.bind(new InetSocketAddress("127.0.0.1", 0)).sync();
            var connect = channel.connect(PEER); f.run(io -> {}); f.run(io -> {});
            assertFalse(connect.isDone()); assertTrue(channel.isOpen());
            f.run(io -> io.sessions.get(channel.session).connecting = false);
            connect.sync(); assertTrue(channel.isConnected());
        }
    }
    @Test void refusalFailsPromiseInsteadOfReportingConnected() throws Exception {
        try (var f = new Fixture(VclMode.POLL)) {
            f.run(io -> { io.delayConnect = true; io.failConnect = true; });
            var capture = new Capture(); var channel = f.register(new VclSocketChannel(), capture);
            var connect = channel.connect(PEER).await(); assertFalse(connect.isSuccess()); channel.closeFuture().sync();
            assertFalse(capture.order.contains("active"));
        }
    }
    @ParameterizedTest @EnumSource(VclMode.class)
    void vppDisconnectClosesExistingChannelsAndRejectsNewRegistrations(VclMode mode) throws Exception {
        try (var f = new Fixture(mode)) {
            var capture = new Capture(); var channel = f.register(new VclSocketChannel(), capture); channel.connect(PEER).sync();
            f.run(io -> io.alive = false); channel.closeFuture().sync(); assertNotNull(capture.failures.poll());
            var next = new VclSocketChannel(); f.channels.add(next);
            assertFalse(f.loop.register(next).await().isSuccess()); assertFalse(next.isOpen());
        }
    }
    @ParameterizedTest @EnumSource(VclMode.class)
    void udpBoundariesTruncationFilteringDisconnectAndEmptyRejection(VclMode mode) throws Exception {
        try (var f = new Fixture(mode)) {
            var capture = new Capture(); var channel = f.register(new VclDatagramChannel(), capture);
            channel.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(2));
            channel.bind(new InetSocketAddress("127.0.0.1", 24000)).sync(); channel.connect(PEER).sync();
            f.run(io -> {
                var s = io.sessions.get(channel.session);
                s.datagrams.add(new SimulatedIo.ReceivedData(new byte[]{9}, new InetSocketAddress("127.0.0.1", 9999)));
                s.datagrams.add(new SimulatedIo.ReceivedData(new byte[]{1,2,3}, PEER));
                s.datagrams.add(new SimulatedIo.ReceivedData(new byte[]{4}, PEER));
            });
            var first = (io.netty.channel.socket.DatagramPacket) capture.reads.poll(3, TimeUnit.SECONDS);
            var second = (io.netty.channel.socket.DatagramPacket) capture.reads.poll(3, TimeUnit.SECONDS);
            assertNotNull(first); assertNotNull(second);
            try { assertEquals(2, first.content().readableBytes()); assertEquals(PEER, first.sender()); assertEquals(4, second.content().getByte(0)); }
            finally { first.release(); second.release(); }
            channel.disconnect().sync(); assertFalse(channel.isActive()); assertFalse(channel.isConnected());
            channel.connect(PEER).sync();
            var empty = Unpooled.directBuffer(1); var rejected = channel.writeAndFlush(empty).await();
            assertInstanceOf(IllegalArgumentException.class, rejected.cause()); assertEquals(0, empty.refCnt());
            channel.writeAndFlush(new io.netty.channel.socket.DatagramPacket(Unpooled.wrappedBuffer(new byte[]{5}), PEER)).sync();
        }
    }
    @Test void staleRegistrationEventDoesNotReachReregisteredSession() throws Exception {
        try (var f = new Fixture(VclMode.INTERRUPT)) {
            var capture = new Capture(); var channel = f.register(new VclSocketChannel(), capture); channel.connect(PEER).sync();
            long old = f.loop.submit(() -> f.io.sessions.get(channel.session).token).get();
            channel.deregister().sync(); f.loop.register(channel).sync();
            f.run(io -> io.staleToken = old); f.run(io -> {});
            assertTrue(channel.isActive()); assertTrue(capture.failures.isEmpty());
        }
    }
    @Test void pollModeNeverUsesBlockingWaitAndTimersRunUnderLoad() throws Exception {
        try (var f = new Fixture(VclMode.POLL)) {
            var capture = new Capture(); var channel = f.register(new VclSocketChannel(), capture); channel.connect(PEER).sync();
            f.run(io -> { for (int i = 0; i < 500; i++) io.sessions.get(channel.session).input.add(ByteBuffer.wrap(new byte[]{1})); });
            f.loop.schedule(() -> {}, 1, TimeUnit.MILLISECONDS).sync();
            f.run(io -> assertEquals(0, io.awaits));
            channel.close().sync(); Object read; while ((read = capture.reads.poll()) != null) ReferenceCountUtil.release(read);
        }
    }
}

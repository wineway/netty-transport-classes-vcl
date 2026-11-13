/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.channel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ServerLifecycleTest {
    @ParameterizedTest @EnumSource(VclMode.class)
    void allListenersBindSamePortAndChildStaysOnAcceptingLoop(VclMode mode) throws Exception {
        var ios = new ConcurrentHashMap<Thread, SimulatedIo>();
        var group = group(mode, ios, false);
        BlockingQueue<Channel> children = new LinkedBlockingQueue<>();
        VclServer server = null;
        try {
            var bound = VclServer.bind(group, new InetSocketAddress("127.0.0.1", 0), () -> new ChannelInboundHandlerAdapter() {
                @Override public void channelActive(ChannelHandlerContext ctx) { children.add(ctx.channel()); }
            }).sync();
            server = bound.getNow(); assertEquals(2, server.listeners().size());
            assertTrue(server.localAddress().getPort() > 0);
            for (Channel listener : server.listeners()) {
                assertEquals(server.localAddress(), listener.localAddress());
                for (int connection = 0; connection < 3; connection++) {
                    listener.eventLoop().submit(() -> {
                        var io = ios.get(Thread.currentThread());
                        try {
                            int child = io.create(false);
                            io.sessions.get(child).local = serverAddress(listener);
                            io.sessions.get(((VclServerSocketChannel) listener).session).accepted.add(
                                    new VclIo.Accepted(child, ChannelStateTest.PEER));
                        } catch (Exception e) { throw new RuntimeException(e); }
                    }).sync();
                    Channel child = children.poll(3, TimeUnit.SECONDS); assertNotNull(child);
                    assertSame(listener.eventLoop(), child.eventLoop()); assertSame(listener, child.parent());
                    child.close().sync();
                }
            }
            server.listeners().getFirst().close().sync();
            assertTrue(server.listeners().getLast().isActive());
            server.close().sync(); server.close().sync();
        } finally {
            if (server != null) server.close().await();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            for (var io : ios.values()) { assertTrue(io.closed); assertTrue(io.sessions.isEmpty()); }
        }
    }
    @Test void partialBindFailureClosesEveryCreatedListenerBeforeReportingFailure() throws Exception {
        var ios = new ConcurrentHashMap<Thread, SimulatedIo>(); var group = group(VclMode.INTERRUPT, ios, true);
        try {
            var bound = VclServer.bind(group, new InetSocketAddress("127.0.0.1", 24000), ChannelInboundHandlerAdapter::new).await();
            assertFalse(bound.isSuccess());
            for (var executor : group) executor.submit(() -> assertTrue(ios.get(Thread.currentThread()).sessions.isEmpty())).sync();
        } finally { group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); }
    }
    @Test void foreignRegistrationIsRejectedWithoutClosingOrMovingNativeSession() throws Exception {
        var ios = new ConcurrentHashMap<Thread, SimulatedIo>(); var group = group(VclMode.POLL, ios, false);
        var loops = new ArrayList<EventLoop>(); for (var loop : group) loops.add((EventLoop) loop);
        var channel = new VclSocketChannel();
        try {
            loops.get(0).register(channel).sync(); channel.connect(ChannelStateTest.PEER).sync(); channel.deregister().sync();
            var foreign = loops.get(1).register(channel).await(); assertFalse(foreign.isSuccess());
            assertTrue(channel.isOpen()); assertSame(loops.get(0), channel.eventLoop());
            loops.get(0).register(channel).sync(); channel.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{1})).sync();
        } finally { channel.close().sync(); group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); }
    }
    private static InetSocketAddress serverAddress(Channel listener) { return (InetSocketAddress) listener.localAddress(); }
    private static MultiThreadIoEventLoopGroup group(VclMode mode, ConcurrentHashMap<Thread, SimulatedIo> ios, boolean failSecond) {
        var count = new AtomicInteger();
        return new MultiThreadIoEventLoopGroup(2, Thread.ofPlatform().name("vcl-multi-test-", 0).factory(),
                executor -> new VclIoHandler(executor, mode, () -> {
                    var io = new SimulatedIo(); io.rejectBind = count.incrementAndGet() == 2 && failSecond;
                    ios.put(Thread.currentThread(), io); return io;
                }));
    }
}

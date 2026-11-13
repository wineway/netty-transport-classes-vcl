/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.channel.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class RuntimeLifecycleTest {
    static class Application implements VclApplication {
        final List<SimulatedIo> workers = new CopyOnWriteArrayList<>();
        Thread control;
        int starts, destroys;
        @Override public void start() { control = Thread.currentThread(); assertFalse(control.isVirtual()); starts++; }
        @Override public VclIo newWorker() {
            assertNotSame(control, Thread.currentThread()); var io = new SimulatedIo(); workers.add(io); return io;
        }
        @Override public void destroy() {
            assertSame(control, Thread.currentThread());
            for (var worker : workers) assertTrue(worker.closed);
            destroys++;
        }
    }
    @Test void workersStopBeforeApplicationAndDuplicateCloseIsHarmless() throws Exception {
        var app = new Application(); var runtime = VclRuntime.open(VclMode.INTERRUPT, 2, () -> app);
        var group = new MultiThreadIoEventLoopGroup(2, runtime.ioHandlerFactory());
        try {
            for (var executor : group) executor.submit(() -> {}).sync();
            assertThrows(IllegalStateException.class, runtime::close);
            assertThrows(IllegalStateException.class, () -> VclRuntime.open(VclMode.INTERRUPT, 1, Application::new));
        } finally { group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); runtime.close(); }
        runtime.close(); assertEquals(1, app.starts); assertEquals(1, app.destroys); assertEquals(2, app.workers.size());
        assertThrows(IllegalStateException.class, () -> VclRuntime.open(VclMode.POLL, 1, Application::new));
    }
    @Test void failedStartReleasesExclusiveOwnership() throws Exception {
        var bad = new Application() { @Override public void start() { throw new IllegalStateException("injected startup failure"); } };
        assertThrows(IllegalStateException.class, () -> VclRuntime.open(VclMode.INTERRUPT, 1, () -> bad));
        assertEquals(0, bad.destroys);
        var good = new Application(); try (var ignored = VclRuntime.open(VclMode.INTERRUPT, 1, () -> good)) { }
        assertEquals(1, good.destroys);
    }
    @Test void failureInOneWorkerClosesChannelsAcrossRuntime() throws Exception {
        var app = new Application(); var runtime = VclRuntime.open(VclMode.INTERRUPT, 2, () -> app);
        var group = new MultiThreadIoEventLoopGroup(2, runtime.ioHandlerFactory());
        var channels = new ArrayList<VclSocketChannel>();
        try {
            for (var executor : group) {
                var channel = new VclSocketChannel(); channels.add(channel);
                channel.pipeline().addLast(new ChannelStateTest.Capture());
                ((EventLoop) executor).register(channel).sync(); channel.connect(ChannelStateTest.PEER).sync();
            }
            channels.getFirst().eventLoop().submit(() -> app.workers.getFirst().alive = false).sync();
            for (var channel : channels) channel.closeFuture().sync();
            assertNotNull(runtime.failure());
        } finally {
            for (var channel : channels) channel.close().await();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); runtime.close();
        }
    }
    @Test void signedNativeHandlesRemainValid() throws Exception {
        assertEquals(0x80000001, VclException.handle("create", 0x80000001));
        assertEquals(0xfe000001, VclException.handle("create", 0xfe000001));
        assertThrows(VclException.class, () -> VclException.handle("create", -11));
        try (var fixture = new ChannelStateTest.Fixture(VclMode.POLL)) {
            fixture.run(io -> io.next = 0x80000001);
            var channel = fixture.register(new VclSocketChannel(), new ChannelStateTest.Capture()); channel.connect(ChannelStateTest.PEER).sync();
            channel.close().sync(); fixture.run(io -> assertTrue(io.sessions.isEmpty()));
        }
    }
}

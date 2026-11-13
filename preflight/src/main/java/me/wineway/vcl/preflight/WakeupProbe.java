/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl.preflight;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.*;
import static java.lang.foreign.ValueLayout.*;

/** Tests libc eventfd/epoll calls separately from VCL's virtual session epoll. */
final class WakeupProbe {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();
    private static final MethodHandle EVENTFD = call("eventfd", JAVA_INT, JAVA_INT, JAVA_INT);
    private static final MethodHandle EPOLL_CREATE = call("epoll_create1", JAVA_INT, JAVA_INT);
    private static final MethodHandle EPOLL_CTL = call("epoll_ctl", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
    private static final MethodHandle EPOLL_WAIT = call("epoll_wait", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
    private static final MethodHandle WRITE = call("write", JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG);
    private static final MethodHandle READ = call("read", JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG);
    private static final MethodHandle CLOSE = call("close", JAVA_INT, JAVA_INT);

    private static MethodHandle call(String name, MemoryLayout result, MemoryLayout... args) {
        return LINKER.downcallHandle(LIBC.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
    }

    static void run() throws Throwable { test(-1, 1000); }
    static void nested(int vclMqFd) throws Throwable { test(vclMqFd, 100); }

    private static void test(int mqFd, int iterations) throws Throwable {
        int wake = Probe.ok((int) EVENTFD.invokeExact(0, 2048 | 524288), "eventfd");
        int epoll = -1;
        try (var arena = Arena.ofShared(); var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().factory())) {
            epoll = Probe.ok((int) EPOLL_CREATE.invokeExact(524288), "epoll_create1");
            var event = arena.allocate(Abi.EPOLL);
            event.set(JAVA_INT_UNALIGNED, 0, 1);
            event.set(JAVA_LONG_UNALIGNED, 4, 1);
            Probe.ok((int) EPOLL_CTL.invokeExact(epoll, 1, wake, event), "epoll_ctl(eventfd)");
            if (mqFd >= 0) {
                event.set(JAVA_LONG_UNALIGNED, 4, 2);
                Probe.ok((int) EPOLL_CTL.invokeExact(epoll, 1, mqFd, event), "epoll_ctl(vcl mq epoll)");
            }
            var signal = arena.allocateFrom(JAVA_LONG, 1L);
            var readback = arena.allocate(JAVA_LONG);
            var events = arena.allocate(Abi.EPOLL, 2);
            for (int i = 0; i < iterations; i++) {
                final boolean beforeWait = (i & 1) == 0;
                var begin = new CountDownLatch(beforeWait ? 0 : 1);
                Future<?> writer = executor.submit(() -> {
                    try {
                        if (!begin.await(5, TimeUnit.SECONDS)) throw new AssertionError("Writer start timeout");
                        long rv = (long) WRITE.invokeExact(wake, signal, 8L);
                        Probe.require(rv == 8, "eventfd write=" + rv);
                    } catch (Throwable t) { throw new CompletionException(t); }
                });
                if (beforeWait) writer.get(5, TimeUnit.SECONDS);
                begin.countDown();
                // A bounded wait covers both a signal pending before entry and one racing with entry.
                int n = (int) EPOLL_WAIT.invokeExact(epoll, events, 2, 1000);
                Probe.ok(n, "epoll_wait");
                boolean sawWake = false;
                for (int j = 0; j < n; j++) sawWake |= events.get(JAVA_LONG_UNALIGNED, j * 12L + 4) == 1;
                Probe.require(sawWake, "Lost eventfd notification");
                writer.get(5, TimeUnit.SECONDS);
                long rv = (long) READ.invokeExact(wake, readback, 8L);
                Probe.require(rv == 8 && readback.get(JAVA_LONG, 0) == 1, "eventfd drain");
            }
            // Zero deadline must be nonblocking; no wall-clock performance assertion.
            Probe.require((int) EPOLL_WAIT.invokeExact(epoll, events, 2, 0) == 0, "Spurious wake after drain");
            System.out.println("{\"test\":\"libc_wakeup\",\"iterations\":" + iterations
                    + ",\"vcl_mq_nested\":" + (mqFd >= 0) + ",\"passed\":true}");
        } finally {
            if (epoll >= 0) Probe.ok((int) CLOSE.invokeExact(epoll), "close(epoll)");
            Probe.ok((int) CLOSE.invokeExact(wake), "close(eventfd)");
        }
    }
}

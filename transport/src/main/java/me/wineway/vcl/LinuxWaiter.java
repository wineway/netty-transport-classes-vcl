/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.*;

/** Linux-only wait resources. Only wakeup() may be called from another thread. */
final class LinuxWaiter implements AutoCloseable {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final StructLayout ERRNO = Linker.Option.captureStateLayout();
    private static final long ERRNO_OFFSET = ERRNO.byteOffset(groupElement("errno"));
    private static final MethodHandle EVENTFD = call("eventfd", JAVA_INT, JAVA_INT, JAVA_INT);
    private static final MethodHandle EVENTFD_WRITE = call("eventfd_write", JAVA_INT, JAVA_INT, JAVA_LONG);
    private static final MethodHandle EVENTFD_READ = call("eventfd_read", JAVA_INT, JAVA_INT, ADDRESS);
    private static final MethodHandle EPOLL_CREATE = call("epoll_create1", JAVA_INT, JAVA_INT);
    private static final MethodHandle EPOLL_CTL = call("epoll_ctl", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
    private static final MethodHandle EPOLL_WAIT = call("epoll_wait", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
    private static final MethodHandle EPOLL_PWAIT2 = LINKER.defaultLookup().find("epoll_pwait2")
            .map(symbol -> LINKER.downcallHandle(symbol,
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    Linker.Option.captureCallState("errno"))).orElse(null);
    private static final MethodHandle SOCKET = call("socket", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    private static final MethodHandle CONNECT = call("connect", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle POLL = call("poll", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT);
    private static final MethodHandle CLOSE = call("close", JAVA_INT, JAVA_INT);
    private static final int CLOEXEC = 524288, NONBLOCK = 2048;

    private final Thread owner = Thread.currentThread();
    private final Arena arena = Arena.ofConfined();
    private final Arena wakeArena = Arena.ofShared();
    private final MemorySegment error = arena.allocate(ERRNO);
    private final MemorySegment wakeError = wakeArena.allocate(ERRNO);
    private final MemorySegment events = arena.allocate(12 * 3, 1);
    private final MemorySegment event = arena.allocate(12, 1);
    private final MemorySegment pollfd = arena.allocate(8, 4);
    private final MemorySegment timeoutSpec = arena.allocate(16, 8);
    private boolean preciseWait = EPOLL_PWAIT2 != null;
    private final MemorySegment counter = arena.allocate(JAVA_LONG);
    private final Object wakeLock = new Object();
    private final VclMode mode;
    private boolean closed;
    private int epoll = -1, wakeFd = -1, monitorFd = -1;

    LinuxWaiter(VclMode mode, String sapiPath, int vclMqFd) throws IOException {
        this.mode = mode;
        try {
            java.util.Objects.requireNonNull(mode, "mode");
            if (owner.isVirtual()) throw new IllegalArgumentException("VCL requires platform threads");
            if (!System.getProperty("os.name").equals("Linux") || !System.getProperty("os.arch").equals("amd64")) {
                throw new UnsupportedOperationException("Linux x86_64 required");
            }
            if (sapiPath != null) {
                byte[] path = sapiPath.getBytes(StandardCharsets.UTF_8);
                if (path.length >= 108 || sapiPath.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("Invalid SAPI socket path");
                }
                monitorFd = check((int) SOCKET.invokeExact(error, 1, 5 | NONBLOCK | CLOEXEC, 0), "socket");
                var address = arena.allocate(110, 2);
                address.set(JAVA_SHORT, 0, (short) 1);
                MemorySegment.copy(path, 0, address, JAVA_BYTE, 2, path.length);
                check((int) CONNECT.invokeExact(error, monitorFd, address, path.length + 3), "connect monitor");
                pollfd.set(JAVA_INT, 0, monitorFd);
            }
            if (mode == VclMode.INTERRUPT) {
                epoll = check((int) EPOLL_CREATE.invokeExact(error, CLOEXEC), "epoll_create1");
                wakeFd = check((int) EVENTFD.invokeExact(error, 0, NONBLOCK | CLOEXEC), "eventfd");
                add(wakeFd, 1, 1);
                if (vclMqFd >= 0) add(vclMqFd, 1, 2);
                if (monitorFd >= 0) add(monitorFd, 0, 3);
            } else if (vclMqFd >= 0) {
                throw new IllegalArgumentException("Poll mode must disable VCL MQ eventfd");
            }
        } catch (Throwable failure) {
            try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw translate(failure);
        }
    }

    private static MethodHandle call(String name, MemoryLayout result, MemoryLayout... args) {
        return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(),
                FunctionDescriptor.of(result, args), Linker.Option.captureCallState("errno"));
    }
    private void add(int fd, int interest, long token) throws Throwable {
        event.set(JAVA_INT_UNALIGNED, 0, interest);
        event.set(JAVA_LONG_UNALIGNED, 4, token);
        check((int) EPOLL_CTL.invokeExact(error, epoll, 1, fd, event), "epoll_ctl");
    }

    /** Independent of VCL's MQ mode. HUP/ERR/NVAL are reported even with events=0. */
    boolean alive() throws IOException {
        checkOwner();
        if (monitorFd < 0) return true;
        pollfd.set(JAVA_SHORT, 6, (short) 0);
        try {
            int n = (int) POLL.invokeExact(error, pollfd, 1L, 0);
            if (n < 0 && errno(error) == 4) return true; // Recheck on the next bounded iteration.
            check(n, "poll monitor");
            return (pollfd.get(JAVA_SHORT, 6) & (8 | 16 | 32)) == 0;
        } catch (Throwable failure) { throw translate(failure); }
    }

    /** Returns false on VPP socket closure. timeoutNanos <= 0 never blocks. */
    boolean await(long timeoutNanos) throws IOException {
        checkOwner();
        if (mode == VclMode.POLL) return alive();
        long millis = timeoutNanos <= 0 ? 0 : 1 + (timeoutNanos - 1) / 1_000_000;
        int timeout = (int) Math.min(Integer.MAX_VALUE, millis);
        try {
            int n;
            if (preciseWait) {
                long nanos = Math.max(0, timeoutNanos);
                timeoutSpec.set(JAVA_LONG, 0, nanos / 1_000_000_000L);
                timeoutSpec.set(JAVA_LONG, 8, nanos % 1_000_000_000L);
                n = (int) EPOLL_PWAIT2.invokeExact(error, epoll, events, 3, timeoutSpec, MemorySegment.NULL);
                if (n < 0 && errno(error) == 38) { // Older kernel: ENOSYS, retain millisecond fallback.
                    preciseWait = false;
                    n = (int) EPOLL_WAIT.invokeExact(error, epoll, events, 3, timeout);
                }
            } else n = (int) EPOLL_WAIT.invokeExact(error, epoll, events, 3, timeout);
            if (n < 0 && errno(error) == 4) return alive();
            check(n, "epoll_wait");
            for (int i = 0; i < n; i++) {
                long token = events.get(JAVA_LONG_UNALIGNED, 12L * i + 4);
                if (token == 3) return false;
                if (token == 1) drainWakeup();
            }
            return true;
        } catch (Throwable failure) { throw translate(failure); }
    }

    void wakeup() throws IOException {
        // A close cannot recycle the descriptor while another thread is writing to it.
        synchronized (wakeLock) {
            if (closed || wakeFd < 0) return;
            try {
                int rv;
                do { rv = (int) EVENTFD_WRITE.invokeExact(wakeError, wakeFd, 1L); }
                while (rv < 0 && errno(wakeError) == 4);
                if (rv < 0 && errno(wakeError) != 11) {
                    throw new IOException("eventfd_write errno=" + errno(wakeError));
                }
            } catch (Throwable failure) { throw translate(failure); }
        }
    }

    private void drainWakeup() throws Throwable {
        int rv;
        do { rv = (int) EVENTFD_READ.invokeExact(error, wakeFd, counter); }
        while (rv < 0 && errno(error) == 4);
        if (rv < 0 && errno(error) != 11) check(rv, "eventfd_read");
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Wrong EventLoop thread");
        if (closed) throw new IllegalStateException("Waiter is closed");
    }
    private int check(int value, String operation) throws IOException {
        if (value < 0) throw new IOException(operation + " errno=" + errno(error));
        return value;
    }
    private static int errno(MemorySegment state) { return state.get(JAVA_INT, ERRNO_OFFSET); }
    private static IOException translate(Throwable t) {
        if (t instanceof IOException io) return io;
        if (t instanceof RuntimeException runtime) throw runtime;
        if (t instanceof Error error) throw error;
        return new IOException(t);
    }

    @Override public void close() throws IOException {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Wrong EventLoop thread");
        synchronized (wakeLock) {
            if (closed) return;
            closed = true;
            IOException first = null;
            for (int fd : new int[]{epoll, wakeFd, monitorFd}) {
                if (fd < 0) continue;
                try {
                    // Linux releases the fd even on EINTR; never retry close on a recycled number.
                    int rv = (int) CLOSE.invokeExact(error, fd);
                    if (rv < 0 && errno(error) != 4) check(rv, "close");
                } catch (Throwable t) {
                    var next = translate(t);
                    if (first == null) first = next; else first.addSuppressed(next);
                }
            }
            epoll = wakeFd = monitorFd = -1;
            arena.close(); wakeArena.close();
            if (first != null) throw first;
        }
    }
}

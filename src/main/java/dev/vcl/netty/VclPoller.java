package dev.vcl.netty;

import io.netty.channel.IoHandlerContext;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import static java.lang.foreign.ValueLayout.*;

/** Thread-confined VCL worker. Only wakeup() may be invoked from another thread. */
final class VclPoller implements AutoCloseable {
    static final int CAPACITY = 256;
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(VclPoller.class);
    // One immutable native constant for the process, independent of poller destruction.
    private static final MemorySegment WAKE_WORD = Arena.global().allocateFrom(JAVA_LONG, 1L).asReadOnly();
    final NativeVcl api;
    final VclMetrics metrics;
    final int workerIndex;
    private final VclRuntime runtime;
    private final Thread owner = Thread.currentThread();
    private final AtomicBoolean awake = new AtomicBoolean(true);
    private boolean pendingWakeup;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment drainWord = arena.allocate(JAVA_LONG);
    private final MemorySegment pollFds = arena.allocate(NativeVcl.POLLFD, 2);
    private final MemorySegment wakePollFd = arena.allocate(NativeVcl.POLLFD);
    private final MemorySegment events = arena.allocate(NativeVcl.EPOLL_EVENT, CAPACITY);
    private final MemorySegment controlEvent = arena.allocate(NativeVcl.EPOLL_EVENT);
    private int epoll = -1;
    private int wakeFd = -1;
    private boolean closed;

    VclPoller(VclRuntime runtime) throws VclException {
        this.runtime = runtime;
        api = runtime.api;
        metrics = runtime.counters;
        boolean attached = false;
        try {
            if (owner.isVirtual()) throw new IllegalArgumentException("VCL workers require platform threads; native TLS cannot follow virtual-thread carriers");
            runtime.attach(); attached = true;
            workerIndex = api.workerIndex();
            epoll = VclException.check("VCL epoll create", api.epollCreate());
            int mq = api.mqFd();
            if (mq < 0) throw new IllegalStateException("VCL requires use-mq-eventfd");
            wakeFd = api.eventfd();
            if (wakeFd < 0) throw new VclException("eventfd", -api.errno());
            pollFds.set(JAVA_INT, 0, mq);
            pollFds.set(JAVA_SHORT, 4, (short) 1);
            pollFds.set(JAVA_INT, 8, wakeFd);
            pollFds.set(JAVA_SHORT, 12, (short) 1);
            wakePollFd.set(JAVA_INT, 0, wakeFd);
            wakePollFd.set(JAVA_SHORT, 4, (short) 1);
        } catch (RuntimeException | VclException | Error e) {
            if (wakeFd >= 0) api.osClose(wakeFd);
            if (epoll >= 0) api.close(epoll);
            if (attached) runtime.detach();
            arena.close();
            throw e;
        }
    }

    void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("VCL worker accessed from another thread");
        if (closed) throw new IllegalStateException("VCL poller closed");
    }

    void control(int op, int session, int interests, long token) throws VclException {
        checkThread();
        controlEvent.set(JAVA_INT, 0, interests);
        controlEvent.set(JAVA_LONG_UNALIGNED, 4, token);
        VclException.check("VCL epoll ctl", api.epollCtl(epoll, op, session, controlEvent));
    }

    int poll(int timeoutMillis) throws VclException {
        return poll(timeoutMillis, null);
    }

    int poll(int timeoutMillis, IoHandlerContext context) throws VclException {
        checkThread();
        if (api.detached()) throw new VclException("VPP detached", -107);
        // Drain VCL's userspace-ready lists before waiting on OS notification fds.
        int ready = VclException.check("VCL epoll wait", api.epollWait(epoll, events, CAPACITY));
        if (ready != 0 || timeoutMillis == 0) return ready;
        if (pendingWakeup) awaitWakeup();
        int result;
        awake.set(false);
        try {
            // Recheck tasks after arming, as in EpollIoHandler: a wakeup while awake
            // need not write, but its queued task must prevent the subsequent wait.
            if (context != null && !context.canBlock()) return 0;
            result = api.poll(pollFds, Math.min(10, Math.max(0, timeoutMillis)));
        } finally {
            if (awake.getAndSet(true)) pendingWakeup = true;
        }
        if (result < 0) {
            if (api.errno() == 4 /* EINTR */) return 0;
            throw new VclException("poll", -api.errno());
        }
        if ((pollFds.get(JAVA_SHORT, 6) & (8 | 16 | 32)) != 0) throw new VclException("VCL MQ fd unavailable", -107);
        if ((pollFds.get(JAVA_SHORT, 14) & 1) != 0) drainWakeup();
        if (api.detached()) throw new VclException("VPP detached", -107);
        return VclException.check("VCL epoll wait", api.epollWait(epoll, events, CAPACITY));
    }

    long token(int index) { return events.get(JAVA_LONG_UNALIGNED, index * 12L + 4); }
    int events(int index) { return events.get(JAVA_INT, index * 12L); }

    void wakeup() {
        if (Thread.currentThread() == owner || awake.getAndSet(true)) return;
        long result;
        do { result = api.osWrite(wakeFd, WAKE_WORD); } while (result < 0 && api.errno() == 4);
        if (result < 0 && api.errno() != 11) throw new IllegalStateException("eventfd write failed: errno=" + api.errno());
    }

    private void drainWakeup() throws VclException {
        for (;;) {
            long read = api.osRead(wakeFd, drainWord);
            if (read == 8) { pendingWakeup = false; continue; }
            if (read < 0 && api.errno() == 4) continue;
            if (read < 0 && api.errno() == 11) return;
            throw new VclException("eventfd read", read < 0 ? -api.errno() : -5);
        }
    }

    private void awaitWakeup() throws VclException {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (pendingWakeup) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            int result = api.poll(wakePollFd, 1L, (int) ((remaining + 999_999L) / 1_000_000L));
            if (result == 0) break;
            if (result < 0) {
                if (api.errno() == 4) continue;
                throw new VclException("wakeup poll", -api.errno());
            }
            if ((wakePollFd.get(JAVA_SHORT, 6) & (8 | 16 | 32)) != 0) throw new VclException("wakeup fd unavailable", -107);
            if ((wakePollFd.get(JAVA_SHORT, 6) & 1) != 0) drainWakeup();
        }
        if (pendingWakeup) {
            logger.warn("Missed eventfd write (not seen after 1 second)");
            pendingWakeup = false;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        checkThread();
        awake.set(true);
        // Match EpollIoHandler: observe the pending wakeup (with a time limit) before closing.
        try { if (pendingWakeup) awaitWakeup(); }
        catch (VclException ignored) { /* Continue descriptor cleanup, as epoll does. */ }
        api.osClose(wakeFd);
        try { api.close(epoll); }
        finally { runtime.detach(); closed = true; arena.close(); }
    }
}

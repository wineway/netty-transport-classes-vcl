package dev.vcl.netty;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import static java.lang.foreign.ValueLayout.*;

/** Thread-confined VCL worker. Only wakeup() may be invoked from another thread. */
final class VclPoller implements AutoCloseable {
    static final int CAPACITY = 256;
    final NativeVcl api;
    final VclMetrics metrics;
    final int workerIndex;
    private final VclRuntime runtime;
    private final Thread owner = Thread.currentThread();
    private final Arena arena = Arena.ofConfined();
    private final Arena wakeArena = Arena.ofShared();
    private final MemorySegment wakeWord = wakeArena.allocateFrom(JAVA_LONG, 1L);
    private final MemorySegment drainWord = arena.allocate(JAVA_LONG);
    private final MemorySegment pollFds = arena.allocate(NativeVcl.POLLFD, 2);
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
        } catch (RuntimeException | VclException | Error e) {
            if (wakeFd >= 0) api.osClose(wakeFd);
            if (epoll >= 0) api.close(epoll);
            if (attached) runtime.detach();
            arena.close(); wakeArena.close();
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
        checkThread();
        if (api.detached()) throw new VclException("VPP detached", -107);
        // Drain VCL's userspace-ready lists before waiting on OS notification fds.
        int ready = VclException.check("VCL epoll wait", api.epollWait(epoll, events, CAPACITY));
        if (ready != 0 || timeoutMillis == 0) return ready;
        int result = api.poll(pollFds, Math.min(10, Math.max(0, timeoutMillis)));
        if (result < 0 && api.errno() != 4 /* EINTR */) throw new VclException("poll", -api.errno());
        if ((pollFds.get(JAVA_SHORT, 6) & (8 | 16 | 32)) != 0) throw new VclException("VCL MQ fd unavailable", -107);
        if ((pollFds.get(JAVA_SHORT, 14) & 1) != 0) {
            while (api.osRead(wakeFd, drainWord) == 8) { /* eventfd is nonblocking */ }
        }
        if (api.detached()) throw new VclException("VPP detached", -107);
        return VclException.check("VCL epoll wait", api.epollWait(epoll, events, CAPACITY));
    }

    long token(int index) { return events.get(JAVA_LONG_UNALIGNED, index * 12L + 4); }
    int events(int index) { return events.get(JAVA_INT, index * 12L); }

    synchronized void wakeup() {
        if (wakeFd < 0) return;
        long result;
        do { result = api.osWrite(wakeFd, wakeWord); } while (result < 0 && api.errno() == 4);
        if (result < 0 && api.errno() != 11) throw new IllegalStateException("eventfd write failed: errno=" + api.errno());
    }

    @Override
    public void close() {
        if (closed) return;
        checkThread();
        synchronized (this) {
            api.osClose(wakeFd); wakeFd = -1;
            wakeArena.close();
        }
        try { api.close(epoll); }
        finally { runtime.detach(); closed = true; arena.close(); }
    }
}

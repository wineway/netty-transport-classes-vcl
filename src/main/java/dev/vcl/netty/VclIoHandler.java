package dev.vcl.netty;

import io.netty.channel.*;
import io.netty.util.concurrent.ThreadAwareExecutor;
import io.netty.util.collection.LongObjectHashMap;
import io.netty.util.collection.LongObjectMap;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Netty 4.2 I/O dispatcher backed by a thread-confined VCL worker. */
public final class VclIoHandler implements IoHandler {
    interface Handle extends IoHandle {
        void attach(VclPoller poller) throws Exception;
        int session();
        void failed(Throwable cause);
    }
    record Ops(int value) implements IoOps { }
    record Event(int value) implements IoEvent { }

    private final VclRuntime runtime;
    private final ThreadAwareExecutor executor;
    private final LongObjectMap<Registration> registrations = new LongObjectHashMap<>(4096);
    private volatile VclPoller poller;
    private volatile Thread thread;
    private volatile boolean failed;
    private Throwable failure;
    private long sequence;

    private VclIoHandler(VclRuntime runtime, ThreadAwareExecutor executor) {
        this.runtime = runtime; this.executor = executor;
    }

    public static IoHandlerFactory newFactory(VclRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return new IoHandlerFactory() {
            @Override public IoHandler newHandler(ThreadAwareExecutor executor) { return new VclIoHandler(runtime, executor); }
            @Override public boolean isChangingThreadSupported() { return false; }
        };
    }

    @Override public void initialize() {
        thread = Thread.currentThread();
        try { poller = new VclPoller(runtime); }
        catch (Exception e) { failed = true; failure = e; }
    }

    @Override public IoRegistration register(IoHandle ioHandle) throws Exception {
        if (failed) throw new IllegalStateException("VCL I/O handler unavailable", failure);
        if (!(ioHandle instanceof Handle handle)) throw new IllegalArgumentException("Not a VCL handle");
        handle.attach(poller);
        if (++sequence <= 0) throw new IllegalStateException("VCL registration token exhausted");
        Registration registration = new Registration(sequence, handle);
        registrations.put(sequence, registration);
        handle.registered();
        runtime.counters.registrations.increment();
        return registration;
    }

    @Override public int run(IoHandlerContext context) {
        if (failed) {
            if (context.canBlock()) LockSupport.parkNanos(1_000_000L);
            return 0;
        }
        try {
            long delay = Math.max(0, Math.min(10_000_000L, context.delayNanos(System.nanoTime())));
            int timeout = context.canBlock() ? (int) ((delay + 999_999) / 1_000_000) : 0;
            int ready = poller.poll(timeout, context);
            long processingStarted = context.shouldReportActiveIoTime() ? System.nanoTime() : 0;
            int handled = 0;
            for (int i = 0; i < ready; i++) {
                Registration registration = registrations.get(poller.token(i));
                if (registration == null || !registration.isValid()) continue;
                try { registration.handle.handle(registration, new Event(poller.events(i))); }
                catch (Exception e) { registration.handle.failed(e); }
                handled++;
            }
            runtime.counters.ioEvents.add(handled);
            if (context.shouldReportActiveIoTime()) context.reportActiveIoTime(System.nanoTime() - processingStarted);
            return handled;
        } catch (Exception e) {
            runtime.counters.ioFailures.increment();
            failed = true; failure = e;
            for (Registration registration : List.copyOf(registrations.values())) registration.handle.failed(e);
            return 0;
        }
    }

    @Override public void wakeup() {
        if (Thread.currentThread() == thread) return;
        VclPoller current = poller;
        if (current != null && !failed) current.wakeup();
        LockSupport.unpark(thread);
    }

    @Override public boolean isCompatible(Class<? extends IoHandle> type) { return Handle.class.isAssignableFrom(type); }

    @Override public void prepareToDestroy() {
        for (Registration registration : List.copyOf(registrations.values())) {
            try { registration.handle.close(); }
            catch (Exception e) { registration.handle.failed(e); }
        }
    }

    @Override public void destroy() { if (poller != null) poller.close(); }

    private final class Registration implements IoRegistration {
        final long token;
        final Handle handle;
        final AtomicBoolean cancelled = new AtomicBoolean();
        boolean added;
        Registration(long token, Handle handle) { this.token = token; this.handle = handle; }
        @Override @SuppressWarnings("unchecked") public <T> T attachment() { return (T) poller; }
        @Override public boolean isValid() { return !cancelled.get(); }
        @Override public long submit(IoOps ioOps) {
            poller.checkThread();
            if (!isValid()) return -1;
            if (!(ioOps instanceof Ops ops)) throw new IllegalArgumentException("Not VCL operations");
            try {
                if (ops.value() == 0) {
                    if (added) poller.control(NativeVcl.DEL, handle.session(), 0, token);
                    added = false;
                } else {
                    poller.control(added ? NativeVcl.MOD : NativeVcl.ADD, handle.session(), ops.value(), token);
                    added = true;
                }
                return ops.value();
            } catch (VclException e) { throw new java.io.UncheckedIOException(e); }
        }
        @Override public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            if (executor.isExecutorThread(Thread.currentThread())) cancel0(); else executor.execute(this::cancel0);
            return true;
        }
        private void cancel0() {
            registrations.remove(token);
            try {
                if (added) poller.control(NativeVcl.DEL, handle.session(), 0, token);
            } catch (VclException ignored) { /* Channel may already have closed the session. */ }
            finally { added = false; runtime.counters.registrations.decrement(); handle.unregistered(); }
        }
    }
}

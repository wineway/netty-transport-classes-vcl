package dev.vcl.netty;

import java.util.Objects;
import java.util.concurrent.*;

/** One process-wide VCL application. Close all event loops before closing this runtime. */
public final class VclRuntime implements AutoCloseable {
    private static VclRuntime current;
    final NativeVcl api;
    final VclMetrics counters = new VclMetrics();
    private final ExecutorService manager = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vcl-runtime"); t.setDaemon(true); return t;
    });
    private int workers;
    private boolean closed;

    private VclRuntime(String name) throws Exception {
        api = new NativeVcl();
        try { await(manager.submit(() -> VclException.check("app create", api.appCreate(name)))); }
        catch (Exception | Error e) { manager.shutdown(); throw e; }
    }

    public static synchronized VclRuntime open(String applicationName) throws Exception {
        Objects.requireNonNull(applicationName, "applicationName");
        if (applicationName.isBlank()) throw new IllegalArgumentException("application name is empty");
        if (current != null) throw new IllegalStateException("Only one VclRuntime may be open in a JVM");
        current = new VclRuntime(applicationName);
        return current;
    }

    synchronized void attach() throws VclException {
        if (closed) throw new IllegalStateException("VclRuntime closed");
        if (workers == 127) throw new IllegalStateException("At most 127 I/O workers are supported by the signed VCL session API");
        VclException.check("worker register", api.workerRegister());
        workers++;
    }

    synchronized void detach() {
        api.workerUnregister();
        workers--;
    }

    public synchronized int activeWorkers() { return workers; }
    public synchronized VclMetrics.Snapshot metrics() { return counters.snapshot(workers); }

    @Override
    public void close() throws Exception {
        synchronized (VclRuntime.class) {
            synchronized (this) {
                if (closed) return;
                if (workers != 0) throw new IllegalStateException("Close event loops first; active VCL workers=" + workers);
                closed = true;
            }
            try { await(manager.submit(api::appDestroy)); }
            finally { manager.shutdown(); current = null; }
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        // Finish the native lifecycle operation even if the caller is interrupted.
        boolean interrupted = false;
        try {
            for (;;) {
                try { return future.get(); }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof Exception exception) throw exception;
                    if (e.getCause() instanceof Error error) throw error;
                    throw new IllegalStateException(e.getCause());
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}

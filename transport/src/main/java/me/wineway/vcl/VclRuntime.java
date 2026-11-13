/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.channel.IoHandlerFactory;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Process-wide application. Close the EventLoopGroup before closing its runtime. */
public final class VclRuntime implements AutoCloseable {
    private static final Object PROCESS_LOCK = new Object();
    private static VclRuntime processOwner;
    private static VclMode processMode;
    private final VclMode mode;
    private final int maxWorkers;
    private final VclApplication application;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("vcl-application").factory());
    private final Set<VclIoHandler> handlers = new HashSet<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private boolean closed;

    public static VclRuntime open(Path library, Path sapiSocket, VclMode mode, int maxEventLoops) throws Exception {
        Objects.requireNonNull(library, "library"); Objects.requireNonNull(sapiSocket, "sapiSocket");
        Objects.requireNonNull(mode, "mode");
        if (maxEventLoops < 1 || maxEventLoops > 254) throw new IllegalArgumentException("maxEventLoops must be 1..254");
        if (Runtime.version().feature() != 26 || !System.getProperty("os.name").equals("Linux")
                || !System.getProperty("os.arch").equals("amd64")) {
            throw new UnsupportedOperationException("Java 26 on Linux x86_64 required");
        }
        return open(mode, maxEventLoops, () -> new FfmVclApplication(library, sapiSocket, mode, maxEventLoops));
    }
    static VclRuntime open(VclMode mode, int maxEventLoops, java.util.function.Supplier<VclApplication> application) throws Exception {
        synchronized (PROCESS_LOCK) {
            if (processOwner != null) throw new IllegalStateException("A VCL application already exists");
            if (processMode != null && processMode != mode) throw new IllegalStateException("VCL mode is fixed for this process");
            var runtime = new VclRuntime(application.get(), mode, maxEventLoops);
            processOwner = runtime;
            processMode = mode;
            try {
                runtime.callLifecycle(() -> { runtime.application.start(); return null; });
                return runtime;
            } catch (Exception | Error e) {
                runtime.lifecycle.shutdown();
                processOwner = null;
                throw e;
            }
        }
    }
    private VclRuntime(VclApplication application, VclMode mode, int maxWorkers) {
        this.mode = mode; this.maxWorkers = maxWorkers; this.application = application;
    }
    public VclMode mode() { return mode; }
    public IoHandlerFactory ioHandlerFactory() { return executor -> new VclIoHandler(executor, this); }
    synchronized VclIo attach(VclIoHandler handler) throws Exception {
        if (closed) throw new IllegalStateException("Runtime is closed");
        if (failure.get() != null) throw new IllegalStateException("Runtime failed", failure.get());
        if (handlers.size() >= maxWorkers) throw new IllegalStateException("Configured worker capacity exceeded");
        var io = application.newWorker();
        handlers.add(handler);
        return io;
    }
    synchronized void detached(VclIoHandler handler) { handlers.remove(handler); }
    Throwable failure() { return failure.get(); }
    void fail(Throwable cause) {
        if (failure.compareAndSet(null, cause)) {
            List<VclIoHandler> snapshot;
            synchronized (this) { snapshot = List.copyOf(handlers); }
            for (var handler : snapshot) {
                try { handler.wakeup(); }
                catch (Throwable wakeFailure) { if (wakeFailure != cause) cause.addSuppressed(wakeFailure); }
            }
        }
    }
    private <T> T callLifecycle(Callable<T> action) throws Exception {
        var future = lifecycle.submit(action);
        boolean interrupted = false;
        try {
            for (;;) {
                try { return future.get(); }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException e) { throw VclException.translate(e.getCause()); }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    @Override public void close() throws Exception {
        synchronized (PROCESS_LOCK) {
            synchronized (this) {
                if (closed) return;
                if (!handlers.isEmpty()) throw new IllegalStateException("Terminate all VCL EventLoops before closing the runtime");
                closed = true;
            }
            try {
                callLifecycle(() -> {
                    application.destroy();
                    return null;
                });
            } finally {
                lifecycle.shutdown();
                processOwner = null;
            }
        }
    }
}

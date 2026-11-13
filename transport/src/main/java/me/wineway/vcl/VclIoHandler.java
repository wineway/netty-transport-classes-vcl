/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.channel.*;
import io.netty.util.concurrent.ThreadAwareExecutor;
import java.io.IOException;
import java.util.*;

/** Bounded VCL dispatch; a session and its registration never change platform threads. */
public final class VclIoHandler implements IoHandler {
    interface Handle extends IoHandle {
        int attach(VclIo io) throws Exception;
        void failed(Throwable failure);
    }
    interface SessionRegistration { void replace(int session); }
    record Interest(int flags) implements IoOps { }
    record Ready(int flags) implements IoEvent { }
    @FunctionalInterface interface Factory { VclIo create() throws Exception; }
    private final ThreadAwareExecutor executor;
    private final VclRuntime runtime;
    private final Factory factory;
    private final VclMode mode;
    private final Map<Long, Registration> registrations = new HashMap<>();
    private final long[] tokens = new long[256];
    private final int[] flags = new int[256];
    private volatile VclIo io;
    private Thread owner;
    private long nextToken = 1;
    private boolean stopping;
    private Throwable failure;

    VclIoHandler(ThreadAwareExecutor executor, VclRuntime runtime) {
        this.executor = executor; this.runtime = runtime; this.mode = runtime.mode();
        this.factory = () -> runtime.attach(this);
    }
    VclIoHandler(ThreadAwareExecutor executor, VclMode mode, Factory factory) {
        this.executor = executor; this.runtime = null; this.mode = mode; this.factory = factory;
    }
    @Override public void initialize() {
        if (Thread.currentThread().isVirtual()) throw new IllegalStateException("VCL requires a platform EventLoop thread");
        owner = Thread.currentThread();
        try { io = factory.create(); }
        catch (Exception e) { fail(e); throw new ChannelException(e); }
    }
    private void owner() {
        if (Thread.currentThread() != owner || !executor.isExecutorThread(Thread.currentThread())) {
            throw new IllegalStateException("VCL EventLoop cannot migrate threads");
        }
    }
    @Override public boolean isCompatible(Class<? extends IoHandle> type) { return Handle.class.isAssignableFrom(type); }
    @Override public IoRegistration register(IoHandle handle) throws Exception {
        owner();
        if (stopping || failure != null) throw new IllegalStateException("VCL handler stopped", failure);
        if (!(handle instanceof Handle vcl)) throw new IllegalArgumentException("Not a VCL handle");
        if (nextToken == Long.MAX_VALUE) throw new IllegalStateException("Registration token space exhausted");
        int session = vcl.attach(io);
        var registration = new Registration(nextToken++, session, vcl);
        registrations.put(registration.token, registration);
        try { handle.registered(); }
        catch (Throwable t) {
            registration.cancel();
            handle.close();
            throw VclException.translate(t);
        }
        return registration;
    }
    @Override public int run(IoHandlerContext context) {
        owner();
        long start = System.nanoTime();
        long waitNanos = 0;
        try {
            if (failure != null || stopping || io == null) return 0;
            if (runtime != null && runtime.failure() != null) { fail(runtime.failure()); return 0; }
            if (!io.alive()) throw new IOException("VPP application socket disconnected");
            int n = io.poll(tokens, flags);
            if (n == 0 && mode == VclMode.INTERRUPT && context.canBlock()) {
                // Recheck after draining MQs. Task enqueue leaves a persistent eventfd notification.
                n = io.poll(tokens, flags);
                if (n == 0 && context.canBlock()) {
                    long waitStart = System.nanoTime();
                    boolean alive = io.await(Math.min(context.delayNanos(waitStart), 1_000_000_000L));
                    waitNanos = System.nanoTime() - waitStart;
                    if (!alive) throw new IOException("VPP application socket disconnected");
                    n = io.poll(tokens, flags);
                }
            }
            int handled = 0;
            for (int i = 0; i < n; i++) {
                Registration registration = registrations.get(tokens[i]);
                if (registration == null || !registration.valid) continue; // Stale native event after session reuse.
                try { registration.handle.handle(registration, new Ready(flags[i])); }
                catch (Throwable t) { registration.handle.failed(t); }
                handled++;
            }
            return handled; // At most 256 events; tasks/timers run between bounded batches.
        } catch (Throwable t) { fail(t); return 0; }
        finally {
            if (context.shouldReportActiveIoTime()) context.reportActiveIoTime(Math.max(0, System.nanoTime() - start - waitNanos));
        }
    }
    private void fail(Throwable cause) {
        if (failure != null) return;
        failure = cause;
        if (io != null) io.failed(cause);
        if (runtime != null) runtime.fail(cause);
        for (var registration : List.copyOf(registrations.values())) {
            try { registration.handle.failed(cause); }
            catch (Throwable cleanup) { if (cleanup != cause) cause.addSuppressed(cleanup); }
        }
    }
    @Override public void wakeup() { var current = io; if (current != null) current.wakeup(); }
    @Override public void prepareToDestroy() {
        owner(); stopping = true;
        for (var registration : List.copyOf(registrations.values())) {
            try { registration.handle.close(); }
            catch (Exception e) { registration.handle.failed(e); }
        }
    }
    @Override public void destroy() {
        owner();
        try { prepareToDestroy(); if (io != null) io.close(); }
        catch (Exception e) { throw new ChannelException(e); }
        finally { io = null; if (runtime != null) runtime.detached(this); }
    }
    private final class Registration implements IoRegistration, SessionRegistration {
        long token;
        int session;
        final Handle handle;
        boolean valid = true, added;
        Registration(long token, int session, Handle handle) { this.token = token; this.session = session; this.handle = handle; }
        @SuppressWarnings("unchecked") @Override public <T> T attachment() { return (T) this; }
        @Override public void replace(int replacement) {
            owner();
            if (!valid || failure != null || stopping) throw new IllegalStateException("Registration is invalid", failure);
            if (nextToken == Long.MAX_VALUE) throw new IllegalStateException("Registration token space exhausted");
            try { if (added) io.remove(session); }
            catch (Exception e) { throw new ChannelException(e); }
            registrations.remove(token);
            token = nextToken++; session = replacement; added = false;
            registrations.put(token, this);
        }
        @Override public boolean isValid() { return valid; }
        @Override public long submit(IoOps ops) {
            owner();
            if (!valid || failure != null) throw new IllegalStateException("Registration is invalid", failure);
            if (!(ops instanceof Interest interest)) throw new IllegalArgumentException("Expected VCL interests");
            try { io.interest(session, token, interest.flags(), !added); added = true; }
            catch (Exception e) { throw new ChannelException(e); }
            return token;
        }
        @Override public boolean cancel() {
            owner(); if (!valid) return false;
            valid = false;
            registrations.remove(token);
            try { if (added && failure == null) io.remove(session); }
            catch (Exception e) { throw new ChannelException(e); }
            finally { handle.unregistered(); }
            return true;
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.*;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.channels.UnsupportedAddressTypeException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.AlreadyConnectedException;
import java.util.concurrent.TimeUnit;

abstract class AbstractVclChannel extends AbstractChannel {
    private static final ChannelMetadata TCP = new ChannelMetadata(false, 16);
    private static final ChannelMetadata UDP = new ChannelMetadata(true, 16);
    final boolean datagram;
    VclIo io;
    int session = -1;
    private Thread owner;
    private volatile EventLoop owningLoop;
    private boolean inputShutdownReadComplete;
    IoRegistration registration;
    volatile boolean open = true, active;
    volatile InetSocketAddress local, remote;
    boolean readPending, writeBlocked, inputShutdown, outputShutdown, failed;
    private ChannelPromise connecting;
    private ScheduledFuture<?> connectTimeout;
    private boolean flushScheduled;
    private PortReservations.Lease boundPort;

    AbstractVclChannel(Channel parent, boolean datagram) { super(parent); this.datagram = datagram; }
    AbstractVclChannel(Channel parent, VclIo io, int session, InetSocketAddress local, InetSocketAddress remote) {
        this(parent, false); this.io = io; this.session = session; this.local = local; this.remote = remote;
        this.owner = Thread.currentThread(); this.owningLoop = parent.eventLoop(); active = true;
    }
    @Override public ChannelMetadata metadata() { return datagram ? UDP : TCP; }
    @Override public boolean isOpen() { return open; }
    @Override public boolean isActive() { return open && active; }
    @Override public InetSocketAddress localAddress() { return (InetSocketAddress) super.localAddress(); }
    @Override public InetSocketAddress remoteAddress() { return (InetSocketAddress) super.remoteAddress(); }
    @Override protected SocketAddress localAddress0() { return local; }
    @Override protected SocketAddress remoteAddress0() { return remote; }
    @Override protected boolean isCompatible(EventLoop loop) {
        return (owningLoop == null || owningLoop == loop)
                && loop instanceof IoEventLoop ioLoop && ioLoop.isCompatible(VclUnsafe.class);
    }
    @Override protected AbstractUnsafe newUnsafe() { return new VclUnsafe(); }
    @Override protected void doRegister(ChannelPromise promise) {
        if (owner != null && owner != Thread.currentThread()) {
            promise.setFailure(new IllegalStateException("VCL sessions cannot move between EventLoops")); return;
        }
        ((IoEventLoop) eventLoop()).register((VclUnsafe) unsafe()).addListener(future -> {
            if (!future.isSuccess()) { promise.setFailure(future.cause()); return; }
            registration = (IoRegistration) future.getNow();
            try { if (active) interests(); promise.setSuccess(); }
            catch (Throwable failure) { promise.setFailure(failure); }
        });
    }
    @Override protected void doDeregister() {
        if (registration != null) { registration.cancel(); registration = null; }
    }
    @Override protected void doClose() throws Exception {
        if (!open) return;
        open = active = false;
        inputShutdown = outputShutdown = true;
        cancelConnect(new ClosedChannelException());
        Throwable failure = null;
        try { doDeregister(); } catch (Throwable t) { failure = t; }
        try { if (session != -1 && io != null) io.closeSession(session); }
        catch (Throwable t) { if (failure == null) failure = t; else failure.addSuppressed(t); }
        finally { session = -1; if (boundPort != null) { boundPort.close(); boundPort = null; } }
        if (failure != null) throw VclException.translate(failure);
    }
    @Override protected void doDisconnect() throws Exception { doClose(); }
    @Override protected void doBeginRead() {
        if (inputShutdown) {
            if (!inputShutdownReadComplete && isActive()) {
                inputShutdownReadComplete = true;
                pipeline().fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
            return;
        }
        readPending = true;
        interests();
    }
    void autoReadCleared() {
        if (!isRegistered()) return;
        Runnable update = () -> { if (!config().isAutoRead()) { readPending = false; interests(); } };
        if (eventLoop().inEventLoop()) update.run(); else eventLoop().execute(update);
    }
    final void interests() {
        if (registration == null || !registration.isValid() || !open || failed) return;
        if (!active && connecting == null) return;
        int flags = 0;
        if (!inputShutdown && active && (readPending || config().isAutoRead())) flags |= VclIo.READ | VclIo.RDHUP;
        if (connecting != null || writeBlocked) flags |= VclIo.WRITE;
        registration.submit(new VclIoHandler.Interest(flags));
    }
    final void bound(InetSocketAddress address) throws Exception {
        if (local != null) throw new java.nio.channels.AlreadyBoundException();
        for (int attempt = 0; attempt < 128; attempt++) {
            var reservation = PortReservations.reserve(datagram, address.getPort());
            try {
                io.bind(session, new InetSocketAddress(address.getAddress(), reservation.port));
                local = io.localAddress(session); invalidateLocalAddress();
                boundPort = reservation;
                return;
            } catch (Throwable failure) {
                reservation.close();
                if (address.getPort() == 0 && failure instanceof VclException vcl
                        && (vcl.error() == -98 || vcl.error() == -17)) continue;
                throw VclException.translate(failure);
            }
        }
        throw new BindException("Unable to allocate a VCL local port");
    }
    final void replaceSession(int replacement) {
        if (registration != null) {
            registration.<VclIoHandler.SessionRegistration>attachment().replace(replacement);
        }
        session = replacement;
    }
    static InetSocketAddress address(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inet)) throw new UnsupportedAddressTypeException();
        if (inet.isUnresolved()) throw new UnresolvedAddressException();
        if (inet.getAddress().isMulticastAddress()) throw new UnsupportedOperationException("VCL transport supports unicast addresses");
        return inet;
    }
    abstract void readReady() throws Exception;
    boolean connectTo(InetSocketAddress peer) throws Exception { return io.connect(session, peer); }
    void connected() throws Exception {
        local = io.localAddress(session); invalidateLocalAddress(); invalidateRemoteAddress();
        active = true;
    }
    private void finishConnect() throws Exception {
        if (connecting == null || !io.finishConnect(session)) return;
        ChannelPromise promise = connecting;
        connecting = null;
        if (connectTimeout != null) { connectTimeout.cancel(false); connectTimeout = null; }
        boolean wasActive = isActive();
        connected();
        interests();
        boolean becameActive = isActive();
        boolean completed = promise.trySuccess();
        if (!wasActive && becameActive) pipeline().fireChannelActive();
        if (!completed) unsafe().close(unsafe().voidPromise());
    }
    private void cancelConnect(Throwable failure) {
        if (connectTimeout != null) { connectTimeout.cancel(false); connectTimeout = null; }
        if (connecting != null) { var promise = connecting; connecting = null; promise.tryFailure(failure); }
    }
    final ByteBuf direct(ByteBuf source) {
        if (source.isDirect() && source.nioBufferCount() == 1) return source;
        ByteBuf copy = alloc().directBuffer(source.readableBytes());
        try { copy.writeBytes(source, source.readerIndex(), source.readableBytes()); }
        catch (Throwable t) { copy.release(); throw t; }
        source.release();
        return copy;
    }
    final void flushLater() {
        if (flushScheduled) return;
        flushScheduled = true;
        eventLoop().execute(() -> { flushScheduled = false; if (open) ((VclUnsafe) unsafe()).flushReady(); });
    }
    final class VclUnsafe extends AbstractUnsafe implements VclIoHandler.Handle {
        @Override public int attach(VclIo worker) throws Exception {
            if (!open) throw new ClosedChannelException();
            if (io != null && (io != worker || owner != Thread.currentThread())) throw new IllegalStateException("Foreign VCL worker");
            io = worker; owner = Thread.currentThread(); owningLoop = eventLoop();
            if (session == -1) session = io.create(datagram);
            return session;
        }
        @Override public void handle(IoRegistration ignored, IoEvent event) {
            if (!open) return;
            int flags = ((VclIoHandler.Ready) event).flags();
            try {
                if (connecting != null) {
                    finishConnect();
                    // VCL's level-trigger list can report HUP while a bound UDP
                    // session transitions through UPDATED. GET_NWRITE distinguishes
                    // an in-progress connect (zero) from a failed native state.
                    if (connecting != null) { interests(); return; }
                }
                if (open && active && (flags & VclIo.WRITE) != 0) {
                    writeBlocked = false; flushReady();
                }
                if (open && active && !inputShutdown && (readPending || config().isAutoRead())
                        && (flags & (VclIo.READ | VclIo.RDHUP | VclIo.HUP | VclIo.ERROR)) != 0) readReady();
                if (open && (flags & VclIo.ERROR) != 0) throw new java.io.IOException("VCL session error");
                interests();
            } catch (Throwable failure) { failed(failure); }
        }
        @Override public void failed(Throwable failure) {
            failed = true;
            cancelConnect(failure);
            pipeline().fireExceptionCaught(failure);
            close(voidPromise());
        }
        @Override public void close() { close(voidPromise()); }
        void flushReady() { super.flush0(); }
        @Override protected void flush0() { if (!writeBlocked) super.flush0(); }
        @Override public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
            if (promise.isDone() || !ensureOpen(promise)) return;
            try {
                if (AbstractVclChannel.this instanceof VclServerSocketChannel) throw new UnsupportedOperationException("Listener cannot connect");
                if (connecting != null) throw new ConnectionPendingException();
                if (remote != null) throw new AlreadyConnectedException();
                InetSocketAddress peer = address(remoteAddress);
                if (localAddress != null) bound(address(localAddress));
                boolean wasActive = isActive();
                remote = peer;
                if (connectTo(peer)) {
                    connected(); interests();
                    boolean becameActive = isActive();
                    boolean completed = promise.trySuccess();
                    if (!wasActive && becameActive) pipeline().fireChannelActive();
                    if (!completed) close(voidPromise());
                } else {
                    connecting = promise;
                    promise.addListener(future -> {
                        if (future.isCancelled()) {
                            cancelConnect(new java.util.concurrent.CancellationException());
                            close(voidPromise());
                        }
                    });
                    interests();
                    int timeout = config().getConnectTimeoutMillis();
                    if (timeout > 0) connectTimeout = eventLoop().schedule(() -> {
                        if (connecting != null) {
                            cancelConnect(new ConnectTimeoutException("Connection timed out: " + peer));
                            close(voidPromise());
                        }
                    }, timeout, TimeUnit.MILLISECONDS);
                }
            } catch (Throwable failure) { promise.tryFailure(annotateConnectException(failure, remoteAddress)); close(voidPromise()); }
        }
    }
}

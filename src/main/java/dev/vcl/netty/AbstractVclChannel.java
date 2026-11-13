package dev.vcl.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import java.lang.foreign.MemorySegment;
import io.netty.buffer.Unpooled;
import java.net.*;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.util.concurrent.TimeUnit;
import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

abstract class AbstractVclChannel extends AbstractChannel {
    static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    volatile boolean open = true, active;
    volatile boolean inputShutdown, outputShutdown;
    boolean readPending, writeBlocked, receivedRdHup;
    private int interests = -1;
    private final Runnable flushTask = () -> ((VclUnsafe) unsafe()).flushReady();
    int session = -1;
    VclPoller poller;
    IoRegistration registration;
    EventLoop ownerLoop;
    InetSocketAddress local, remote;
    ChannelPromise connectPromise;
    InetSocketAddress requestedRemoteAddress;
    io.netty.util.concurrent.ScheduledFuture<?> connectTimeout;

    AbstractVclChannel(Channel parent) { super(parent); }

    @Override public final ChannelMetadata metadata() { return METADATA; }
    @Override public final boolean isOpen() { return open; }
    @Override public final boolean isActive() { return open && active; }
    @Override public InetSocketAddress localAddress() { return (InetSocketAddress) super.localAddress(); }
    @Override public InetSocketAddress remoteAddress() { return (InetSocketAddress) super.remoteAddress(); }
    @Override protected final SocketAddress localAddress0() { return local; }
    @Override protected final SocketAddress remoteAddress0() { return remote; }
    @Override protected final AbstractUnsafe newUnsafe() { return new VclUnsafe(); }
    @Override protected final boolean isCompatible(EventLoop loop) {
        return loop instanceof IoEventLoop io && io.isIoType(VclIoHandler.class) && (ownerLoop == null || ownerLoop == loop);
    }

    @Override protected final void doRegister(ChannelPromise promise) {
        if (ownerLoop != null && ownerLoop != eventLoop()) {
            promise.tryFailure(new IllegalStateException("VCL Channel cannot migrate between EventLoops")); return;
        }
        ownerLoop = eventLoop();
        ((IoEventLoop) eventLoop()).register((VclUnsafe) unsafe()).addListener(future -> {
            if (future.isSuccess()) {
                registration = (IoRegistration) future.getNow();
                interests = -1;
                refreshInterests();
                promise.trySuccess();
            } else promise.tryFailure(future.cause());
        });
    }

    @Override protected void doBind(SocketAddress address) throws Exception {
        VclException.check("bind", poller.api.bind(session, ipv4(address)));
        local = poller.api.address(session, false);
    }
    static InetSocketAddress ipv4(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.isUnresolved()) throw new java.nio.channels.UnresolvedAddressException();
        if (!(address instanceof InetSocketAddress inet) || !(inet.getAddress() instanceof Inet4Address)) {
            throw new IllegalArgumentException("Requires resolved IPv4 InetSocketAddress: " + address);
        }
        return inet;
    }
    @Override protected final void doDisconnect() throws Exception { doClose(); }
    @Override protected void doClose() {
        open = false; active = false; inputShutdown = outputShutdown = true;
        try { if (registration != null) registration.cancel(); }
        finally {
            if (session >= 0 && poller != null) { poller.checkThread(); poller.api.close(session); session = -1; }
            cancelConnectTimeout();
            ChannelPromise pending = connectPromise; connectPromise = null;
            if (pending != null) pending.tryFailure(new ClosedChannelException());
        }
    }
    @Override protected final void doDeregister() {
        if (registration != null) registration.cancel();
        registration = null;
        interests = -1;
    }
    @Override protected final void doBeginRead() { readPending = true; refreshInterests(); }

    final void autoReadCleared() {
        if (!isRegistered()) { readPending = false; return; }
        Runnable clear = () -> { readPending = false; refreshInterests(); };
        if (eventLoop().inEventLoop()) clear.run();
        else eventLoop().execute(() -> {
            if (!readPending && !config().isAutoRead()) clear.run();
        });
    }
    final void refreshInterests() {
        if (registration == null || !registration.isValid() || !open) return;
        int next = 0;
        if (connectPromise != null && !active) next = NativeVcl.OUT;
        else if (active) {
            if (!inputShutdown && (readPending || config().isAutoRead() || receivedRdHup)) next |= NativeVcl.IN;
            if (this instanceof VclSocketChannel && !inputShutdown && !receivedRdHup) next |= NativeVcl.RDHUP;
            if (writeBlocked) next |= NativeVcl.OUT;
        }
        if (next != interests) {
            registration.submit(new VclIoHandler.Ops(next));
            interests = next;
        }
    }
    void ready(int events) throws Exception {
        // Keep epoll's ordering: finish connect / flush, read, then remote half-close.
        if ((events & (NativeVcl.OUT | NativeVcl.ERR | NativeVcl.HUP)) != 0) {
            if (connectPromise != null) finishConnect();
            else if (!outputShutdown) ((VclUnsafe) unsafe()).flushReady();
        }
        if (!isActive()) return;
        if ((events & (NativeVcl.IN | NativeVcl.ERR | NativeVcl.HUP)) != 0 && !inputShutdown) readReady();
        if (open && (events & NativeVcl.RDHUP) != 0 && !inputShutdown) {
            receivedRdHup = true;
            readReady();
        }
        refreshInterests();
    }

    abstract void readReady() throws Exception;

    @Override protected Object filterOutboundMessage(Object message) {
        if (!(message instanceof ByteBuf buffer)) throw new UnsupportedOperationException("VCL supports ByteBuf writes only");
        if (buffer.isDirect() && buffer.nioBufferCount() == 1) return message;
        if (!buffer.isReadable()) { buffer.release(); return Unpooled.EMPTY_BUFFER; }
        ByteBuf direct = alloc().directBuffer(buffer.readableBytes());
        try { direct.writeBytes(buffer, buffer.readerIndex(), buffer.readableBytes()); }
        catch (Throwable e) { direct.release(); throw e; }
        buffer.release();
        return direct;
    }

    @Override protected void doWrite(ChannelOutboundBuffer outbound) throws Exception {
        writeBlocked = false;
        int writeSpinCount = config().getWriteSpinCount();
        do {
            if (outbound.current() == null) {
                refreshInterests();
                return;
            }
            writeSpinCount -= doWriteSingle(outbound);
        } while (writeSpinCount > 0);
        writeBlocked = writeSpinCount < 0;
        refreshInterests();
        if (writeSpinCount == 0) eventLoop().execute(flushTask);
    }

    private int doWriteSingle(ChannelOutboundBuffer outbound) throws VclException {
        ByteBuf buffer = (ByteBuf) outbound.current();
        if (!buffer.isReadable()) { outbound.remove(); return 0; }
        MemorySegment memory = MemorySegment.ofBuffer(buffer.internalNioBuffer(buffer.readerIndex(), buffer.readableBytes()));
        int written = poller.api.write(session, memory, memory.byteSize());
        if (written == NativeVcl.AGAIN || written == 0) {
            poller.metrics.writeWouldBlock.increment();
            return WRITE_STATUS_SNDBUF_FULL;
        }
        VclException.check("write", written);
        poller.metrics.writtenBytes.add(written);
        outbound.removeBytes(written);
        return 1;
    }

    private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) throws VclException {
        if (promise == null) return;
        local = poller.api.address(session, false);
        InetSocketAddress actual = poller.api.address(session, true);
        if (requestedRemoteAddress == null) remote = actual;
        else {
            try {
                remote = new InetSocketAddress(InetAddress.getByAddress(requestedRemoteAddress.getHostString(),
                        actual.getAddress().getAddress()), actual.getPort());
            } catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
        }
        active = true;
        boolean nowActive = isActive();
        boolean delivered = promise.trySuccess();
        if (!wasActive && nowActive) pipeline().fireChannelActive();
        if (!delivered) unsafe().close(voidPromise());
        else refreshInterests();
    }

    private void finishConnect() {
        ChannelPromise promise = connectPromise;
        try {
            VclException.check("connect completion", poller.api.error(session));
            fulfillConnectPromise(promise, isActive());
        } catch (Throwable cause) {
            if (promise != null) promise.tryFailure(((VclUnsafe) unsafe()).connectFailure(cause, requestedRemoteAddress));
            ((VclUnsafe) unsafe()).closeIfNotOpen();
        } finally {
            cancelConnectTimeout();
            connectPromise = null;
        }
    }
    private void cancelConnectTimeout() { if (connectTimeout != null) { connectTimeout.cancel(false); connectTimeout = null; } }

    final class VclUnsafe extends AbstractUnsafe implements VclIoHandler.Handle {
        @Override public void attach(VclPoller worker) throws Exception {
            if (poller != null && poller != worker) throw new IllegalStateException("VCL worker migration unsupported");
            poller = worker;
            worker.checkThread();
            if (session < 0) session = VclException.check("session create", worker.api.create());
        }
        @Override public int session() { return session; }
        @Override public void handle(IoRegistration registration, IoEvent event) {
            if (!open) return;
            try { ready(((VclIoHandler.Event) event).value()); }
            catch (Exception e) { failed(e); }
        }
        @Override public void failed(Throwable cause) {
            ChannelPromise pending = connectPromise; connectPromise = null;
            if (pending != null) pending.tryFailure(cause);
            pipeline().fireExceptionCaught(cause);
            close(voidPromise());
        }
        @Override public void close() { close(voidPromise()); }
        @Override protected void flush0() { if (!writeBlocked) super.flush0(); }
        void flushReady() { writeBlocked = false; super.flush0(); }
        void closeIfNotOpen() { closeIfClosed(); }

        private Throwable connectFailure(Throwable cause, SocketAddress address) {
            if (cause instanceof VclException error) {
                java.io.IOException translated;
                if (error.errno() == 111) translated = new ConnectException("Connection refused");
                else if (error.errno() == 110) translated = new ConnectTimeoutException("Connection timed out");
                else if (error.errno() == 101 || error.errno() == 113) translated = new NoRouteToHostException(error.getMessage());
                else return cause;
                translated.initCause(cause);
                cause = translated;
            }
            return annotateConnectException(cause, address);
        }

        @Override public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
            if (promise.isDone() || !ensureOpen(promise)) return;
            if (AbstractVclChannel.this instanceof VclServerSocketChannel) {
                promise.tryFailure(new UnsupportedOperationException("Server channel cannot connect")); return;
            }
            try {
                if (connectPromise != null) throw new ConnectionPendingException();
                InetSocketAddress target = ipv4(remoteAddress);
                if (localAddress != null) ipv4(localAddress);
                if (active) throw new AlreadyConnectedException();
                boolean wasActive = isActive();
                if (localAddress != null) doBind(localAddress);
                requestedRemoteAddress = target;
                int result;
                boolean success = false;
                try {
                    result = poller.api.connect(session, target);
                    if (result != NativeVcl.IN_PROGRESS) VclException.check("connect", result);
                    success = true;
                } finally { if (!success) doClose(); }
                local = poller.api.address(session, false);
                if (result == 0) { fulfillConnectPromise(promise, wasActive); return; }
                connectPromise = promise;
                refreshInterests();
                int millis = config().getConnectTimeoutMillis();
                if (millis > 0) connectTimeout = eventLoop().schedule(() -> {
                    ChannelPromise pending = connectPromise;
                    if (pending != null && !pending.isDone() && pending.tryFailure(new ConnectTimeoutException(
                            "connection timed out after " + millis + " ms: " + target))) close(voidPromise());
                }, millis, TimeUnit.MILLISECONDS);
                promise.addListener(future -> {
                    if (future.isCancelled()) { cancelConnectTimeout(); connectPromise = null; close(voidPromise()); }
                });
            } catch (Throwable cause) {
                closeIfClosed();
                promise.tryFailure(connectFailure(cause, remoteAddress));
            }
        }
    }
}

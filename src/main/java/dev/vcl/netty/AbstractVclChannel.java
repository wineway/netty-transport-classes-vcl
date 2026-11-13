package dev.vcl.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import java.lang.foreign.MemorySegment;
import java.net.*;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.util.concurrent.TimeUnit;

abstract class AbstractVclChannel extends AbstractChannel {
    static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    volatile boolean open = true, active;
    volatile boolean inputShutdown, outputShutdown;
    boolean readPending, writeBlocked, writeScheduled;
    int session = -1;
    VclPoller poller;
    IoRegistration registration;
    EventLoop ownerLoop;
    InetSocketAddress local, remote;
    ChannelPromise connectPromise;
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
        if (!(address instanceof InetSocketAddress inet) || inet.isUnresolved() || !(inet.getAddress() instanceof Inet4Address)) {
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
    @Override protected final void doDeregister() { if (registration != null) registration.cancel(); }
    @Override protected final void doBeginRead() { readPending = true; refreshInterests(); }

    final void autoReadCleared() {
        if (!isRegistered()) { readPending = false; return; }
        Runnable clear = () -> { readPending = false; refreshInterests(); };
        if (eventLoop().inEventLoop()) clear.run(); else eventLoop().execute(clear);
    }
    final void refreshInterests() {
        if (registration == null || !registration.isValid() || !open) return;
        int interests = 0;
        if (connectPromise != null) interests = NativeVcl.OUT;
        else if (active) {
            if (!inputShutdown && (readPending || config().isAutoRead())) {
                interests |= NativeVcl.IN;
                if (this instanceof VclSocketChannel) interests |= NativeVcl.RDHUP;
            }
            if (writeBlocked) interests |= NativeVcl.OUT;
        }
        registration.submit(new VclIoHandler.Ops(interests));
    }
    void ready(int events) throws Exception {
        if (connectPromise != null && (events & (NativeVcl.OUT | NativeVcl.ERR | NativeVcl.HUP)) != 0) {
            int error = poller.api.error(session);
            if (error != 0) throw new VclException("connect completion", error);
            finishConnect();
        }
        if (!isActive()) return;
        if ((events & NativeVcl.OUT) != 0 && writeBlocked) ((VclUnsafe) unsafe()).flushReady();
        if ((events & (NativeVcl.IN | NativeVcl.HUP | NativeVcl.RDHUP)) != 0 && !inputShutdown
                && (readPending || config().isAutoRead())) readReady();
        if (open && (events & NativeVcl.ERR) != 0) {
            int error = poller.api.error(session);
            if (error != 0) throw new VclException("session", error);
        }
        refreshInterests();
    }

    abstract void readReady() throws Exception;

    @Override protected Object filterOutboundMessage(Object message) {
        if (!(message instanceof ByteBuf buffer)) throw new UnsupportedOperationException("VCL supports ByteBuf writes only");
        if (buffer.isDirect() && buffer.nioBufferCount() == 1) return message;
        ByteBuf direct = alloc().directBuffer(buffer.readableBytes());
        try { direct.writeBytes(buffer, buffer.readerIndex(), buffer.readableBytes()); }
        catch (Throwable e) { direct.release(); throw e; }
        buffer.release();
        return direct;
    }

    @Override protected void doWrite(ChannelOutboundBuffer outbound) throws Exception {
        writeBlocked = false;
        int budget = config().getWriteSpinCount();
        try {
            while (budget-- > 0) {
                ByteBuf buffer = (ByteBuf) outbound.current();
                if (buffer == null) return;
                if (!buffer.isReadable()) { outbound.remove(); continue; }
                MemorySegment memory = MemorySegment.ofBuffer(buffer.internalNioBuffer(buffer.readerIndex(), buffer.readableBytes()));
                int written = poller.api.write(session, memory, memory.byteSize());
                if (written == NativeVcl.AGAIN || written == 0) { poller.metrics.writeWouldBlock.increment(); writeBlocked = true; return; }
                VclException.check("write", written);
                poller.metrics.writtenBytes.add(written);
                outbound.removeBytes(written);
            }
            if (outbound.current() != null && !writeScheduled) {
                writeScheduled = true;
                eventLoop().execute(() -> {
                    writeScheduled = false;
                    if (isActive() && !outputShutdown) ((VclUnsafe) unsafe()).flushReady();
                });
            }
        } finally { refreshInterests(); }
    }

    final void finishConnect() throws VclException {
        local = poller.api.address(session, false);
        remote = poller.api.address(session, true);
        active = true;
        ChannelPromise promise = connectPromise; connectPromise = null;
        cancelConnectTimeout();
        boolean delivered = promise != null && promise.trySuccess();
        pipeline().fireChannelActive();
        if (!delivered) unsafe().close(voidPromise());
        else refreshInterests();
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

        @Override public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
            if (promise.isDone() || !ensureOpen(promise)) return;
            if (AbstractVclChannel.this instanceof VclServerSocketChannel) {
                promise.tryFailure(new UnsupportedOperationException("Server channel cannot connect")); return;
            }
            if (active) { promise.tryFailure(new AlreadyConnectedException()); return; }
            if (connectPromise != null) { promise.tryFailure(new ConnectionPendingException()); return; }
            try {
                InetSocketAddress target = ipv4(remoteAddress);
                if (localAddress != null) doBind(localAddress);
                connectPromise = promise;
                int result = poller.api.connect(session, target);
                if (result == 0) { finishConnect(); return; }
                if (result != NativeVcl.IN_PROGRESS) VclException.check("connect", result);
                refreshInterests();
                int millis = config().getConnectTimeoutMillis();
                if (millis > 0) connectTimeout = eventLoop().schedule(() -> {
                    if (connectPromise != null) failed(new ConnectTimeoutException("connect timed out: " + target));
                }, millis, TimeUnit.MILLISECONDS);
                promise.addListener(future -> { if (future.isCancelled()) close(voidPromise()); });
            } catch (Exception e) { promise.tryFailure(e); close(voidPromise()); }
        }
    }
}

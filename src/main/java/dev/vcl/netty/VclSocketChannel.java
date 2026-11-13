package dev.vcl.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import java.lang.foreign.MemorySegment;
import java.nio.channels.ClosedChannelException;

/** TCP socket over VPP/VCL. Native sessions are created only on the owning EventLoop. */
public final class VclSocketChannel extends AbstractVclChannel implements SocketChannel {
    private final VclSocketChannelConfig config = new VclSocketChannelConfig(this);

    public VclSocketChannel() { super(null); }
    VclSocketChannel(VclServerSocketChannel parent, int session, VclPoller poller) throws VclException {
        super(parent);
        this.session = session; this.poller = poller;
        ownerLoop = parent.eventLoop();
        local = poller.api.address(session, false); remote = poller.api.address(session, true);
        active = true;
    }
    @Override public ServerSocketChannel parent() { return (ServerSocketChannel) super.parent(); }
    @Override public SocketChannelConfig config() { return config; }

    @Override void readReady() throws Exception {
        RecvByteBufAllocator.Handle allocator = unsafe().recvBufAllocHandle();
        allocator.reset(config);
        boolean eof = false;
        try {
            for (int budget = 16; budget > 0 && open && !inputShutdown; budget--) {
                if (!readPending && !config.isAutoRead()) break;
                ByteBuf buffer = alloc().directBuffer(Math.max(1, allocator.guess()));
                try {
                    allocator.attemptedBytesRead(buffer.writableBytes());
                    MemorySegment memory = MemorySegment.ofBuffer(buffer.internalNioBuffer(buffer.writerIndex(), buffer.writableBytes()));
                    int read = poller.api.read(session, memory, memory.byteSize());
                    allocator.lastBytesRead(read == NativeVcl.AGAIN ? 0 : read);
                    if (read == NativeVcl.AGAIN) { poller.metrics.readWouldBlock.increment(); break; }
                    if (read == 0) { eof = true; break; }
                    VclException.check("read", read);
                    poller.metrics.readBytes.add(read);
                    buffer.writerIndex(buffer.writerIndex() + read);
                    allocator.incMessagesRead(1);
                    readPending = false;
                    ByteBuf message = buffer; buffer = null;
                    pipeline().fireChannelRead(message);
                } finally { if (buffer != null) buffer.release(); }
                if (!allocator.continueReading()) break;
            }
        } finally {
            allocator.readComplete();
            pipeline().fireChannelReadComplete();
            if (eof && open) {
                inputShutdown = true;
                if (config.isAllowHalfClosure()) {
                    pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                    closeIfBothShutdown();
                }
                else unsafe().close(voidPromise());
            }
        }
    }

    @Override protected void doShutdownOutput() throws Exception {
        VclException.check("shutdown output", poller.api.shutdown(session, 1));
        outputShutdown = true; writeBlocked = false; refreshInterests();
        closeIfBothShutdown();
    }
    @Override public boolean isInputShutdown() { return !open || inputShutdown; }
    @Override public boolean isOutputShutdown() { return !open || outputShutdown; }
    @Override public boolean isShutdown() { return isInputShutdown() && isOutputShutdown(); }
    @Override public ChannelFuture shutdownInput() { return shutdownInput(newPromise()); }
    @Override public ChannelFuture shutdownInput(ChannelPromise promise) {
        onLoop(promise, () -> {
            if (!inputShutdown) {
                VclException.check("shutdown input", poller.api.shutdown(session, 0));
                inputShutdown = true; readPending = false; refreshInterests();
                closeIfBothShutdown();
            }
            promise.trySuccess();
        });
        return promise;
    }
    @Override public ChannelFuture shutdownOutput() { return shutdownOutput(newPromise()); }
    @Override public ChannelFuture shutdownOutput(ChannelPromise promise) {
        onLoop(promise, () -> {
            if (outputShutdown) promise.trySuccess(); else ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        });
        return promise;
    }
    @Override public ChannelFuture shutdown() { return shutdown(newPromise()); }
    @Override public ChannelFuture shutdown(ChannelPromise promise) {
        shutdownOutput().addListener(output -> {
            if (!output.isSuccess()) promise.tryFailure(output.cause());
            else shutdownInput().addListener(input -> {
                if (input.isSuccess()) promise.trySuccess(); else promise.tryFailure(input.cause());
            });
        });
        return promise;
    }
    private interface Action { void run() throws Exception; }
    private void closeIfBothShutdown() {
        // VCL's CLOSE_WAIT shutdown only marks flags. close() sends the disconnected reply/FIN.
        // Defer until AbstractUnsafe has completed its output-buffer shutdown bookkeeping.
        if (inputShutdown && outputShutdown && open) eventLoop().execute(() -> unsafe().close(voidPromise()));
    }
    private void onLoop(ChannelPromise promise, Action action) {
        if (!isRegistered()) { promise.tryFailure(new IllegalStateException("Channel is not registered")); return; }
        Runnable task = () -> {
            if (!promise.setUncancellable()) return;
            if (!open) { promise.tryFailure(new ClosedChannelException()); return; }
            try { action.run(); } catch (Exception e) { promise.tryFailure(e); }
        };
        if (eventLoop().inEventLoop()) task.run(); else {
            try { eventLoop().execute(task); } catch (RuntimeException e) { promise.tryFailure(e); }
        }
    }
}

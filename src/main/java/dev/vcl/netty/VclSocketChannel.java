package dev.vcl.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import java.lang.foreign.MemorySegment;
import java.nio.channels.ClosedChannelException;
import java.io.IOException;

/** TCP socket over VPP/VCL. Native sessions are created only on the owning EventLoop. */
public final class VclSocketChannel extends AbstractVclChannel implements SocketChannel {
    private final VclSocketChannelConfig config = new VclSocketChannelConfig(this);
    private VclRecvByteAllocatorHandle recvHandle;
    private boolean inputClosedSeenErrorOnRead;

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
        if (!open || inputShutdown) return;
        if (recvHandle == null) recvHandle = new VclRecvByteAllocatorHandle(
                (RecvByteBufAllocator.ExtendedHandle) unsafe().recvBufAllocHandle());
        VclRecvByteAllocatorHandle allocator = recvHandle;
        if (receivedRdHup) allocator.receivedRdHup();
        allocator.reset(config);
        boolean eof = false;
        ByteBuf buffer = null;
        try {
            do {
                buffer = allocator.allocate(alloc());
                allocator.attemptedBytesRead(buffer.writableBytes());
                MemorySegment memory = MemorySegment.ofBuffer(buffer.internalNioBuffer(buffer.writerIndex(), buffer.writableBytes()));
                int read = poller.api.read(session, memory, memory.byteSize());
                allocator.lastBytesRead(read == NativeVcl.AGAIN ? 0 : read == 0 ? -1 : read);
                if (read == NativeVcl.AGAIN) { poller.metrics.readWouldBlock.increment(); break; }
                if (read == 0) { eof = true; readPending = false; break; }
                VclException.check("read", read);
                poller.metrics.readBytes.add(read);
                buffer.writerIndex(buffer.writerIndex() + read);
                allocator.incMessagesRead(1);
                readPending = false;
                ByteBuf message = buffer; buffer = null;
                pipeline().fireChannelRead(message);
                if (!open || inputShutdown) break;
            } while (allocator.continueReading());
            if (buffer != null) { buffer.release(); buffer = null; }
            allocator.readComplete();
            pipeline().fireChannelReadComplete();
            if (eof) shutdownInputOnRead();
        } catch (Throwable cause) {
            if (buffer != null) {
                if (buffer.isReadable()) { readPending = false; pipeline().fireChannelRead(buffer); }
                else buffer.release();
            }
            allocator.readComplete();
            pipeline().fireChannelReadComplete();
            pipeline().fireExceptionCaught(cause);
            if (eof || cause instanceof IOException || cause instanceof OutOfMemoryError) shutdownInputOnRead();
        } finally {
            if (!readPending && !config.isAutoRead()) refreshInterests();
        }
    }

    private void shutdownInputOnRead() {
        if (!open) return;
        if (!inputShutdown) {
            if (!config.isAllowHalfClosure()) { unsafe().close(voidPromise()); return; }
            try { VclException.check("shutdown input", poller.api.shutdown(session, 0)); }
            catch (IOException ignored) {
                pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                unsafe().close(voidPromise());
                return;
            }
            inputShutdown = true;
            readPending = false;
            pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        }
        if (!inputClosedSeenErrorOnRead) {
            inputClosedSeenErrorOnRead = true;
            pipeline().fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
        }
        closeIfBothShutdown();
    }

    @Override protected void doShutdownOutput() throws Exception {
        outputShutdown = true;
        writeBlocked = false;
        try { VclException.check("shutdown output", poller.api.shutdown(session, 1)); }
        finally { refreshInterests(); }
        closeIfBothShutdown();
    }
    @Override public boolean isInputShutdown() { return !open || inputShutdown; }
    @Override public boolean isOutputShutdown() { return !open || outputShutdown; }
    @Override public boolean isShutdown() { return isInputShutdown() && isOutputShutdown(); }
    @Override public ChannelFuture shutdownInput() { return shutdownInput(newPromise()); }
    @Override public ChannelFuture shutdownInput(ChannelPromise promise) {
        EventLoop loop = eventLoop();
        if (loop.inEventLoop()) shutdownInput0(promise);
        else loop.execute(() -> shutdownInput0(promise));
        return promise;
    }
    private void shutdownInput0(ChannelPromise promise) {
        try {
            if (!open) throw new ClosedChannelException();
            if (!inputShutdown) {
                inputShutdown = true;
                readPending = false;
                try { VclException.check("shutdown input", poller.api.shutdown(session, 0)); }
                finally { refreshInterests(); }
                closeIfBothShutdown();
            }
            promise.setSuccess();
        } catch (Throwable cause) { promise.setFailure(cause); }
    }
    @Override public ChannelFuture shutdownOutput() { return shutdownOutput(newPromise()); }
    @Override public ChannelFuture shutdownOutput(ChannelPromise promise) {
        EventLoop loop = eventLoop();
        if (loop.inEventLoop()) ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        else loop.execute(() -> ((AbstractUnsafe) unsafe()).shutdownOutput(promise));
        return promise;
    }
    @Override public ChannelFuture shutdown() { return shutdown(newPromise()); }
    @Override public ChannelFuture shutdown(ChannelPromise promise) {
        ChannelFuture output = shutdownOutput();
        if (output.isDone()) shutdownOutputDone(output, promise);
        else output.addListener((ChannelFutureListener) future -> shutdownOutputDone(future, promise));
        return promise;
    }
    private void shutdownOutputDone(ChannelFuture output, ChannelPromise promise) {
        ChannelFuture input = shutdownInput();
        if (input.isDone()) shutdownDone(output, input, promise);
        else input.addListener((ChannelFutureListener) future -> shutdownDone(output, future, promise));
    }
    private static void shutdownDone(ChannelFuture output, ChannelFuture input, ChannelPromise promise) {
        if (output.cause() != null) promise.setFailure(output.cause());
        else if (input.cause() != null) promise.setFailure(input.cause());
        else promise.setSuccess();
    }
    private void closeIfBothShutdown() {
        // VCL's CLOSE_WAIT shutdown only marks flags. close() sends the disconnected reply/FIN.
        // Defer until AbstractUnsafe has completed its output-buffer shutdown bookkeeping.
        if (inputShutdown && outputShutdown && open) eventLoop().execute(() -> unsafe().close(voidPromise()));
    }
}

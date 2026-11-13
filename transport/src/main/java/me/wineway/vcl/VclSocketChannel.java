/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import java.net.*;

public final class VclSocketChannel extends AbstractVclChannel implements SocketChannel {
    private final VclSocketChannelConfig config = new VclSocketChannelConfig(this);
    public VclSocketChannel() { super(null, false); }
    VclSocketChannel(VclServerSocketChannel parent, VclIo io, int session, InetSocketAddress local, InetSocketAddress peer) {
        super(parent, io, session, local, peer);
    }
    @Override public VclSocketChannelConfig config() { return config; }
    @Override public ServerSocketChannel parent() { return (ServerSocketChannel) super.parent(); }
    @Override protected void doBind(SocketAddress local) throws Exception { bound(address(local)); }
    @Override protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf buffer) return direct(buffer);
        throw new UnsupportedOperationException("TCP writes require ByteBuf");
    }
    @Override protected void doWrite(ChannelOutboundBuffer outbound) throws Exception {
        int budget = config.getWriteSpinCount();
        while (budget-- > 0) {
            ByteBuf buffer = (ByteBuf) outbound.current();
            if (buffer == null) { writeBlocked = false; interests(); return; }
            if (!buffer.isReadable()) { outbound.remove(); continue; }
            int length = Math.min(buffer.readableBytes(), 64 * 1024);
            int n = io.write(session, buffer.internalNioBuffer(buffer.readerIndex(), length));
            if (n == 0) { writeBlocked = true; interests(); return; }
            outbound.removeBytes(n);
        }
        if (outbound.current() != null) flushLater();
    }
    @Override void readReady() throws Exception {
        var handle = unsafe().recvBufAllocHandle();
        handle.reset(config);
        boolean eof = false;
        Throwable failure = null;
        try {
            int messages = 0;
            do {
                ByteBuf buffer = alloc().directBuffer(Math.max(1, Math.min(65536, handle.guess())));
                try {
                    handle.attemptedBytesRead(buffer.writableBytes());
                    int n = io.read(session, buffer.internalNioBuffer(buffer.writerIndex(), buffer.writableBytes()));
                    handle.lastBytesRead(n == VclIo.AGAIN ? 0 : n == 0 ? -1 : n);
                    if (n == VclIo.AGAIN) break;
                    if (n == 0) { eof = true; break; }
                    buffer.writerIndex(buffer.writerIndex() + n);
                    handle.incMessagesRead(1); readPending = false;
                    ByteBuf delivered = buffer; buffer = null;
                    pipeline().fireChannelRead(delivered);
                    messages++;
                } finally { if (buffer != null) buffer.release(); }
            } while (open && !inputShutdown && messages < 16 && (readPending || config.isAutoRead()) && handle.continueReading());
        } catch (Throwable t) { failure = t; }
        finally { handle.readComplete(); pipeline().fireChannelReadComplete(); }
        if (eof) {
            inputShutdown = true;
            if (config.isAllowHalfClosure()) pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
            else unsafe().close(unsafe().voidPromise());
        }
        if (failure != null) throw VclException.translate(failure);
    }
    @Override public boolean isInputShutdown() { return inputShutdown || !open; }
    @Override public boolean isOutputShutdown() { return outputShutdown || !open; }
    @Override public boolean isShutdown() { return isInputShutdown() && isOutputShutdown(); }
    @Override protected void doShutdownOutput() throws Exception {
        if (!outputShutdown) { io.shutdown(session, 1); outputShutdown = true; interests(); }
    }
    @Override public ChannelFuture shutdownInput() { return shutdownInput(newPromise()); }
    @Override public ChannelFuture shutdownOutput() { return shutdownOutput(newPromise()); }
    @Override public ChannelFuture shutdown() { return shutdown(newPromise()); }
    @Override public ChannelFuture shutdownInput(ChannelPromise promise) {
        Runnable action = () -> {
            if (!promise.setUncancellable()) return;
            try {
                if (!inputShutdown) { io.shutdown(session, 0); inputShutdown = true; interests(); }
                promise.trySuccess();
            } catch (Throwable t) { promise.tryFailure(t); }
        };
        execute(action, promise); return promise;
    }
    @Override public ChannelFuture shutdownOutput(ChannelPromise promise) {
        execute(() -> ((VclUnsafe) unsafe()).shutdownOutput(promise), promise); return promise;
    }
    @Override public ChannelFuture shutdown(ChannelPromise promise) {
        shutdownOutput().addListener(output -> shutdownInput().addListener(input -> {
            if (!output.isSuccess()) promise.tryFailure(output.cause());
            else if (!input.isSuccess()) promise.tryFailure(input.cause()); else promise.trySuccess();
        }));
        return promise;
    }
    private void execute(Runnable action, ChannelPromise promise) {
        try { if (eventLoop().inEventLoop()) action.run(); else eventLoop().execute(action); }
        catch (Throwable t) { promise.tryFailure(t); }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import java.net.*;
import java.nio.channels.NotYetConnectedException;

/** Unicast datagrams using native connected sessions and bound connectionless listeners. */
public final class VclDatagramChannel extends AbstractVclChannel implements DatagramChannel {
    private final VclDatagramChannelConfig config = new VclDatagramChannelConfig(this);
    private volatile boolean connected;
    public VclDatagramChannel() { super(null, true); }
    @Override public VclDatagramChannelConfig config() { return config; }
    @Override public boolean isConnected() { return open && connected; }
    @Override protected void doBind(SocketAddress local) throws Exception {
        bound(address(local)); active = true; interests();
    }
    @Override void connected() throws Exception { super.connected(); connected = true; }
    @Override protected void doDisconnect() throws Exception {
        if (!connected) return;
        int previous = session;
        int replacement = io.create(true);
        boolean replaced = false;
        try {
            replaceSession(replacement); replaced = true;
            io.closeSession(previous);
            io.bind(session, local);
            local = io.localAddress(session); invalidateLocalAddress();
            connected = false; active = false; remote = null; invalidateRemoteAddress(); interests();
        } catch (Throwable failure) {
            if (!replaced) try { io.closeSession(replacement); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            try { doClose(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw VclException.translate(failure);
        }
    }
    @Override protected Object filterOutboundMessage(Object message) {
        if (message instanceof ByteBuf buffer) {
            if (!buffer.isReadable()) throw new IllegalArgumentException("Zero-length UDP is unsupported by VPP 26.10");
            return direct(buffer);
        }
        if (message instanceof AddressedEnvelope<?, ?> envelope && envelope.content() instanceof ByteBuf buffer) {
            InetSocketAddress recipient = envelope.recipient() == null ? null : address(envelope.recipient());
            if (!buffer.isReadable()) throw new IllegalArgumentException("Zero-length UDP is unsupported by VPP 26.10");
            if (buffer.isDirect() && buffer.nioBufferCount() == 1 && message instanceof DatagramPacket) return message;
            ByteBuf copy = alloc().directBuffer(buffer.readableBytes());
            try { copy.writeBytes(buffer, buffer.readerIndex(), buffer.readableBytes()); }
            catch (Throwable t) { copy.release(); throw t; }
            ReferenceCountUtil.release(message);
            return new DatagramPacket(copy, recipient);
        }
        throw new UnsupportedOperationException("UDP writes require ByteBuf or an addressed ByteBuf envelope");
    }
    @Override protected void doWrite(ChannelOutboundBuffer outbound) throws Exception {
        int budget = config.getWriteSpinCount();
        while (budget-- > 0) {
            Object message = outbound.current();
            if (message == null) { writeBlocked = false; interests(); return; }
            ByteBuf buffer;
            InetSocketAddress recipient;
            if (message instanceof DatagramPacket packet) { buffer = packet.content(); recipient = packet.recipient(); }
            else { buffer = (ByteBuf) message; recipient = remote; }
            if (recipient == null) { outbound.remove(new NotYetConnectedException()); continue; }
            if (remote != null && !remote.equals(recipient)) {
                outbound.remove(new IllegalArgumentException("Recipient differs from connected UDP peer")); continue;
            }
            try {
                // Unbound sendto asks VCL to select a source address/port via its route,
                // then create a connectionless listener. Binding a wildcard first would
                // bypass that VCL path and could emit packets with an unspecified source.
                int n = io.send(session, buffer.internalNioBuffer(buffer.readerIndex(), buffer.readableBytes()), connected ? null : recipient);
                if (local == null) {
                    local = io.localAddress(session); invalidateLocalAddress(); active = true;
                    interests(); pipeline().fireChannelActive();
                }
                if (n == 0) { writeBlocked = true; interests(); return; }
                if (n != buffer.readableBytes()) throw new java.io.IOException("VCL returned a partial datagram write");
                outbound.remove();
            } catch (java.io.IOException e) { outbound.remove(e); }
        }
        if (outbound.current() != null) flushLater();
    }
    @Override void readReady() throws Exception {
        var handle = unsafe().recvBufAllocHandle(); handle.reset(config);
        try {
            int packets = 0;
            do {
                ByteBuf buffer = alloc().directBuffer(Math.max(1, Math.min(65535, handle.guess())));
                try {
                    handle.attemptedBytesRead(buffer.writableBytes());
                    var received = io.receive(session, buffer.internalNioBuffer(buffer.writerIndex(), buffer.writableBytes()));
                    if (received == null) { handle.lastBytesRead(0); break; }
                    packets++;
                    handle.lastBytesRead(received.bytes());
                    // Bound discarded traffic as well, so an unrelated peer cannot starve tasks/timers.
                    if (remote != null && !remote.equals(received.sender())) continue;
                    buffer.writerIndex(buffer.writerIndex() + received.bytes());
                    readPending = false; handle.incMessagesRead(1);
                    var packet = new DatagramPacket(buffer, local, received.sender()); buffer = null;
                    pipeline().fireChannelRead(packet);
                } finally { if (buffer != null) buffer.release(); }
            } while (open && packets < 16 && (readPending || config.isAutoRead()) && handle.continueReading());
        } finally { handle.readComplete(); pipeline().fireChannelReadComplete(); }
    }
    @Override public ChannelFuture joinGroup(InetAddress multicastAddress) { return newFailedFuture(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture joinGroup(InetAddress multicastAddress, ChannelPromise future) { return future.setFailure(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture joinGroup(InetSocketAddress multicastAddress, NetworkInterface networkInterface) { return newFailedFuture(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture joinGroup(InetSocketAddress multicastAddress, NetworkInterface networkInterface, ChannelPromise future) { return future.setFailure(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture joinGroup(InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source) { return newFailedFuture(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture joinGroup(InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source, ChannelPromise future) { return future.setFailure(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture leaveGroup(InetAddress multicastAddress) { return newFailedFuture(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture leaveGroup(InetAddress multicastAddress, ChannelPromise future) { return future.setFailure(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture leaveGroup(InetSocketAddress multicastAddress, NetworkInterface networkInterface) { return newFailedFuture(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture leaveGroup(InetSocketAddress multicastAddress, NetworkInterface networkInterface, ChannelPromise future) { return future.setFailure(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture leaveGroup(InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source) { return newFailedFuture(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture leaveGroup(InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source, ChannelPromise future) { return future.setFailure(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture block(InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress sourceToBlock) { return newFailedFuture(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture block(InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress sourceToBlock, ChannelPromise future) { return future.setFailure(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture block(InetAddress multicastAddress, InetAddress sourceToBlock) { return newFailedFuture(new UnsupportedOperationException("Only UDP unicast is supported")); }
    @Override public ChannelFuture block(InetAddress multicastAddress, InetAddress sourceToBlock, ChannelPromise future) { return future.setFailure(new UnsupportedOperationException("Only UDP unicast is supported")); }
}

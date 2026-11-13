/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.channel.*;
import io.netty.channel.socket.ServerSocketChannel;
import java.net.SocketAddress;

/** A listener whose accepted children must be registered on this listener's EventLoop. */
public final class VclServerSocketChannel extends AbstractVclChannel implements ServerSocketChannel {
    private final VclServerSocketChannelConfig config = new VclServerSocketChannelConfig(this);
    public VclServerSocketChannel() { super(null, false); }
    @Override public VclServerSocketChannelConfig config() { return config; }
    @Override protected void doBind(SocketAddress address) throws Exception {
        bound(address(address));
        io.listen(session, 128); // Required API argument; VPP 26.10 does not use q_len.
        local = io.localAddress(session); invalidateLocalAddress();
        active = true; interests();
    }
    @Override protected Object filterOutboundMessage(Object message) { throw new UnsupportedOperationException("Cannot write to listener"); }
    @Override protected void doWrite(ChannelOutboundBuffer outbound) { throw new UnsupportedOperationException("Cannot write to listener"); }
    @Override void readReady() throws Exception {
        var handle = unsafe().recvBufAllocHandle(); handle.reset(config);
        try {
            int messages = 0;
            do {
                var child = io.accept(session);
                if (child == null) { handle.lastBytesRead(0); break; }
                handle.lastBytesRead(1); handle.incMessagesRead(1); readPending = false;
                VclSocketChannel channel;
                try { channel = new VclSocketChannel(this, io, child.session(), local, child.peer()); }
                catch (Throwable t) { io.closeSession(child.session()); throw t; }
                pipeline().fireChannelRead(channel);
                messages++;
            } while (open && messages < 16 && (readPending || config.isAutoRead()) && handle.continueReading());
        } finally { handle.readComplete(); pipeline().fireChannelReadComplete(); }
    }
}

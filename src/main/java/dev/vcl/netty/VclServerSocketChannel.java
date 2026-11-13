package dev.vcl.netty;

import io.netty.channel.*;
import io.netty.channel.socket.*;
import java.net.SocketAddress;

/** One VCL worker's listener. Its children must register on this listener's EventLoop. */
public final class VclServerSocketChannel extends AbstractVclChannel implements ServerSocketChannel {
    private final VclServerSocketChannelConfig config = new VclServerSocketChannelConfig(this);

    public VclServerSocketChannel() { super(null); }
    @Override public ServerSocketChannelConfig config() { return config; }
    @Override protected void doBind(SocketAddress address) throws Exception {
        super.doBind(address);
        VclException.check("listen", poller.api.listen(session, config.getBacklog()));
        local = poller.api.address(session, false);
        active = true;
    }
    @Override void readReady() throws Exception {
        if (!open) return;
        RecvByteBufAllocator.Handle allocator = unsafe().recvBufAllocHandle();
        allocator.reset(config);
        allocator.attemptedBytesRead(1);
        Throwable exception = null;
        try {
            do {
                int accepted = poller.api.accept(session);
                allocator.lastBytesRead(accepted == NativeVcl.AGAIN ? -1 : accepted);
                if (accepted == NativeVcl.AGAIN) break;
                VclException.check("accept", accepted);
                allocator.incMessagesRead(1);
                VclSocketChannel child;
                try { child = new VclSocketChannel(this, accepted, poller); }
                catch (Exception | Error e) { poller.api.close(accepted); throw e; }
                readPending = false;
                pipeline().fireChannelRead(child);
            } while (open && allocator.continueReading());
        } catch (Throwable cause) { exception = cause; }
        finally {
            allocator.readComplete();
            pipeline().fireChannelReadComplete();
            if (exception != null) pipeline().fireExceptionCaught(exception);
            if (!readPending && !config.isAutoRead()) refreshInterests();
        }
    }
    @Override protected void doWrite(ChannelOutboundBuffer outbound) { throw new UnsupportedOperationException("Server channel cannot write"); }
    @Override protected Object filterOutboundMessage(Object message) { throw new UnsupportedOperationException("Server channel cannot write"); }
}

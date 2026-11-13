package dev.vcl.netty;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.socket.ServerSocketChannelConfig;
import java.util.Map;
import io.netty.util.NetUtil;

/** Native socket options are rejected until their VPP behavior is verified. */
@SuppressWarnings("deprecation")
final class VclServerSocketChannelConfig extends DefaultChannelConfig implements ServerSocketChannelConfig {
    private final VclServerSocketChannel channel;
    VclServerSocketChannelConfig(VclServerSocketChannel channel) { super(channel, new ServerChannelRecvByteBufAllocator()); this.channel = channel; }
    private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Native socket option is not supported by this VCL transport"); }
    @Override protected void autoReadCleared() { channel.autoReadCleared(); }
    @Override public ServerSocketChannelConfig setConnectTimeoutMillis(int value) { super.setConnectTimeoutMillis(value); return this; }
    @Override public ServerSocketChannelConfig setMaxMessagesPerRead(int value) { super.setMaxMessagesPerRead(value); return this; }
    @Override public ServerSocketChannelConfig setWriteSpinCount(int value) { super.setWriteSpinCount(value); return this; }
    @Override public ServerSocketChannelConfig setAllocator(ByteBufAllocator value) { super.setAllocator(value); return this; }
    @Override public ServerSocketChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator value) {
        if (!(value.newHandle() instanceof RecvByteBufAllocator.ExtendedHandle)) throw new IllegalArgumentException("allocator.newHandle() must return ExtendedHandle");
        super.setRecvByteBufAllocator(value); return this;
    }
    @Override public ServerSocketChannelConfig setAutoRead(boolean value) { super.setAutoRead(value); return this; }
    @Override public ServerSocketChannelConfig setAutoClose(boolean value) { super.setAutoClose(value); return this; }
    @Override public ServerSocketChannelConfig setMessageSizeEstimator(MessageSizeEstimator value) { super.setMessageSizeEstimator(value); return this; }
    @Override public ServerSocketChannelConfig setWriteBufferHighWaterMark(int value) { super.setWriteBufferHighWaterMark(value); return this; }
    @Override public ServerSocketChannelConfig setWriteBufferLowWaterMark(int value) { super.setWriteBufferLowWaterMark(value); return this; }
    @Override public ServerSocketChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark value) { super.setWriteBufferWaterMark(value); return this; }
    @Override public int getReceiveBufferSize() { throw unsupported(); }
    @Override public ServerSocketChannelConfig setReceiveBufferSize(int value) { throw unsupported(); }
    @Override public boolean isReuseAddress() { throw unsupported(); }
    @Override public ServerSocketChannelConfig setReuseAddress(boolean value) { throw unsupported(); }
    @Override public ServerSocketChannelConfig setPerformancePreferences(int connectionTime, int latency, int bandwidth) { throw unsupported(); }
    private volatile int backlog = NetUtil.SOMAXCONN;
    @Override public int getBacklog() { return backlog; }
    @Override public ServerSocketChannelConfig setBacklog(int value) { if (value < 0) throw new IllegalArgumentException("backlog < 0"); backlog = value; return this; }
    @Override public Map<ChannelOption<?>, Object> getOptions() { return getOptions(super.getOptions(), ChannelOption.SO_BACKLOG); }
    @Override @SuppressWarnings("unchecked") public <T> T getOption(ChannelOption<T> option) {
        if (option == ChannelOption.SO_BACKLOG) return (T) Integer.valueOf(backlog);
        return super.getOption(option);
    }
    @Override public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);
        if (option == ChannelOption.SO_BACKLOG) { setBacklog((Integer) value); return true; }
        if (option == ChannelOption.TCP_NODELAY || option == ChannelOption.SO_KEEPALIVE || option == ChannelOption.SO_LINGER
                || option == ChannelOption.SO_SNDBUF || option == ChannelOption.SO_RCVBUF || option == ChannelOption.SO_REUSEADDR || option == ChannelOption.IP_TOS) throw unsupported();
        return super.setOption(option, value);
    }
}


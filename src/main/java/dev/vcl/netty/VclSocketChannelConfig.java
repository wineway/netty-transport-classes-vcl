package dev.vcl.netty;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannelConfig;
import java.util.Map;

/** Native socket options are rejected until their VPP behavior is verified. */
@SuppressWarnings("deprecation")
final class VclSocketChannelConfig extends DefaultChannelConfig implements SocketChannelConfig {
    private final VclSocketChannel channel;
    VclSocketChannelConfig(VclSocketChannel channel) { super(channel); this.channel = channel; }
    private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Native socket option is not supported by this VCL transport"); }
    @Override protected void autoReadCleared() { channel.autoReadCleared(); }
    @Override public SocketChannelConfig setConnectTimeoutMillis(int value) { super.setConnectTimeoutMillis(value); return this; }
    @Override public SocketChannelConfig setMaxMessagesPerRead(int value) { super.setMaxMessagesPerRead(value); return this; }
    @Override public SocketChannelConfig setWriteSpinCount(int value) { super.setWriteSpinCount(value); return this; }
    @Override public SocketChannelConfig setAllocator(ByteBufAllocator value) { super.setAllocator(value); return this; }
    @Override public SocketChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator value) {
        if (!(value.newHandle() instanceof RecvByteBufAllocator.ExtendedHandle)) throw new IllegalArgumentException("allocator.newHandle() must return ExtendedHandle");
        super.setRecvByteBufAllocator(value); return this;
    }
    @Override public SocketChannelConfig setAutoRead(boolean value) { super.setAutoRead(value); return this; }
    @Override public SocketChannelConfig setAutoClose(boolean value) { super.setAutoClose(value); return this; }
    @Override public SocketChannelConfig setMessageSizeEstimator(MessageSizeEstimator value) { super.setMessageSizeEstimator(value); return this; }
    @Override public SocketChannelConfig setWriteBufferHighWaterMark(int value) { super.setWriteBufferHighWaterMark(value); return this; }
    @Override public SocketChannelConfig setWriteBufferLowWaterMark(int value) { super.setWriteBufferLowWaterMark(value); return this; }
    @Override public SocketChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark value) { super.setWriteBufferWaterMark(value); return this; }
    @Override public boolean isTcpNoDelay() { throw unsupported(); }
    @Override public SocketChannelConfig setTcpNoDelay(boolean value) { throw unsupported(); }
    @Override public int getSoLinger() { throw unsupported(); }
    @Override public SocketChannelConfig setSoLinger(int value) { throw unsupported(); }
    @Override public int getSendBufferSize() { throw unsupported(); }
    @Override public SocketChannelConfig setSendBufferSize(int value) { throw unsupported(); }
    @Override public boolean isKeepAlive() { throw unsupported(); }
    @Override public SocketChannelConfig setKeepAlive(boolean value) { throw unsupported(); }
    @Override public int getTrafficClass() { throw unsupported(); }
    @Override public SocketChannelConfig setTrafficClass(int value) { throw unsupported(); }
    @Override public int getReceiveBufferSize() { throw unsupported(); }
    @Override public SocketChannelConfig setReceiveBufferSize(int value) { throw unsupported(); }
    @Override public boolean isReuseAddress() { throw unsupported(); }
    @Override public SocketChannelConfig setReuseAddress(boolean value) { throw unsupported(); }
    @Override public SocketChannelConfig setPerformancePreferences(int connectionTime, int latency, int bandwidth) { throw unsupported(); }
    private volatile boolean halfClosure;
    @Override public boolean isAllowHalfClosure() { return halfClosure; }
    @Override public SocketChannelConfig setAllowHalfClosure(boolean value) { halfClosure = value; return this; }
    @Override public Map<ChannelOption<?>, Object> getOptions() { return getOptions(super.getOptions(), ChannelOption.ALLOW_HALF_CLOSURE); }
    @Override @SuppressWarnings("unchecked") public <T> T getOption(ChannelOption<T> option) {
        if (option == ChannelOption.ALLOW_HALF_CLOSURE) return (T) Boolean.valueOf(halfClosure);
        return super.getOption(option);
    }
    @Override public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);
        if (option == ChannelOption.ALLOW_HALF_CLOSURE) { setAllowHalfClosure((Boolean) value); return true; }
        if (option == ChannelOption.TCP_NODELAY || option == ChannelOption.SO_KEEPALIVE || option == ChannelOption.SO_LINGER
                || option == ChannelOption.SO_SNDBUF || option == ChannelOption.SO_RCVBUF || option == ChannelOption.SO_REUSEADDR || option == ChannelOption.IP_TOS) throw unsupported();
        return super.setOption(option, value);
    }
}


/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.channel.*;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.buffer.ByteBufAllocator;
import java.net.*;

public final class VclSocketChannelConfig extends DefaultChannelConfig implements SocketChannelConfig {
    private final AbstractVclChannel channel;
    private volatile boolean allowHalfClosure;
    VclSocketChannelConfig(AbstractVclChannel channel) { super(channel); this.channel = channel; setAllocator(io.netty.buffer.PooledByteBufAllocator.DEFAULT); }
    @Override protected void autoReadCleared() { channel.autoReadCleared(); }
    @Override public boolean isTcpNoDelay() { throw unsupported("isTcpNoDelay"); }
    @Override public VclSocketChannelConfig setTcpNoDelay(boolean tcpNoDelay) { throw unsupported("setTcpNoDelay"); }
    @Override public int getSoLinger() { throw unsupported("getSoLinger"); }
    @Override public VclSocketChannelConfig setSoLinger(int soLinger) { throw unsupported("setSoLinger"); }
    @Override public int getSendBufferSize() { throw unsupported("getSendBufferSize"); }
    @Override public VclSocketChannelConfig setSendBufferSize(int sendBufferSize) { throw unsupported("setSendBufferSize"); }
    @Override public int getReceiveBufferSize() { throw unsupported("getReceiveBufferSize"); }
    @Override public VclSocketChannelConfig setReceiveBufferSize(int receiveBufferSize) { throw unsupported("setReceiveBufferSize"); }
    @Override public boolean isKeepAlive() { throw unsupported("isKeepAlive"); }
    @Override public VclSocketChannelConfig setKeepAlive(boolean keepAlive) { throw unsupported("setKeepAlive"); }
    @Override public int getTrafficClass() { throw unsupported("getTrafficClass"); }
    @Override public VclSocketChannelConfig setTrafficClass(int trafficClass) { throw unsupported("setTrafficClass"); }
    @Override public boolean isReuseAddress() { throw unsupported("isReuseAddress"); }
    @Override public VclSocketChannelConfig setReuseAddress(boolean reuseAddress) { throw unsupported("setReuseAddress"); }
    @Override public VclSocketChannelConfig setPerformancePreferences(int connectionTime, int latency, int bandwidth) { throw unsupported("setPerformancePreferences"); }
    @Override public VclSocketChannelConfig setAllowHalfClosure(boolean allowHalfClosure) { this.allowHalfClosure = allowHalfClosure; return this; }
    @Override public VclSocketChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis) { super.setConnectTimeoutMillis(connectTimeoutMillis); return this; }
    @Override public VclSocketChannelConfig setMaxMessagesPerRead(int maxMessagesPerRead) { super.setMaxMessagesPerRead(maxMessagesPerRead); return this; }
    @Override public VclSocketChannelConfig setWriteSpinCount(int writeSpinCount) { super.setWriteSpinCount(writeSpinCount); return this; }
    @Override public VclSocketChannelConfig setAllocator(ByteBufAllocator allocator) { super.setAllocator(allocator); return this; }
    @Override public VclSocketChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator allocator) { super.setRecvByteBufAllocator(allocator); return this; }
    @Override public VclSocketChannelConfig setAutoRead(boolean autoRead) { super.setAutoRead(autoRead); return this; }
    @Override public VclSocketChannelConfig setAutoClose(boolean autoClose) { super.setAutoClose(autoClose); return this; }
    @Override public VclSocketChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator) { super.setMessageSizeEstimator(estimator); return this; }
    @Override public VclSocketChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark) { super.setWriteBufferWaterMark(writeBufferWaterMark); return this; }
    @Override public boolean isAllowHalfClosure() { return allowHalfClosure; }
    @Override public java.util.Map<ChannelOption<?>, Object> getOptions() { return getOptions(super.getOptions(), ChannelOption.ALLOW_HALF_CLOSURE); }
    @SuppressWarnings("unchecked") @Override public <T> T getOption(ChannelOption<T> option) { if (option == ChannelOption.ALLOW_HALF_CLOSURE) return (T) (Object) isAllowHalfClosure(); return super.getOption(option); }
    @Override public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);
        if (option == ChannelOption.ALLOW_HALF_CLOSURE) { setAllowHalfClosure((Boolean) value); return true; }
        if (super.setOption(option, value)) return true;
        throw unsupported(option.toString());
    }
    private static UnsupportedOperationException unsupported(String option) { return new UnsupportedOperationException("VCL option is not implemented or validated: " + option); }
}

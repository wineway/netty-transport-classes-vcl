/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.channel.*;
import io.netty.channel.socket.ServerSocketChannelConfig;
import io.netty.buffer.ByteBufAllocator;
import java.net.*;

public final class VclServerSocketChannelConfig extends DefaultChannelConfig implements ServerSocketChannelConfig {
    private final AbstractVclChannel channel;
    VclServerSocketChannelConfig(AbstractVclChannel channel) { super(channel); this.channel = channel; setAllocator(io.netty.buffer.PooledByteBufAllocator.DEFAULT); }
    @Override protected void autoReadCleared() { channel.autoReadCleared(); }
    @Override public int getBacklog() { throw unsupported("getBacklog"); }
    @Override public VclServerSocketChannelConfig setBacklog(int backlog) { throw unsupported("setBacklog: VPP 26.10 ignores q_len"); }
    @Override public boolean isReuseAddress() { throw unsupported("isReuseAddress"); }
    @Override public VclServerSocketChannelConfig setReuseAddress(boolean reuseAddress) { throw unsupported("setReuseAddress"); }
    @Override public int getReceiveBufferSize() { throw unsupported("getReceiveBufferSize"); }
    @Override public VclServerSocketChannelConfig setReceiveBufferSize(int receiveBufferSize) { throw unsupported("setReceiveBufferSize"); }
    @Override public VclServerSocketChannelConfig setPerformancePreferences(int connectionTime, int latency, int bandwidth) { throw unsupported("setPerformancePreferences"); }
    @Override public VclServerSocketChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis) { super.setConnectTimeoutMillis(connectTimeoutMillis); return this; }
    @Override public VclServerSocketChannelConfig setMaxMessagesPerRead(int maxMessagesPerRead) { super.setMaxMessagesPerRead(maxMessagesPerRead); return this; }
    @Override public VclServerSocketChannelConfig setWriteSpinCount(int writeSpinCount) { super.setWriteSpinCount(writeSpinCount); return this; }
    @Override public VclServerSocketChannelConfig setAllocator(ByteBufAllocator allocator) { super.setAllocator(allocator); return this; }
    @Override public VclServerSocketChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator allocator) { super.setRecvByteBufAllocator(allocator); return this; }
    @Override public VclServerSocketChannelConfig setAutoRead(boolean autoRead) { super.setAutoRead(autoRead); return this; }
    @Override public VclServerSocketChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator) { super.setMessageSizeEstimator(estimator); return this; }
    @Override public VclServerSocketChannelConfig setWriteBufferHighWaterMark(int writeBufferHighWaterMark) { super.setWriteBufferHighWaterMark(writeBufferHighWaterMark); return this; }
    @Override public VclServerSocketChannelConfig setWriteBufferLowWaterMark(int writeBufferLowWaterMark) { super.setWriteBufferLowWaterMark(writeBufferLowWaterMark); return this; }
    @Override public VclServerSocketChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark) { super.setWriteBufferWaterMark(writeBufferWaterMark); return this; }
    @Override public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);
        if (option == ChannelOption.SO_BACKLOG) { setBacklog((Integer) value); return true; }
        if (super.setOption(option, value)) return true;
        throw unsupported(option.toString());
    }
    private static UnsupportedOperationException unsupported(String option) { return new UnsupportedOperationException("VCL option is not implemented or validated: " + option); }
}

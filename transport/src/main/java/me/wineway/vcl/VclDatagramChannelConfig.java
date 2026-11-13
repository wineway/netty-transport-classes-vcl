/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.channel.*;
import io.netty.channel.socket.DatagramChannelConfig;
import io.netty.buffer.ByteBufAllocator;
import java.net.*;

public final class VclDatagramChannelConfig extends DefaultChannelConfig implements DatagramChannelConfig {
    private final AbstractVclChannel channel;
    VclDatagramChannelConfig(AbstractVclChannel channel) { super(channel); this.channel = channel; setAllocator(io.netty.buffer.PooledByteBufAllocator.DEFAULT); }
    @Override protected void autoReadCleared() { channel.autoReadCleared(); }
    @Override public int getSendBufferSize() { throw unsupported("getSendBufferSize"); }
    @Override public VclDatagramChannelConfig setSendBufferSize(int sendBufferSize) { throw unsupported("setSendBufferSize"); }
    @Override public int getReceiveBufferSize() { throw unsupported("getReceiveBufferSize"); }
    @Override public VclDatagramChannelConfig setReceiveBufferSize(int receiveBufferSize) { throw unsupported("setReceiveBufferSize"); }
    @Override public int getTrafficClass() { throw unsupported("getTrafficClass"); }
    @Override public VclDatagramChannelConfig setTrafficClass(int trafficClass) { throw unsupported("setTrafficClass"); }
    @Override public boolean isReuseAddress() { throw unsupported("isReuseAddress"); }
    @Override public VclDatagramChannelConfig setReuseAddress(boolean reuseAddress) { throw unsupported("setReuseAddress"); }
    @Override public boolean isBroadcast() { throw unsupported("isBroadcast"); }
    @Override public VclDatagramChannelConfig setBroadcast(boolean broadcast) { throw unsupported("setBroadcast"); }
    @Override public boolean isLoopbackModeDisabled() { throw unsupported("isLoopbackModeDisabled"); }
    @Override public VclDatagramChannelConfig setLoopbackModeDisabled(boolean loopbackModeDisabled) { throw unsupported("setLoopbackModeDisabled"); }
    @Override public int getTimeToLive() { throw unsupported("getTimeToLive"); }
    @Override public VclDatagramChannelConfig setTimeToLive(int ttl) { throw unsupported("setTimeToLive"); }
    @Override public InetAddress getInterface() { throw unsupported("getInterface"); }
    @Override public VclDatagramChannelConfig setInterface(InetAddress interfaceAddress) { throw unsupported("setInterface"); }
    @Override public NetworkInterface getNetworkInterface() { throw unsupported("getNetworkInterface"); }
    @Override public VclDatagramChannelConfig setNetworkInterface(NetworkInterface networkInterface) { throw unsupported("setNetworkInterface"); }
    @Override public VclDatagramChannelConfig setMaxMessagesPerRead(int maxMessagesPerRead) { super.setMaxMessagesPerRead(maxMessagesPerRead); return this; }
    @Override public VclDatagramChannelConfig setWriteSpinCount(int writeSpinCount) { super.setWriteSpinCount(writeSpinCount); return this; }
    @Override public VclDatagramChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis) { super.setConnectTimeoutMillis(connectTimeoutMillis); return this; }
    @Override public VclDatagramChannelConfig setAllocator(ByteBufAllocator allocator) { super.setAllocator(allocator); return this; }
    @Override public VclDatagramChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator allocator) { super.setRecvByteBufAllocator(allocator); return this; }
    @Override public VclDatagramChannelConfig setAutoRead(boolean autoRead) { super.setAutoRead(autoRead); return this; }
    @Override public VclDatagramChannelConfig setAutoClose(boolean autoClose) { super.setAutoClose(autoClose); return this; }
    @Override public VclDatagramChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator) { super.setMessageSizeEstimator(estimator); return this; }
    @Override public VclDatagramChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark) { super.setWriteBufferWaterMark(writeBufferWaterMark); return this; }
    @Override public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);
        if (super.setOption(option, value)) return true;
        throw unsupported(option.toString());
    }
    private static UnsupportedOperationException unsupported(String option) { return new UnsupportedOperationException("VCL option is not implemented or validated: " + option); }
}

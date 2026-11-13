/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.socket.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same observable contract against epoll, interrupt VCL and poll VCL. */
@Timeout(45)
class TransportContractIT {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void streamEchoHeapCompositeAndRetainedReceive(boolean ipv6) throws Exception {
        try (var transport = new RealTransportSupport()) {
            InetSocketAddress address = transport.listen(ipv6, () -> new ChannelInboundHandlerAdapter() {
                @Override public void channelRead(ChannelHandlerContext ctx, Object message) { ctx.writeAndFlush(message); }
                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
            });
            var received = new CompletableFuture<ByteBuf>();
            final int length = 256 * 1024;
            Channel client = transport.connect(address, new ChannelInboundHandlerAdapter() {
                final ByteBuf data = Unpooled.buffer(length);
                boolean transferred;
                @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                    ByteBuf buffer = (ByteBuf) message;
                    try {
                        data.writeBytes(buffer);
                        if (data.readableBytes() == length) { transferred = true; received.complete(data); }
                    } finally { buffer.release(); }
                }
                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { received.completeExceptionally(cause); ctx.close(); }
                @Override public void channelInactive(ChannelHandlerContext ctx) { if (!transferred) data.release(); }
            });
            var payload = Unpooled.compositeBuffer();
            for (int part = 0; part < 4; part++) {
                ByteBuf component = part % 2 == 0 ? Unpooled.buffer(length / 4) : Unpooled.directBuffer(length / 4);
                for (int i = 0; i < length / 4; i++) component.writeByte((part * length / 4 + i) & 255);
                payload.addComponent(true, component);
            }
            client.writeAndFlush(payload).sync(); assertEquals(0, payload.refCnt());
            ByteBuf echoed = received.get(20, TimeUnit.SECONDS);
            try {
                client.close().sync();
                for (int i = 0; i < length; i++) assertEquals(i & 255, echoed.getUnsignedByte(i));
            } finally { echoed.release(); }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void udpPreservesSourceBoundaryAndTruncation(boolean ipv6) throws Exception {
        try (var transport = new RealTransportSupport()) {
            var capture = new ChannelStateTest.Capture(); Channel receiver = transport.udp(ipv6, capture);
            receiver.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(8));
            Channel sender = transport.udp(ipv6, new ChannelStateTest.Capture());
            var destination = (InetSocketAddress) receiver.localAddress();
            sender.writeAndFlush(new DatagramPacket(Unpooled.buffer(32).writeZero(32), destination)).sync();
            sender.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(new byte[]{42}), destination)).sync();
            DatagramPacket first = (DatagramPacket) capture.reads.poll(10, TimeUnit.SECONDS);
            DatagramPacket second = (DatagramPacket) capture.reads.poll(10, TimeUnit.SECONDS);
            assertNotNull(first, "receiver=" + receiver + ", sender=" + sender + ", failures=" + capture.failures); assertNotNull(second);
            try {
                assertEquals(8, first.content().readableBytes()); assertEquals(sender.localAddress(), first.sender());
                assertEquals(1, second.content().readableBytes()); assertEquals(42, second.content().getByte(0));
            } finally { first.release(); second.release(); }
            receiver.close().sync(); sender.close().sync();
        }
    }
    @Test void halfCloseDeliversEofAfterDataAndKeepsOppositeDirectionOpen() throws Exception {
        try (var transport = new RealTransportSupport()) {
            var accepted = new CompletableFuture<SocketChannel>();
            var eof = new CompletableFuture<Void>();
            InetSocketAddress address = transport.listen(false, () -> new ChannelInboundHandlerAdapter() {
                @Override public void channelActive(ChannelHandlerContext ctx) {
                    var channel = (SocketChannel) ctx.channel(); channel.config().setAllowHalfClosure(true); accepted.complete(channel);
                }
                @Override public void channelRead(ChannelHandlerContext ctx, Object message) { io.netty.util.ReferenceCountUtil.release(message); }
                @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                    if (event == ChannelInputShutdownEvent.INSTANCE) eof.complete(null);
                }
                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { eof.completeExceptionally(cause); }
            });
            var capture = new ChannelStateTest.Capture(); var client = (SocketChannel) transport.connect(address, capture);
            SocketChannel server = accepted.get(10, TimeUnit.SECONDS);
            client.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{1,2,3})).sync(); client.shutdownOutput().sync();
            eof.get(10, TimeUnit.SECONDS); assertTrue(server.isActive()); assertTrue(server.isInputShutdown());
            server.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{9})).sync();
            ByteBuf reply = (ByteBuf) capture.reads.poll(10, TimeUnit.SECONDS); if (reply == null && client instanceof VclSocketChannel vcl) {
                String diagnostic = client.eventLoop().submit(() -> {
                    var buffer = java.nio.ByteBuffer.allocateDirect(16);
                    int n = vcl.io.read(vcl.session, buffer);
                    return "native_read=" + n + ", first=" + buffer.get(0) + ", readPending=" + vcl.readPending + ", autoRead=" + vcl.config().isAutoRead();
                }).get(3, TimeUnit.SECONDS);
                System.out.println("HALF_CLOSE_DIAGNOSTIC " + diagnostic);
            }
            assertNotNull(reply, "client=" + client + ", inputShutdown=" + client.isInputShutdown() + ", failures=" + capture.failures);
            try { assertEquals(9, reply.readByte()); } finally { reply.release(); }
            client.close().sync(); server.close().sync();
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.buffer.*;
import io.netty.channel.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class NativeServerIT {
    @Test void samePortListenersAcceptRepeatedChildrenAndSurviveIndependentClose() throws Exception {
        try (var transport = new RealTransportSupport()) {
            var accepted = new LinkedBlockingQueue<Channel>();
            var loops = new HashSet<EventLoop>();
            VclServer server = VclServer.bind(transport.group, transport.address(false, 0), () -> new ChannelInboundHandlerAdapter() {
                @Override public void channelActive(ChannelHandlerContext ctx) { accepted.add(ctx.channel()); }
                @Override public void channelRead(ChannelHandlerContext ctx, Object msg) { ctx.writeAndFlush(msg); }
                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable failure) { ctx.close(); }
            }).sync().getNow();
            try {
                assertEquals(2, server.listeners().size());
                for (Channel listener : server.listeners()) assertEquals(server.localAddress(), listener.localAddress());
                for (int index = 0; index < 40; index++) {
                    if (index == 20) server.listeners().getFirst().close().sync();
                    var capture = new ChannelStateTest.Capture();
                    Channel client = transport.connect(server.localAddress(), capture);
                    try {
                        client.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{(byte) index})).sync();
                        ByteBuf response = (ByteBuf) capture.reads.poll(5, TimeUnit.SECONDS); assertNotNull(response);
                        try { assertEquals(index, response.readUnsignedByte()); } finally { response.release(); }
                        Channel child = accepted.poll(5, TimeUnit.SECONDS); assertNotNull(child);
                        assertSame(child.parent().eventLoop(), child.eventLoop()); loops.add(child.eventLoop());
                        if (index >= 20) assertSame(server.listeners().getLast(), child.parent());
                    } finally { client.close().sync(); }
                }
                assertEquals(2, loops.size(), "both native listeners must accept connections");
            } finally { server.close().sync(); }
        }
    }
}

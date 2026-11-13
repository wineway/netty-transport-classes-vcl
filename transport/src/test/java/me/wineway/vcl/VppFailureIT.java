/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.buffer.*;
import io.netty.channel.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class VppFailureIT {
    @Test void serverExitFailsQueuedWritesAndNewRegistrations() throws Exception {
        assertNotEquals("epoll", System.getProperty("vcl.it.backend"));
        try (var transport = new RealTransportSupport()) {
            var address = transport.listen(false, () -> new ChannelInboundHandlerAdapter() {
                @Override public void channelActive(ChannelHandlerContext ctx) { ctx.channel().config().setAutoRead(false); }
            });
            var capture = new ChannelStateTest.Capture();
            Channel client = transport.connect(address, capture);
            ByteBuf pending = Unpooled.directBuffer(8 << 20).writeZero(8 << 20);
            var written = client.writeAndFlush(pending);
            client.eventLoop().submit(() -> {}).sync();
            assertFalse(written.isDone(), "fixture must leave data queued behind VCL FIFO backpressure");
            Files.writeString(Path.of(System.getProperty("vcl.it.output"), "kill-vpp.ready"), "ready\n");
            assertTrue(client.closeFuture().await(10, TimeUnit.SECONDS), "VPP failure did not close the client");
            assertTrue(written.await(5, TimeUnit.SECONDS)); assertFalse(written.isSuccess()); assertEquals(0, pending.refCnt());
            assertNotNull(capture.failures.poll(5, TimeUnit.SECONDS));
            var next = new VclSocketChannel(); next.pipeline().addLast(new ChannelStateTest.Capture());
            assertFalse(transport.group.register(next).await().isSuccess()); assertFalse(next.isOpen());
            ByteBuf after = Unpooled.directBuffer(1).writeByte(1);
            assertFalse(client.writeAndFlush(after).await().isSuccess()); assertEquals(0, after.refCnt());
        }
    }
}

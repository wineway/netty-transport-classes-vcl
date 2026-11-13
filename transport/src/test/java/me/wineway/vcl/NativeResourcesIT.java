/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import io.netty.buffer.*;
import io.netty.channel.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class NativeResourcesIT {
    @Test void repeatedApplicationAndWorkerLifetimesReleaseDescriptorsAndMappings() throws Exception {
        cycle(); // Initialize JVM, FFM and allocator caches before comparing native resources.
        long descriptors = descriptors(), mappings = mappings();
        for (int i = 0; i < Integer.getInteger("vcl.it.resourceCycles", 8); i++) {
            System.out.println("Resource cycle " + i); cycle();
            System.out.println("descriptors=" + descriptors() + " mappings=" + mappings());
        }
        assertEquals(descriptors, descriptors(), "file descriptors leaked across application lifetimes");
        assertEquals(mappings, mappings(), "shared memory mappings leaked across application lifetimes");
    }
    private static void cycle() throws Exception {
        try (var transport = new RealTransportSupport()) {
            if (!Boolean.parseBoolean(System.getProperty("vcl.it.resourceTraffic", "true"))) return;
            var address = transport.listen(false, () -> new ChannelInboundHandlerAdapter() {
                @Override public void channelRead(ChannelHandlerContext ctx, Object msg) { ctx.writeAndFlush(msg); }
                @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
            });
            var capture = new ChannelStateTest.Capture(); Channel client = transport.connect(address, capture);
            client.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{1})).sync();
            ByteBuf reply = (ByteBuf) capture.reads.poll(5, TimeUnit.SECONDS); assertNotNull(reply);
            reply.release(); client.close().sync();
            var udp = transport.udp(false, new ChannelStateTest.Capture()); udp.close().sync();
        }
    }
    private static long descriptors() throws Exception {
        try (var descriptors = Files.list(Path.of("/proc/self/fd"))) { return descriptors.count(); }
    }
    private static long mappings() throws Exception {
        try (var lines = Files.lines(Path.of("/proc/self/maps"))) {
            return lines.filter(line -> line.contains("memfd:") || line.contains("/dev/shm/")).count();
        }
    }
}

package dev.vcl.netty;

import org.junit.jupiter.api.Test;
import java.lang.foreign.*;
import java.net.InetSocketAddress;
import java.nio.ByteOrder;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class AbiTest {
    @Test void endpointUsesNetworkByteOrderAndOwnedNativeAddress() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ep = NativeVcl.endpoint(arena, new InetSocketAddress("10.20.30.40", 0x1234));
            assertEquals(48, ep.byteSize());
            assertEquals(1, ep.get(JAVA_BYTE, 1));
            assertArrayEquals(new byte[]{10, 20, 30, 40}, ep.get(ADDRESS, 8).reinterpret(4).toArray(JAVA_BYTE));
            assertEquals(0x12, ep.get(JAVA_BYTE, 16));
            assertEquals(0x34, ep.get(JAVA_BYTE, 17));
            assertEquals(0, ep.get(JAVA_INT, 32));
            assertEquals(MemorySegment.NULL, ep.get(ADDRESS, 40));
        }
    }

    @Test void packedEpollEventsCanBeReadAtUnalignedOffsets() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment events = arena.allocate(NativeVcl.EPOLL_EVENT, 2);
            events.set(JAVA_LONG_UNALIGNED, 4, 0x123456789abcdef0L);
            events.set(JAVA_INT, 12, NativeVcl.IN);
            events.set(JAVA_LONG_UNALIGNED, 16, Long.MAX_VALUE);
            assertEquals(12, NativeVcl.EPOLL_EVENT.byteSize());
            assertEquals(0x123456789abcdef0L, events.get(JAVA_LONG_UNALIGNED, 4));
            assertEquals(Long.MAX_VALUE, events.get(JAVA_LONG_UNALIGNED, 16));
        }
    }

    @Test void unsupportedAddressesFailBeforeNativeCall() {
        try (Arena arena = Arena.ofConfined()) {
            assertThrows(IllegalArgumentException.class, () -> NativeVcl.endpoint(arena, InetSocketAddress.createUnresolved("unknown.invalid", 80)));
            assertThrows(IllegalArgumentException.class, () -> NativeVcl.endpoint(arena, new InetSocketAddress("::1", 80)));
        }
    }

    @Test void nativeOptionsAreExplicitlyRejected() {
        VclSocketChannel channel = new VclSocketChannel();
        assertThrows(UnsupportedOperationException.class, () -> channel.config().setTcpNoDelay(true));
        assertThrows(UnsupportedOperationException.class, () -> channel.config().setOption(io.netty.channel.ChannelOption.SO_RCVBUF, 1024));
        channel.config().setAllowHalfClosure(true);
        assertTrue(channel.config().isAllowHalfClosure());
        assertEquals(ByteOrder.LITTLE_ENDIAN, ByteOrder.nativeOrder());
    }
}

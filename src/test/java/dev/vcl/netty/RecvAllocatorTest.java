package dev.vcl.netty;

import io.netty.buffer.*;
import io.netty.channel.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("deprecation")
class RecvAllocatorTest {
    @Test void configuredAllocationIsUsedAndPrefersDirectMemory() {
        AtomicInteger allocations = new AtomicInteger();
        RecvByteBufAllocator.ExtendedHandle delegate = (RecvByteBufAllocator.ExtendedHandle)
                new FixedRecvByteBufAllocator(73).newHandle();
        VclRecvByteAllocatorHandle handle = new VclRecvByteAllocatorHandle(new CountingHandle(delegate, allocations));
        ByteBuf buffer = handle.allocate(new UnpooledByteBufAllocator(false));
        try {
            assertEquals(1, allocations.get());
            assertEquals(73, buffer.capacity());
            assertTrue(buffer.isDirect());
        } finally { buffer.release(); }
    }

    @Test void configuredReadBudgetCanExceedSixteenAndRdHupDrainsWithAutoReadDisabled() {
        VclSocketChannel channel = new VclSocketChannel();
        try {
            FixedRecvByteBufAllocator allocator = new FixedRecvByteBufAllocator(32);
            allocator.maxMessagesPerRead(64);
            VclRecvByteAllocatorHandle handle = new VclRecvByteAllocatorHandle(
                    (RecvByteBufAllocator.ExtendedHandle) allocator.newHandle());
            handle.reset(channel.config());
            handle.attemptedBytesRead(32);
            for (int i = 0; i < 17; i++) { handle.lastBytesRead(32); handle.incMessagesRead(1); }
            assertTrue(handle.continueReading());
            channel.config().setAutoRead(false);
            assertFalse(handle.continueReading());
            handle.receivedRdHup();
            assertTrue(handle.continueReading());
        } finally { channel.unsafe().closeForcibly(); }
    }

    @Test void bothConfigsRequireExtendedReceiveHandles() {
        RecvByteBufAllocator unsupported = () -> new RecvByteBufAllocator.DelegatingHandle(
                new FixedRecvByteBufAllocator(32).newHandle());
        VclSocketChannel socket = new VclSocketChannel();
        VclServerSocketChannel server = new VclServerSocketChannel();
        try {
            assertThrows(IllegalArgumentException.class, () -> socket.config().setRecvByteBufAllocator(unsupported));
            assertThrows(IllegalArgumentException.class, () -> server.config().setRecvByteBufAllocator(unsupported));
            assertInstanceOf(ServerChannelRecvByteBufAllocator.class, server.config().getRecvByteBufAllocator());
        } finally { socket.unsafe().closeForcibly(); server.unsafe().closeForcibly(); }
    }

    private static final class CountingHandle extends RecvByteBufAllocator.DelegatingHandle implements RecvByteBufAllocator.ExtendedHandle {
        private final RecvByteBufAllocator.ExtendedHandle delegate;
        private final AtomicInteger allocations;
        CountingHandle(RecvByteBufAllocator.ExtendedHandle delegate, AtomicInteger allocations) {
            super(delegate); this.delegate = delegate; this.allocations = allocations;
        }
        @Override public ByteBuf allocate(ByteBufAllocator allocator) { allocations.incrementAndGet(); return super.allocate(allocator); }
        @Override public boolean continueReading(io.netty.util.UncheckedBooleanSupplier supplier) { return delegate.continueReading(supplier); }
    }
}

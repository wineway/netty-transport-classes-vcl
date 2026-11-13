package dev.vcl.netty;

import java.util.concurrent.atomic.LongAdder;

/** Process-local transport counters; snapshots are observational, not transactional. */
public final class VclMetrics {
    final LongAdder readBytes = new LongAdder(), writtenBytes = new LongAdder();
    final LongAdder readWouldBlock = new LongAdder(), writeWouldBlock = new LongAdder();
    final LongAdder ioEvents = new LongAdder(), ioFailures = new LongAdder(), registrations = new LongAdder();

    public record Snapshot(int activeWorkers, long activeRegistrations, long readBytes, long writtenBytes,
                           long readWouldBlock, long writeWouldBlock, long ioEvents, long ioFailures) { }

    Snapshot snapshot(int workers) {
        return new Snapshot(workers, registrations.sum(), readBytes.sum(), writtenBytes.sum(),
                readWouldBlock.sum(), writeWouldBlock.sum(), ioEvents.sum(), ioFailures.sum());
    }
}

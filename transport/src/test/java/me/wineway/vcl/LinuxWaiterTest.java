/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class LinuxWaiterTest {
    @Test void wakeupsBeforeAndDuringWaitAreNotLost() throws Exception {
        try (var waiter = new LinuxWaiter(VclMode.INTERRUPT, null, -1);
             var sender = Executors.newSingleThreadExecutor()) {
            for (int i = 0; i < 500; i++) {
                if (i % 2 == 0) { sender.submit(() -> { waiter.wakeup(); return null; }).get(); assertTrue(waiter.await(1_000_000_000L)); }
                else {
                    Future<?> write = sender.submit(() -> { waiter.wakeup(); return null; });
                    assertTrue(waiter.await(1_000_000_000L)); write.get();
                }
            }
        }
    }
    @Test void ownerChecksAndWakeupCloseRace() throws Exception {
        var waiter = new LinuxWaiter(VclMode.INTERRUPT, null, -1);
        try (var sender = Executors.newSingleThreadExecutor()) {
            sender.submit(() -> {
                assertThrows(IllegalStateException.class, () -> waiter.await(0));
                assertThrows(IllegalStateException.class, waiter::close);
            }).get();
            var start = new CountDownLatch(1);
            Future<?> waking = sender.submit(() -> { start.countDown(); for (int i = 0; i < 10000; i++) waiter.wakeup(); return null; });
            start.await(); waiter.close(); waking.get();
            waiter.close(); waiter.wakeup();
            assertThrows(IllegalStateException.class, waiter::alive);
        } finally { waiter.close(); }
    }
    @Test void repeatedConstructionAndFailureReleaseDescriptors() throws Exception {
        try (var ignored = new LinuxWaiter(VclMode.INTERRUPT, null, -1)) { }
        long before = descriptors();
        for (int i = 0; i < 100; i++) {
            try (var ignored = new LinuxWaiter(VclMode.INTERRUPT, null, -1)) { }
            assertThrows(IllegalArgumentException.class, () -> new LinuxWaiter(VclMode.POLL, null, 1));
        }
        assertEquals(before, descriptors());
    }
    @Test void pollDoesNotRequireKernelWaitDescriptors() throws Exception {
        try (var waiter = new LinuxWaiter(VclMode.POLL, null, -1)) {
            assertTrue(waiter.alive()); assertTrue(waiter.await(Long.MAX_VALUE)); waiter.wakeup();
        }
    }
    @Test void virtualThreadCannotOwnNativeWorker() throws Exception {
        try (var virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            virtual.submit(() -> assertThrows(IllegalArgumentException.class,
                    () -> new LinuxWaiter(VclMode.INTERRUPT, null, -1))).get();
        }
    }
    private static long descriptors() throws Exception {
        try (var descriptors = Files.list(Path.of("/proc/self/fd"))) { return descriptors.count(); }
    }
}

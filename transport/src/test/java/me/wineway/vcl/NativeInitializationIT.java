/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

/** A failed native attach must release process ownership, descriptors and mappings. */
@Timeout(30)
class NativeInitializationIT {
    @Test void failedAttachReleasesResourcesAndAllowsRetry() throws Exception {
        Path library = Path.of(System.getProperty("vcl.it.library"));
        VclMode mode = VclMode.valueOf(System.getProperty("vcl.it.backend").toUpperCase(Locale.ROOT));
        try (var warmup = new RealTransportSupport()) { }
        long descriptors = descriptors(), mappings = mappings();
        Path missingSocket = Path.of(System.getProperty("vcl.it.output"), "missing-sapi.sock");
        assertFalse(Files.exists(missingSocket));
        assertThrows(Exception.class, () -> {
            try (var unexpected = VclRuntime.open(library, missingSocket, mode, 2)) {
                fail("Attaching to a missing VPP socket must fail");
            }
        });
        assertEquals(descriptors, descriptors(), "failed attach leaked descriptors");
        assertEquals(mappings, mappings(), "failed attach leaked mappings");
        try (var retry = new RealTransportSupport()) {
            for (var loop : retry.group) loop.submit(() -> {}).sync();
        }
        assertEquals(descriptors, descriptors(), "retry leaked descriptors");
        assertEquals(mappings, mappings(), "retry leaked mappings");
    }
    private static long descriptors() throws Exception {
        try (var files = Files.list(Path.of("/proc/self/fd"))) { return files.count(); }
    }
    private static long mappings() throws Exception {
        try (var lines = Files.lines(Path.of("/proc/self/maps"))) {
            return lines.filter(line -> line.contains("memfd:") || line.contains("/dev/shm/")).count();
        }
    }
}

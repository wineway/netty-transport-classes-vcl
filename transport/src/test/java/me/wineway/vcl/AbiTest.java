/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import org.junit.jupiter.api.Test;
import java.lang.foreign.StructLayout;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AbiTest {
    @Test void productionLayoutsMatchArchivedOfficialHeaderProbe() throws Exception {
        // The header-only C probe was built from the pristine pinned 26.10 checkout.
        // This is a layout regression test, not validation of a replacement runtime library.
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("../preflight/results/abi.properties"))) { properties.load(reader); }
        Map<String, StructLayout> layouts = Map.of("vppcom_endpt_t", VclAbi.ENDPOINT,
                "vppcom_data_segment_t", VclAbi.SEGMENT, "vcl_poll_t", VclAbi.POLL,
                "epoll_event", VclAbi.EPOLL, "vppcom_cfg_t", VclAbi.CONFIG);
        for (var entry : layouts.entrySet()) {
            assertEquals(Long.parseLong(properties.getProperty(entry.getKey() + ".size")), entry.getValue().byteSize());
            assertEquals(Long.parseLong(properties.getProperty(entry.getKey() + ".align")), entry.getValue().byteAlignment());
            for (var member : entry.getValue().memberLayouts()) if (member.name().isPresent()) {
                String name = member.name().orElseThrow();
                assertEquals(Long.parseLong(properties.getProperty(entry.getKey() + "." + name)),
                        VclAbi.offset(entry.getValue(), name), entry.getKey() + "." + name);
            }
        }
    }
}

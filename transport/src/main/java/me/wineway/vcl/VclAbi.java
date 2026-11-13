/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import java.lang.foreign.*;
import java.util.*;

import static java.lang.foreign.ValueLayout.*;
import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/** Explicit Linux x86_64 layouts, checked against headers before native execution. */
final class VclAbi {
    static final StructLayout ENDPOINT = struct(
            JAVA_BYTE.withName("unused"), JAVA_BYTE.withName("is_ip4"), ADDRESS.withName("ip"),
            JAVA_SHORT.withName("port"), JAVA_LONG.withName("unused2"),
            JAVA_INT.withName("app_tlv_len"), ADDRESS.withName("app_tlvs"));
    static final StructLayout SEGMENT = struct(ADDRESS.withName("data"), JAVA_INT.withName("len"));
    static final StructLayout POLL = struct(JAVA_INT.withName("fds_ndx"), JAVA_INT.withName("sh"),
            JAVA_SHORT.withName("events"), JAVA_SHORT.withName("revents"));
    static final StructLayout EPOLL = MemoryLayout.structLayout(
            JAVA_INT_UNALIGNED.withName("events"), JAVA_LONG_UNALIGNED.withName("data"));
    static final StructLayout CONFIG = struct(
            JAVA_LONG.withName("heapsize"), JAVA_INT.withName("max_workers"),
            JAVA_LONG.withName("segment_baseva"), JAVA_LONG.withName("segment_size"),
            JAVA_LONG.withName("add_segment_size"), JAVA_INT.withName("preallocated_fifo_pairs"),
            JAVA_INT.withName("rx_fifo_size"), JAVA_INT.withName("tx_fifo_size"),
            JAVA_INT.withName("event_queue_size"), JAVA_BYTE.withName("app_proxy_transport_tcp"),
            JAVA_BYTE.withName("app_proxy_transport_udp"), JAVA_BYTE.withName("app_scope_local"),
            JAVA_BYTE.withName("app_scope_global"), ADDRESS.withName("namespace_id"),
            JAVA_LONG.withName("namespace_secret"), JAVA_BYTE.withName("use_mq_eventfd"),
            JAVA_DOUBLE.withName("app_timeout"), JAVA_DOUBLE.withName("session_timeout"),
            ADDRESS.withName("event_log_path"), ADDRESS.withName("vpp_app_socket_api"),
            ADDRESS.withName("vpp_bapi_socket_name"), ADDRESS.withName("app_name"),
            JAVA_INT.withName("tls_engine"), JAVA_BYTE.withName("mt_wrk_supported"),
            JAVA_BYTE.withName("huge_page"), JAVA_BYTE.withName("app_original_dst"),
            JAVA_BYTE.withName("debug_level"));

    private static StructLayout struct(MemoryLayout... fields) {
        var members = new ArrayList<MemoryLayout>();
        long offset = 0, alignment = 1;
        for (var field : fields) {
            long padding = Math.floorMod(-offset, field.byteAlignment());
            if (padding != 0) members.add(MemoryLayout.paddingLayout(padding));
            members.add(field);
            offset += padding + field.byteSize();
            alignment = Math.max(alignment, field.byteAlignment());
        }
        long padding = Math.floorMod(-offset, alignment);
        if (padding != 0) members.add(MemoryLayout.paddingLayout(padding));
        return MemoryLayout.structLayout(members.toArray(MemoryLayout[]::new));
    }

    static long offset(StructLayout layout, String field) { return layout.byteOffset(groupElement(field)); }

}

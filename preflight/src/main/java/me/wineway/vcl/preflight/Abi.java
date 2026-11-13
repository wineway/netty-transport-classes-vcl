/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl.preflight;

import java.lang.foreign.*;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static java.lang.foreign.ValueLayout.*;
import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/** Explicit Linux x86_64 layouts, checked against headers before native execution. */
final class Abi {
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

    static void verify(Path headerProbe) throws Exception {
        if (!System.getProperty("os.name").equals("Linux") || !System.getProperty("os.arch").equals("amd64")
                || ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN || Runtime.version().feature() != 26) {
            throw new IllegalStateException("Requires Linux x86_64 and Java 26");
        }
        var values = new Properties();
        try (var reader = Files.newBufferedReader(headerProbe)) { values.load(reader); }
        var layouts = Map.of("vppcom_endpt_t", ENDPOINT, "vppcom_data_segment_t", SEGMENT,
                "vcl_poll_t", POLL, "epoll_event", EPOLL, "vppcom_cfg_t", CONFIG);
        int checks = 0;
        for (var entry : layouts.entrySet()) {
            String name = entry.getKey();
            var layout = entry.getValue();
            check(values, name + ".size", layout.byteSize());
            check(values, name + ".align", layout.byteAlignment());
            checks += 2;
            for (var field : layout.memberLayouts()) {
                if (field.name().isPresent()) {
                    String f = field.name().orElseThrow();
                    check(values, name + "." + f, offset(layout, f));
                    checks++;
                }
            }
        }
        for (var entry : Map.of("pointer", ADDRESS, "size_t", JAVA_LONG, "uint32_t", JAVA_INT,
                "uint16_t", JAVA_SHORT, "int", JAVA_INT, "long", JAVA_LONG).entrySet()) {
            check(values, entry.getKey() + ".size", entry.getValue().byteSize());
            check(values, entry.getKey() + ".align", entry.getValue().byteAlignment());
            checks += 2;
        }
        var constants = Map.ofEntries(Map.entry("EPOLLIN", 1), Map.entry("EPOLLOUT", 4),
                Map.entry("EPOLLERR", 8), Map.entry("EPOLLHUP", 16), Map.entry("EPOLLRDHUP", 8192),
                Map.entry("EPOLL_CTL_ADD", 1), Map.entry("EPOLL_CTL_MOD", 3), Map.entry("EPOLL_CTL_DEL", 2),
                Map.entry("VPPCOM_PROTO_TCP", 0), Map.entry("VPPCOM_PROTO_UDP", 1),
                Map.entry("VPPCOM_EAGAIN", -11), Map.entry("VPPCOM_EINPROGRESS", -115),
                Map.entry("VPPCOM_ENOTSUP", -95), Map.entry("O_NONBLOCK", 2048),
                Map.entry("VPPCOM_ATTR_GET_NREAD", 0), Map.entry("VPPCOM_ATTR_GET_LCL_ADDR", 4),
                Map.entry("htons(0x1234)", Short.toUnsignedInt(Short.reverseBytes((short) 0x1234))));
        for (var entry : constants.entrySet()) { check(values, entry.getKey(), entry.getValue()); checks++; }
        System.out.println("{\"test\":\"abi\",\"checks\":" + checks + ",\"passed\":true}");
    }

    private static void check(Properties values, String key, long expected) {
        if (!Long.toString(expected).equals(values.getProperty(key))) {
            throw new AssertionError(key + ": Java=" + expected + ", C=" + values.getProperty(key));
        }
    }
}

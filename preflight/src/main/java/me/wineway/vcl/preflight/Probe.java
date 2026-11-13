/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl.preflight;

import java.lang.foreign.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static java.lang.foreign.ValueLayout.*;

/** Isolated native experiments, not a transport or a Channel implementation. */
public final class Probe {
    private final Calls c;
    private final Arena arena;
    private final MemorySegment events;
    private final int epoll;
    private final List<Integer> sessions = new ArrayList<>();

    private Probe(Calls c, Arena arena) throws Throwable {
        this.c = c;
        this.arena = arena;
        events = arena.allocate(Abi.EPOLL, 128);
        epoll = ok(c.epollCreate(), "epoll_create");
        sessions.add(epoll);
    }

    public static void main(String[] args) throws Throwable {
        Abi.verify(Path.of(args[0]));
        if (args.length == 1) { WakeupProbe.run(); return; }
        var c = new Calls(Path.of(args[1]));
        boolean interrupt = args[3].equals("interrupt");
        try (var arena = Arena.ofConfined(); var monitor = new LivenessProbe(args[2])) {
            ok(c.createApp(Calls.config(arena, args[2], interrupt)), "app_create");
            try {
                var probe = new Probe(c, arena);
                try {
                    System.out.println("{\"test\":\"worker\",\"mode\":\"" + args[3]
                            + "\",\"index\":" + c.worker() + ",\"mq_fd\":" + c.mqFd()
                            + ",\"detached_query\":" + c.detached() + "}");
                    if (interrupt) WakeupProbe.nested(c.mqFd());
                    switch (args[4]) {
                        case "smoke" -> {
                            probe.tcp("10.77.0.1", 23000);
                            probe.tcp("fd77::1", 23001);
                        }
                        case "udp" -> {
                            probe.udp("10.77.0.1", 23002, Path.of(args[5]).resolveSibling("udp4.ready"));
                            probe.udp("fd77::1", 23004, Path.of(args[5]).resolveSibling("udp6.ready"));
                        }
                        case "retention" -> probe.retention(Path.of(args[5]), interrupt, monitor);
                        case "bench" -> probe.bench();
                        case "workers" -> probe.multiworker();
                        case "workers-after-close" -> {
                            probe.tcp("10.77.0.1", 23000);
                            probe.tcp("fd77::1", 23001);
                            probe.multiworker();
                        }
                        default -> throw new IllegalArgumentException("Unknown scenario");
                    }
                } finally { probe.close(); }
            } finally { c.destroyApp(); }
        }
        System.out.println("{\"test\":\"probe_complete\",\"passed\":true}");
    }

    static int ok(int rv, String operation) {
        if (rv < 0) throw new IllegalStateException(operation + " returned " + rv);
        return rv;
    }
    static void require(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }
    private int session(int protocol) throws Throwable {
        int s = ok(c.create(protocol), "session_create");
        sessions.add(s);
        require(c.sessionWorker(s) == c.worker(), "Session owned by wrong worker");
        var event = arena.allocate(Abi.EPOLL);
        event.set(JAVA_INT_UNALIGNED, 0, 1 | 4 | 8192);
        event.set(JAVA_LONG_UNALIGNED, 4, Integer.toUnsignedLong(s));
        ok(c.epollCtl(epoll, 1, s, event), "epoll_ctl");
        return s;
    }
    private void poll() throws Throwable { ok(c.poll(epoll, events), "epoll_wait"); }
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }
    private static void checkDeadline(long deadline) {
        if (System.nanoTime() - deadline >= 0) throw new AssertionError("Native operation timed out");
    }
    private int listener(String ip, int port) throws Throwable {
        int s = session(0);
        ok(c.bind(s, Calls.endpoint(arena, ip, port)), "bind");
        ok(c.listen(s), "listen");
        return s;
    }
    private int client(String ip, int port) throws Throwable {
        int s = session(0);
        int rv = c.connect(s, Calls.endpoint(arena, ip, port));
        if (rv != -115) ok(rv, "connect");
        return s;
    }
    private int accept(int listener) throws Throwable {
        int ready = c.available(listener);
        if (ready == 0) return -11;
        if (ready < 0) return ready;
        int s = c.accept(listener, MemorySegment.NULL);
        if (s >= 0) {
            sessions.add(s);
            require(c.sessionWorker(s) == c.worker(), "Accepted child changed worker");
        }
        return s;
    }
    private int[] pair(String ip, int port) throws Throwable {
        int listener = listener(ip, port);
        int client = client(ip, port);
        long end = deadline();
        int child;
        while ((child = accept(listener)) == -11) { poll(); checkDeadline(end); }
        ok(child, "accept");
        poll();
        return new int[]{client, child, listener};
    }
    private void write(int s, MemorySegment data, int length) throws Throwable {
        int offset = 0;
        long end = deadline();
        while (offset < length) {
            int rv = c.write(s, data.asSlice(offset), length - offset);
            if (rv > 0) offset += rv;
            else if (rv != -11 && rv != -107) ok(rv, "write");
            poll(); checkDeadline(end);
        }
    }
    private void read(int s, MemorySegment data, int length) throws Throwable {
        int offset = 0;
        long end = deadline();
        while (offset < length) {
            int rv = c.read(s, data.asSlice(offset), length - offset);
            if (rv > 0) offset += rv;
            else if (rv != -11) throw new AssertionError("read returned " + rv);
            poll(); checkDeadline(end);
        }
    }
    private void tcp(String ip, int port) throws Throwable {
        int[] pair = pair(ip, port);
        var data = arena.allocateFrom("segment-lifetime");
        var received = arena.allocate(data.byteSize());
        write(pair[0], data, (int) data.byteSize());
        read(pair[1], received, (int) data.byteSize());
        require(data.mismatch(received) == -1, "TCP payload mismatch");
        write(pair[0], data, (int) data.byteSize());
        var ds = arena.allocate(Abi.SEGMENT, 16);
        int count = segmentRead(pair[1], ds, (int) data.byteSize());
        require(count == data.byteSize(), "Segment read length");
        var borrowed = ds.get(ADDRESS, 0).reinterpret(ds.get(JAVA_INT, 8));
        require(borrowed.asSlice(0, data.byteSize()).mismatch(data) == -1, "Segment data mismatch");
        c.free(pair[1], count);
        for (int s : pair) close(s);
        System.out.println("{\"test\":\"tcp\",\"ip\":\"" + ip + "\",\"copy_and_segments\":true}");
    }
    private int segmentRead(int s, MemorySegment ds, int length) throws Throwable {
        long end = deadline();
        int rv;
        while ((rv = c.segments(s, ds, (int) (ds.byteSize() / Abi.SEGMENT.byteSize()), length)) == -11) {
            poll(); checkDeadline(end);
        }
        return ok(rv, "read_segments");
    }
    private void udp(String ip, int port, Path ready) throws Throwable {
        int receiver = session(1);
        var dest = Calls.endpoint(arena, ip, port);
        var source = Calls.endpoint(arena, ip.contains(":") ? "fd77::2" : "10.77.0.2", port + 1);
        var peer = Calls.endpoint(arena, "0.0.0.0", 0);
        ok(c.bind(receiver, dest), "udp_bind");
        var data = arena.allocate(32); data.fill((byte) 0x5a);
        Files.writeString(ready, "bound\n");
        long end = deadline();
        int rv;
        while ((rv = c.recvfrom(receiver, data, 8, peer)) == -11) { poll(); checkDeadline(end); }
        require(rv == 8, "UDP truncation length=" + rv);
        require(peer.get(JAVA_SHORT, Abi.offset(Abi.ENDPOINT, "port"))
                == source.get(JAVA_SHORT, Abi.offset(Abi.ENDPOINT, "port")), "UDP peer port");
        require(peer.get(JAVA_BYTE, 1) == source.get(JAVA_BYTE, 1), "UDP peer family");
        int ipLength = ip.contains(":") ? 16 : 4;
        require(peer.get(ADDRESS, 8).reinterpret(ipLength).mismatch(source.get(ADDRESS, 8).reinterpret(ipLength)) == -1,
                "UDP peer address");
        int emptySend = c.sendto(receiver, data, 0, source);
        require(emptySend == 0, "Empty send return");
        end = deadline();
        while ((rv = c.recvfrom(receiver, data, 32, peer)) == -11) { poll(); checkDeadline(end); }
        // Packet-generator injects [32 bytes, empty, 1 byte] in that order.
        // The positive marker proves RX progressed past the injected empty datagram.
        boolean emptyReceived = rv == 0;
        require(rv == 1, "Reassess known UDP empty-receive gap, received=" + rv);
        System.out.println("{\"test\":\"udp\",\"ip\":\"" + ip
                + "\",\"source_and_truncation\":true,\"empty_send_return\":" + emptySend
                + ",\"empty_received_before_marker\":" + emptyReceived + "}");
        close(receiver);
    }

    private void multiworker() throws Throwable {
        var stop = new AtomicBoolean();
        var removeFirst = new AtomicBoolean();
        var ready = new CountDownLatch(2);
        var firstClosed = new CountDownLatch(1);
        var failed = new AtomicReference<Throwable>();
        var accepted = new AtomicIntegerArray(2);
        var threads = new ArrayList<Thread>();
        for (int index = 0; index < 2; index++) {
            final int id = index;
            Thread thread = Thread.ofPlatform().name("vcl-probe-" + id).unstarted(() -> {
                boolean registered = false;
                try (var a = Arena.ofConfined()) {
                    ok(c.register(), "worker_register"); registered = true;
                    var p = new Probe(c, a);
                    try {
                        int listener = p.listener("10.77.0.1", 23100);
                        var buf = a.allocate(1);
                        var children = new ArrayList<Integer>();
                        ready.countDown();
                        while (!stop.get()) {
                            if (id == 0 && removeFirst.get() && listener >= 0) {
                                p.close(listener); listener = -1; firstClosed.countDown();
                            }
                            p.poll();
                            if (listener >= 0) {
                                int child = p.accept(listener);
                                if (child >= 0) { children.add(child); accepted.incrementAndGet(id); }
                                else if (child != -11) ok(child, "accept");
                            }
                            for (var it = children.iterator(); it.hasNext();) {
                                int child = it.next();
                                int rv = c.read(child, buf, 1);
                                if (rv == 1) { p.write(child, buf, 1); p.close(child); it.remove(); }
                                else if (rv != -11) throw new AssertionError("child read=" + rv);
                            }
                        }
                    } finally { p.close(); }
                } catch (Throwable t) { failed.compareAndSet(null, t); }
                finally {
                    ready.countDown(); firstClosed.countDown();
                    if (registered) try { c.unregister(); } catch (Throwable t) { failed.compareAndSet(null, t); }
                }
            });
            threads.add(thread); thread.start();
        }
        try {
            require(ready.await(10, TimeUnit.SECONDS), "Listener startup deadline");
            if (failed.get() != null) throw failed.get();
            var data = arena.allocate(1); data.set(JAVA_BYTE, 0, (byte) 37);
            var result = arena.allocate(1);
            for (int i = 0; i < 80; i++) {
                if (i == 64) {
                    removeFirst.set(true);
                    require(firstClosed.await(5, TimeUnit.SECONDS), "Listener close deadline");
                    if (failed.get() != null) throw failed.get();
                }
                int client = client("10.77.0.1", 23100);
                write(client, data, 1); read(client, result, 1);
                require(result.get(JAVA_BYTE, 0) == 37, "Worker echo payload");
                close(client);
            }
            if (failed.get() != null) throw failed.get();
            require(accepted.get(0) > 0 && accepted.get(1) > 16, "VPP must distribute to both workers");
            System.out.println("{\"test\":\"multiworker\",\"accepted\":[" + accepted.get(0) + ","
                    + accepted.get(1) + "],\"connections_after_first_listener_close\":16}");
        } finally {
            stop.set(true);
            for (Thread thread : threads) thread.join(10000);
            for (Thread thread : threads) require(!thread.isAlive(), "Worker did not exit");
        }
        if (failed.get() != null) throw failed.get();
    }

    private void retention(Path ready, boolean interrupt, LivenessProbe monitor) throws Throwable {
        int[] pair = pair("10.77.0.1", 23200);
        var data = arena.allocateFrom("retained");
        write(pair[0], data, (int) data.byteSize());
        var ds = arena.allocate(Abi.SEGMENT, 16);
        segmentRead(pair[1], ds, (int) data.byteSize());
        var borrowed = ds.get(ADDRESS, 0).reinterpret(ds.get(JAVA_INT, 8));
        var exportedNio = borrowed.asByteBuffer();
        long address = borrowed.address();
        require(mapped(address), "RX segment initially unmapped");
        Files.writeString(ready, "retained\n");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        int query;
        do {
            poll(); query = c.detached();
            if (interrupt && query == 1) break;
        } while (System.nanoTime() - end < 0);
        boolean stillMapped = mapped(address);
        boolean monitorAlive = monitor.alive();
        require(!monitorAlive, "Independent SAPI monitor missed VPP exit");
        // Deliberately never dereference after disconnect or call free_segments on a detached FIFO.
        System.out.println("{\"test\":\"retention_after_vpp_exit\",\"detached_query\":" + query
                + ",\"rx_address_mapped\":" + stillMapped + ",\"nio_capacity\":" + exportedNio.capacity()
                + ",\"independent_monitor_alive\":" + monitorAlive + "}");
        if (interrupt) require(query == 1 && !stillMapped, "Reassess retained-segment unmapping gap");
        else require(query == -95, "Reassess poll detach query gap");
    }

    private static boolean mapped(long address) throws Exception {
        for (String line : Files.readAllLines(Path.of("/proc/self/maps"))) {
            String[] range = line.substring(0, line.indexOf(' ')).split("-");
            long low = Long.parseUnsignedLong(range[0], 16), high = Long.parseUnsignedLong(range[1], 16);
            if (Long.compareUnsigned(address, low) >= 0 && Long.compareUnsigned(address, high) < 0) return true;
        }
        return false;
    }

    private void bench() throws Throwable {
        int[] pair = pair("10.77.0.1", 23300);
        var buffer = arena.allocate(65536); buffer.fill((byte) 0x37);
        var dest = arena.allocate(65536);
        var ds = arena.allocate(Abi.SEGMENT, 16);
        for (int size : new int[]{64, 1024, 65536}) {
            for (boolean borrowed : new boolean[]{false, true}) {
                for (int repeat = -1; repeat < 3; repeat++) {
                    int count = repeat < 0 ? 2000 : 10000;
                    long checksum = 0, begin = System.nanoTime();
                    for (int i = 0; i < count; i++) {
                        write(pair[0], buffer, size);
                        if (borrowed) {
                            int remaining = size;
                            while (remaining > 0) {
                                ds.fill((byte) 0);
                                int n = segmentRead(pair[1], ds, remaining);
                                require(n > 0, "Zero progress segment read");
                                int left = n;
                                for (int j = 0; left > 0; j++) {
                                    int length = ds.get(JAVA_INT, j * 16L + 8);
                                    require(length > 0 && length <= left, "Invalid segment length");
                                    checksum += ds.get(ADDRESS, j * 16L).reinterpret(length).get(JAVA_BYTE, 0);
                                    left -= length;
                                }
                                c.free(pair[1], n); remaining -= n;
                            }
                        } else {
                            read(pair[1], dest, size); checksum += dest.get(JAVA_BYTE, 0);
                        }
                    }
                    long elapsed = System.nanoTime() - begin;
                    require(checksum >= (long) count * 0x37, "Benchmark checksum");
                    if (repeat >= 0) System.out.println("{\"test\":\"rx_microbench\",\"bytes\":" + size
                            + ",\"path\":\"" + (borrowed ? "borrow_immediate_release" : "native_copy")
                            + "\",\"repeat\":" + repeat + ",\"operations\":" + count
                            + ",\"elapsed_ns\":" + elapsed + ",\"checksum\":" + checksum + "}");
                }
            }
            var txSegments = arena.allocate(Abi.SEGMENT, 16);
            int chunk = size / 16;
            for (int j = 0; j < 16; j++) {
                txSegments.set(ADDRESS, j * 16L, buffer.asSlice((long) j * chunk));
                txSegments.set(JAVA_INT, j * 16L + 8, chunk);
            }
            for (String path : new String[]{"write_once", "write_16_calls", "gather_16_segments"}) {
                for (int repeat = -1; repeat < 3; repeat++) {
                    int count = repeat < 0 ? 2000 : 10000;
                    long checksum = 0, begin = System.nanoTime();
                    for (int i = 0; i < count; i++) {
                        switch (path) {
                            case "write_once" -> benchmarkWrite(pair[0], buffer, size);
                            case "write_16_calls" -> {
                                for (int j = 0; j < 16; j++) benchmarkWrite(pair[0], buffer.asSlice((long) j * chunk), chunk);
                            }
                            case "gather_16_segments" -> {
                                long end = deadline();
                                int rv;
                                while ((rv = c.gather(pair[0], txSegments, 16)) == -11) {
                                    poll(); checkDeadline(end);
                                }
                                require(rv == size, "Gather result=" + rv);
                            }
                            default -> throw new AssertionError(path);
                        }
                        read(pair[1], dest, size);
                        checksum += dest.get(JAVA_BYTE, 0);
                    }
                    long elapsed = System.nanoTime() - begin;
                    require(checksum == (long) count * 0x37, "TX benchmark checksum");
                    if (repeat >= 0) System.out.println("{\"test\":\"tx_microbench\",\"bytes\":" + size
                            + ",\"path\":\"" + path + "\",\"repeat\":" + repeat + ",\"operations\":" + count
                            + ",\"elapsed_ns\":" + elapsed + ",\"checksum\":" + checksum + "}");
                }
            }
        }
        for (int s : pair) close(s);
    }
    private void benchmarkWrite(int session, MemorySegment data, int length) throws Throwable {
        int offset = 0;
        long end = deadline();
        while (offset < length) {
            int rv = c.write(session, data.asSlice(offset), length - offset);
            if (rv > 0) offset += rv;
            else if (rv == -11 || rv == -107) poll();
            else throw new AssertionError("Benchmark write returned " + rv);
            checkDeadline(end);
        }
    }
    private void close(int s) throws Throwable {
        ok(c.close(s), "session_close"); sessions.remove(Integer.valueOf(s));
    }
    private void close() throws Throwable {
        Throwable failure = null;
        for (int i = sessions.size() - 1; i >= 0; i--) {
            try { ok(c.close(sessions.get(i)), "session_close"); }
            catch (Throwable t) { if (failure == null) failure = t; else failure.addSuppressed(t); }
        }
        sessions.clear();
        if (failure != null) throw failure;
    }
}

/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl.preflight;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

/** Test-only direct FFM downcalls. Library lifetime is the probe process lifetime. */
final class Calls {
    private static final Linker LINKER = Linker.nativeLinker();
    private final SymbolLookup library;
    private final MethodHandle createApp, destroyApp, register, unregister, worker, detached, mqFd;
    private final MethodHandle create, close, bind, listen, accept, connect, read, write, segments, free, gather;
    private final MethodHandle epollCreate, epollCtl, epollWait, recvfrom, sendto, sessionWorker;
    private final MethodHandle attr;

    Calls(Path path) {
        library = SymbolLookup.libraryLookup(path.toAbsolutePath(), Arena.global());
        attr = call("vppcom_session_attr", JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
        createApp = call("vppcom_app_create_with_config", JAVA_INT, ADDRESS);
        destroyApp = callVoid("vppcom_app_destroy");
        register = call("vppcom_worker_register", JAVA_INT);
        unregister = callVoid("vppcom_worker_unregister");
        worker = call("vppcom_worker_index", JAVA_INT);
        sessionWorker = call("vppcom_session_worker", JAVA_INT, JAVA_INT);
        detached = call("vppcom_worker_is_detached", JAVA_INT);
        mqFd = call("vppcom_worker_mqs_epfd", JAVA_INT);
        create = call("vppcom_session_create", JAVA_INT, JAVA_BYTE, JAVA_BYTE);
        close = call("vppcom_session_close", JAVA_INT, JAVA_INT);
        bind = call("vppcom_session_bind", JAVA_INT, JAVA_INT, ADDRESS);
        listen = call("vppcom_session_listen", JAVA_INT, JAVA_INT, JAVA_INT);
        accept = call("vppcom_session_accept", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
        connect = call("vppcom_session_connect", JAVA_INT, JAVA_INT, ADDRESS);
        read = call("vppcom_session_read", JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG);
        write = call("vppcom_session_write", JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG);
        segments = call("vppcom_session_read_segments", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
        free = callVoid("vppcom_session_free_segments", JAVA_INT, JAVA_INT);
        gather = call("vppcom_session_write_segments", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
        epollCreate = call("vppcom_epoll_create", JAVA_INT);
        epollCtl = call("vppcom_epoll_ctl", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
        epollWait = call("vppcom_epoll_wait", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_DOUBLE);
        recvfrom = call("vppcom_session_recvfrom", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);
        sendto = call("vppcom_session_sendto", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);
    }

    private MethodHandle call(String name, MemoryLayout result, MemoryLayout... args) {
        return LINKER.downcallHandle(library.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
    }
    private MethodHandle callVoid(String name, MemoryLayout... args) {
        return LINKER.downcallHandle(library.find(name).orElseThrow(), FunctionDescriptor.ofVoid(args));
    }
    int createApp(MemorySegment config) throws Throwable { return (int) createApp.invokeExact(config); }
    void destroyApp() throws Throwable { destroyApp.invokeExact(); }
    int register() throws Throwable { return (int) register.invokeExact(); }
    void unregister() throws Throwable { unregister.invokeExact(); }
    int worker() throws Throwable { return (int) worker.invokeExact(); }
    int sessionWorker(int s) throws Throwable { return (int) sessionWorker.invokeExact(s); }
    int available(int s) throws Throwable {
        return (int) attr.invokeExact(s, 0, MemorySegment.NULL, MemorySegment.NULL);
    }
    int detached() throws Throwable { return (int) detached.invokeExact(); }
    int mqFd() throws Throwable { return (int) mqFd.invokeExact(); }
    int create(int protocol) throws Throwable { return (int) create.invokeExact((byte) protocol, (byte) 1); }
    int close(int s) throws Throwable { return (int) close.invokeExact(s); }
    int bind(int s, MemorySegment ep) throws Throwable { return (int) bind.invokeExact(s, ep); }
    int listen(int s) throws Throwable { return (int) listen.invokeExact(s, 128); }
    int accept(int s, MemorySegment ep) throws Throwable { return (int) accept.invokeExact(s, ep, 2048); }
    int connect(int s, MemorySegment ep) throws Throwable { return (int) connect.invokeExact(s, ep); }
    int read(int s, MemorySegment data, long n) throws Throwable { return (int) read.invokeExact(s, data, n); }
    int write(int s, MemorySegment data, long n) throws Throwable { return (int) write.invokeExact(s, data, n); }
    int segments(int s, MemorySegment ds, int count, int max) throws Throwable {
        return (int) segments.invokeExact(s, ds, count, max);
    }
    void free(int s, int n) throws Throwable { free.invokeExact(s, n); }
    int gather(int s, MemorySegment ds, int count) throws Throwable { return (int) gather.invokeExact(s, ds, count); }
    int epollCreate() throws Throwable { return (int) epollCreate.invokeExact(); }
    int epollCtl(int e, int op, int s, MemorySegment event) throws Throwable {
        return (int) epollCtl.invokeExact(e, op, s, event);
    }
    int poll(int e, MemorySegment events) throws Throwable {
        return (int) epollWait.invokeExact(e, events, (int) (events.byteSize() / Abi.EPOLL.byteSize()), 0.0);
    }
    int recvfrom(int s, MemorySegment data, int n, MemorySegment ep) throws Throwable {
        return (int) recvfrom.invokeExact(s, data, n, 0, ep);
    }
    int sendto(int s, MemorySegment data, int n, MemorySegment ep) throws Throwable {
        return (int) sendto.invokeExact(s, data, n, 0, ep);
    }

    static MemorySegment config(Arena arena, String socket, boolean interrupt) {
        var c = arena.allocate(Abi.CONFIG);
        c.set(JAVA_LONG, Abi.offset(Abi.CONFIG, "heapsize"), 256L << 20);
        c.set(JAVA_INT, Abi.offset(Abi.CONFIG, "max_workers"), 8);
        c.set(JAVA_LONG, Abi.offset(Abi.CONFIG, "segment_size"), 64L << 20);
        c.set(JAVA_LONG, Abi.offset(Abi.CONFIG, "add_segment_size"), 32L << 20);
        c.set(JAVA_INT, Abi.offset(Abi.CONFIG, "preallocated_fifo_pairs"), 8);
        c.set(JAVA_INT, Abi.offset(Abi.CONFIG, "rx_fifo_size"), 1 << 20);
        c.set(JAVA_INT, Abi.offset(Abi.CONFIG, "tx_fifo_size"), 1 << 20);
        c.set(JAVA_INT, Abi.offset(Abi.CONFIG, "event_queue_size"), 2048);
        c.set(JAVA_BYTE, Abi.offset(Abi.CONFIG, "app_scope_global"), (byte) 1);
        c.set(JAVA_BYTE, Abi.offset(Abi.CONFIG, "app_scope_local"), (byte) 1);
        c.set(JAVA_BYTE, Abi.offset(Abi.CONFIG, "use_mq_eventfd"), (byte) (interrupt ? 1 : 0));
        c.set(JAVA_DOUBLE, Abi.offset(Abi.CONFIG, "app_timeout"), 5.0);
        c.set(JAVA_DOUBLE, Abi.offset(Abi.CONFIG, "session_timeout"), 5.0);
        c.set(ADDRESS, Abi.offset(Abi.CONFIG, "vpp_app_socket_api"), arena.allocateFrom(socket));
        c.set(ADDRESS, Abi.offset(Abi.CONFIG, "app_name"), arena.allocateFrom("java26-preflight"));
        return c;
    }

    static MemorySegment endpoint(Arena arena, String address, int port) throws Exception {
        var ep = arena.allocate(Abi.ENDPOINT);
        var ip = arena.allocate(16);
        byte[] bytes = java.net.InetAddress.getByName(address).getAddress();
        MemorySegment.copy(bytes, 0, ip, JAVA_BYTE, 0, bytes.length);
        ep.set(JAVA_BYTE, Abi.offset(Abi.ENDPOINT, "is_ip4"), (byte) (bytes.length == 4 ? 1 : 0));
        ep.set(ADDRESS, Abi.offset(Abi.ENDPOINT, "ip"), ip);
        ep.set(JAVA_SHORT.withOrder(java.nio.ByteOrder.BIG_ENDIAN), Abi.offset(Abi.ENDPOINT, "port"), (short) port);
        return ep;
    }
}

/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

/** Fixed typed FFM downcalls for the pinned VPP 26.10 ABI. Library lifetime is the process lifetime. */
final class NativeCalls {
    private static final java.util.concurrent.ConcurrentMap<Path, NativeCalls> LIBRARIES =
            new java.util.concurrent.ConcurrentHashMap<>();
    static NativeCalls load(Path path) {
        // A global Arena never unloads libraries. Reuse its lookup/handles across
        // runtime lifetimes instead of accumulating dlopen references each time.
        return LIBRARIES.computeIfAbsent(path.toAbsolutePath().normalize(), NativeCalls::new);
    }
    private static final Linker LINKER = Linker.nativeLinker();
    private final SymbolLookup library;
    private final MethodHandle createApp, destroyApp, register, unregister, worker, mqFd;
    private final MethodHandle create, close, bind, listen, accept, connect, read, write;
    private final MethodHandle epollCreate, epollCtl, epollWait, recvfrom, sendto;
    private final MethodHandle attr, shutdown, sessionError;

    private NativeCalls(Path path) {
        library = SymbolLookup.libraryLookup(path.toAbsolutePath(), Arena.global());
        sessionError = call("vppcom_session_get_error", JAVA_INT, JAVA_INT);
        shutdown = call("vppcom_session_shutdown", JAVA_INT, JAVA_INT, JAVA_INT);
        attr = call("vppcom_session_attr", JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
        createApp = call("vppcom_app_create_with_config", JAVA_INT, ADDRESS);
        destroyApp = callVoid("vppcom_app_destroy");
        register = call("vppcom_worker_register", JAVA_INT);
        unregister = callVoid("vppcom_worker_unregister");
        worker = call("vppcom_worker_index", JAVA_INT);
        mqFd = call("vppcom_worker_mqs_epfd", JAVA_INT);
        create = call("vppcom_session_create", JAVA_INT, JAVA_BYTE, JAVA_BYTE);
        close = call("vppcom_session_close", JAVA_INT, JAVA_INT);
        bind = call("vppcom_session_bind", JAVA_INT, JAVA_INT, ADDRESS);
        listen = call("vppcom_session_listen", JAVA_INT, JAVA_INT, JAVA_INT);
        accept = call("vppcom_session_accept", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
        connect = call("vppcom_session_connect", JAVA_INT, JAVA_INT, ADDRESS);
        read = call("vppcom_session_read", JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG);
        write = call("vppcom_session_write", JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG);
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
    int sessionError(int s) throws Throwable { return (int) sessionError.invokeExact(s); }
    int worker() throws Throwable { return (int) worker.invokeExact(); }
    int shutdown(int s, int how) throws Throwable { return (int) shutdown.invokeExact(s, how); }
    int attr(int s, int op, MemorySegment value, MemorySegment length) throws Throwable {
        return (int) attr.invokeExact(s, op, value, length);
    }
    int available(int s) throws Throwable {
        return (int) attr.invokeExact(s, 0, MemorySegment.NULL, MemorySegment.NULL);
    }
    int mqFd() throws Throwable { return (int) mqFd.invokeExact(); }
    int create(int protocol) throws Throwable { return (int) create.invokeExact((byte) protocol, (byte) 1); }
    int close(int s) throws Throwable { return (int) close.invokeExact(s); }
    int bind(int s, MemorySegment ep) throws Throwable { return (int) bind.invokeExact(s, ep); }
    int listen(int s, int backlog) throws Throwable { return (int) listen.invokeExact(s, backlog); }
    int accept(int s, MemorySegment ep) throws Throwable { return (int) accept.invokeExact(s, ep, 2048); }
    int connect(int s, MemorySegment ep) throws Throwable { return (int) connect.invokeExact(s, ep); }
    int read(int s, MemorySegment data, long n) throws Throwable { return (int) read.invokeExact(s, data, n); }
    int write(int s, MemorySegment data, long n) throws Throwable { return (int) write.invokeExact(s, data, n); }
    int epollCreate() throws Throwable { return (int) epollCreate.invokeExact(); }
    int epollCtl(int e, int op, int s, MemorySegment event) throws Throwable {
        return (int) epollCtl.invokeExact(e, op, s, event);
    }
    int poll(int e, MemorySegment events) throws Throwable {
        return (int) epollWait.invokeExact(e, events, (int) (events.byteSize() / VclAbi.EPOLL.byteSize()), 0.0);
    }
    int recvfrom(int s, MemorySegment data, int n, MemorySegment ep) throws Throwable {
        return (int) recvfrom.invokeExact(s, data, n, 0, ep);
    }
    int sendto(int s, MemorySegment data, int n, MemorySegment ep) throws Throwable {
        return (int) sendto.invokeExact(s, data, n, 0, ep);
    }

    static MemorySegment config(Arena arena, String socket, boolean interrupt, int maxWorkers) {
        var c = arena.allocate(VclAbi.CONFIG);
        c.set(JAVA_LONG, VclAbi.offset(VclAbi.CONFIG, "heapsize"), 256L << 20);
        c.set(JAVA_INT, VclAbi.offset(VclAbi.CONFIG, "max_workers"), maxWorkers);
        c.set(JAVA_LONG, VclAbi.offset(VclAbi.CONFIG, "segment_size"), 64L << 20);
        c.set(JAVA_LONG, VclAbi.offset(VclAbi.CONFIG, "add_segment_size"), 32L << 20);
        c.set(JAVA_INT, VclAbi.offset(VclAbi.CONFIG, "preallocated_fifo_pairs"), 8);
        c.set(JAVA_INT, VclAbi.offset(VclAbi.CONFIG, "rx_fifo_size"), 1 << 20);
        c.set(JAVA_INT, VclAbi.offset(VclAbi.CONFIG, "tx_fifo_size"), 1 << 20);
        c.set(JAVA_INT, VclAbi.offset(VclAbi.CONFIG, "event_queue_size"), 2048);
        c.set(JAVA_BYTE, VclAbi.offset(VclAbi.CONFIG, "app_scope_global"), (byte) 1);
        c.set(JAVA_BYTE, VclAbi.offset(VclAbi.CONFIG, "app_scope_local"), (byte) 1);
        c.set(JAVA_BYTE, VclAbi.offset(VclAbi.CONFIG, "use_mq_eventfd"), (byte) (interrupt ? 1 : 0));
        c.set(JAVA_DOUBLE, VclAbi.offset(VclAbi.CONFIG, "app_timeout"), 5.0);
        c.set(JAVA_DOUBLE, VclAbi.offset(VclAbi.CONFIG, "session_timeout"), 5.0);
        c.set(ADDRESS, VclAbi.offset(VclAbi.CONFIG, "vpp_app_socket_api"), arena.allocateFrom(socket));
        c.set(ADDRESS, VclAbi.offset(VclAbi.CONFIG, "app_name"), arena.allocateFrom("me.wineway.vcl"));
        return c;
    }

}

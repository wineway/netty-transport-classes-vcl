package dev.vcl.netty;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.*;

/** Direct FFM bindings. ABI: VPP 25.10, Linux x86_64, little endian. No JNI or C shim. */
final class NativeVcl {
    static final int AGAIN = -11, IN_PROGRESS = -115;
    static final int IN = 1, OUT = 4, ERR = 8, HUP = 16, RDHUP = 0x2000;
    static final int ADD = 1, DEL = 2, MOD = 3;
    static final int GET_LOCAL = 4, GET_PEER = 6;
    static final MemoryLayout ENDPOINT = MemoryLayout.structLayout(
            JAVA_BYTE.withName("unused"), JAVA_BYTE.withName("is_ip4"), MemoryLayout.paddingLayout(6),
            ADDRESS.withName("ip"), JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN).withName("port"),
            MemoryLayout.paddingLayout(6), JAVA_LONG.withName("unused2"), JAVA_INT.withName("app_tlv_len"),
            MemoryLayout.paddingLayout(4), ADDRESS.withName("app_tlvs"));
    // Linux x86_64's epoll_event is packed: u32 events; u64 data; sizeof=12.
    static final MemoryLayout EPOLL_EVENT = MemoryLayout.structLayout(
            JAVA_INT.withName("events"), JAVA_LONG_UNALIGNED.withName("data"));
    static final MemoryLayout POLLFD = MemoryLayout.structLayout(
            JAVA_INT.withName("fd"), JAVA_SHORT.withName("events"), JAVA_SHORT.withName("revents"));

    private final Linker linker = Linker.nativeLinker();
    private final SymbolLookup vcl;
    private final SymbolLookup libc = linker.defaultLookup();
    private final ThreadLocal<MemorySegment> callState = ThreadLocal.withInitial(
            () -> Arena.ofAuto().allocate(Linker.Option.captureStateLayout()));
    private static final long ERRNO_OFFSET = Linker.Option.captureStateLayout().byteOffset(MemoryLayout.PathElement.groupElement("errno"));
    private final MethodHandle appCreate, appDestroy, workerRegister, workerUnregister, workerIndex, mqFd, detached;
    private final MethodHandle create, bind, listen, accept, connect, read, write, close, shutdown, attr, error;
    private final MethodHandle epollCreate, epollCtl, epollWait;
    private final MethodHandle eventfd, poll, osRead, osWrite, osClose;

    NativeVcl() {
        requirePlatform();
        String path = System.getProperty("vcl.library");
        vcl = path == null ? SymbolLookup.libraryLookup("libvppcom.so.25.10", Arena.global())
                : SymbolLookup.libraryLookup(Path.of(path), Arena.global());
        appCreate = v("vppcom_app_create", JAVA_INT, ADDRESS);
        appDestroy = v("vppcom_app_destroy", null);
        workerRegister = v("vppcom_worker_register", JAVA_INT);
        workerUnregister = v("vppcom_worker_unregister", null);
        workerIndex = v("vppcom_worker_index", JAVA_INT);
        mqFd = v("vppcom_worker_mqs_epfd", JAVA_INT);
        detached = v("vppcom_worker_is_detached", JAVA_INT);
        create = v("vppcom_session_create", JAVA_INT, JAVA_BYTE, JAVA_BYTE);
        bind = v("vppcom_session_bind", JAVA_INT, JAVA_INT, ADDRESS);
        listen = v("vppcom_session_listen", JAVA_INT, JAVA_INT, JAVA_INT);
        accept = v("vppcom_session_accept", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
        connect = v("vppcom_session_connect", JAVA_INT, JAVA_INT, ADDRESS);
        read = v("vppcom_session_read", JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG);
        write = v("vppcom_session_write", JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG);
        close = v("vppcom_session_close", JAVA_INT, JAVA_INT);
        shutdown = v("vppcom_session_shutdown", JAVA_INT, JAVA_INT, JAVA_INT);
        attr = v("vppcom_session_attr", JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
        error = v("vppcom_session_get_error", JAVA_INT, JAVA_INT);
        epollCreate = v("vppcom_epoll_create", JAVA_INT);
        epollCtl = v("vppcom_epoll_ctl", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
        epollWait = v("vppcom_epoll_wait", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_DOUBLE);
        eventfd = c("eventfd", JAVA_INT, JAVA_INT, JAVA_INT);
        poll = c("poll", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT);
        osRead = c("read", JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG);
        osWrite = c("write", JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG);
        osClose = c("close", JAVA_INT, JAVA_INT);
    }

    static void requirePlatform() {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).equals("linux")
                || !(System.getProperty("os.arch").equals("amd64") || System.getProperty("os.arch").equals("x86_64"))
                || ADDRESS.byteSize() != 8 || ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new UnsupportedOperationException("VCL ABI requires Linux x86_64 little endian");
        }
    }

    private MethodHandle v(String name, MemoryLayout result, MemoryLayout... args) {
        return function(vcl, name, result, args);
    }
    private MethodHandle c(String name, MemoryLayout result, MemoryLayout... args) {
        return linker.downcallHandle(libc.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)),
                FunctionDescriptor.of(result, args), Linker.Option.captureCallState("errno"));
    }
    private MethodHandle function(SymbolLookup lookup, String name, MemoryLayout result, MemoryLayout... args) {
        FunctionDescriptor fd = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
        return linker.downcallHandle(lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), fd);
    }
    private static int call(MethodHandle function, Object... args) {
        try { return (int) function.invokeWithArguments(args); }
        catch (Throwable e) { throw bindingFailure(e); }
    }
    private static void callVoid(MethodHandle function) {
        try { function.invokeExact(); }
        catch (Throwable e) { throw bindingFailure(e); }
    }
    private static RuntimeException bindingFailure(Throwable e) {
        if (e instanceof Error error) throw error;
        return e instanceof RuntimeException r ? r : new IllegalStateException("FFM invocation failed", e);
    }

    int appCreate(String name) {
        try (Arena arena = Arena.ofConfined()) { return call(appCreate, arena.allocateFrom(name)); }
    }
    void appDestroy() { callVoid(appDestroy); }
    int workerRegister() { return call(workerRegister); }
    void workerUnregister() { callVoid(workerUnregister); }
    int workerIndex() { return call(workerIndex); }
    int mqFd() { return call(mqFd); }
    boolean detached() { return call(detached) != 0; }
    int create() { return call(create, (byte) 0, (byte) 1); }
    int listen(int sh, int backlog) { return call(listen, sh, backlog); }
    int close(int sh) { return call(close, sh); }
    int shutdown(int sh, int how) { return call(shutdown, sh, how); }
    int error(int sh) { return call(error, sh); }
    int epollCreate() { return call(epollCreate); }
    int epollCtl(int epoll, int op, int sh, MemorySegment event) { return call(epollCtl, epoll, op, sh, event); }
    int accept(int sh) { return call(accept, sh, MemorySegment.NULL, 0x800 /* O_NONBLOCK */); }

    int bind(int sh, InetSocketAddress address) {
        try (Arena arena = Arena.ofConfined()) { return call(bind, sh, endpoint(arena, address)); }
    }
    int connect(int sh, InetSocketAddress address) {
        try (Arena arena = Arena.ofConfined()) { return call(connect, sh, endpoint(arena, address)); }
    }
    int read(int sh, MemorySegment buffer, long length) {
        try { return (int) read.invokeExact(sh, buffer, length); }
        catch (Throwable e) { throw bindingFailure(e); }
    }
    int write(int sh, MemorySegment buffer, long length) {
        try { return (int) write.invokeExact(sh, buffer, length); }
        catch (Throwable e) { throw bindingFailure(e); }
    }
    int epollWait(int epoll, MemorySegment events, int maxEvents) {
        try { return (int) epollWait.invokeExact(epoll, events, maxEvents, 0.0); }
        catch (Throwable e) { throw bindingFailure(e); }
    }
    InetSocketAddress address(int sh, boolean peer) throws VclException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ep = endpoint(arena, new InetSocketAddress("0.0.0.0", 0));
            MemorySegment size = arena.allocateFrom(JAVA_INT, (int) ENDPOINT.byteSize());
            VclException.check("get address", call(attr, sh, peer ? GET_PEER : GET_LOCAL, ep, size));
            byte[] ip = ep.get(ADDRESS, 8).reinterpret(4).toArray(JAVA_BYTE);
            try { return new InetSocketAddress(Inet4Address.getByAddress(ip), Short.toUnsignedInt(ep.get(JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN), 16))); }
            catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
        }
    }
    static MemorySegment endpoint(Arena arena, InetSocketAddress address) {
        if (address.isUnresolved() || !(address.getAddress() instanceof Inet4Address)) {
            throw new IllegalArgumentException("MVP requires a resolved IPv4 address: " + address);
        }
        MemorySegment ep = arena.allocate(ENDPOINT);
        ep.set(JAVA_BYTE, 1, (byte) 1);
        ep.set(ADDRESS, 8, arena.allocateFrom(JAVA_BYTE, address.getAddress().getAddress()));
        ep.set(JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN), 16, (short) address.getPort());
        return ep;
    }
    int eventfd() { return call(eventfd, callState.get(), 0, 0x800 | 0x80000 /* NONBLOCK | CLOEXEC */); }
    int osClose(int fd) { return call(osClose, callState.get(), fd); }
    int poll(MemorySegment fds, int timeout) {
        return poll(fds, 2L, timeout);
    }
    int poll(MemorySegment fds, long count, int timeout) {
        try { return (int) poll.invokeExact(callState.get(), fds, count, timeout); }
        catch (Throwable e) { throw bindingFailure(e); }
    }
    long osRead(int fd, MemorySegment word) {
        try { return (long) osRead.invokeExact(callState.get(), fd, word, 8L); }
        catch (Throwable e) { throw bindingFailure(e); }
    }
    long osWrite(int fd, MemorySegment word) {
        try { return (long) osWrite.invokeExact(callState.get(), fd, word, 8L); }
        catch (Throwable e) { throw bindingFailure(e); }
    }
    int errno() { return callState.get().get(JAVA_INT, ERRNO_OFFSET); }
}

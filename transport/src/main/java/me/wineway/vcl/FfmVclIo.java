/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import java.lang.foreign.*;
import java.net.*;
import java.nio.*;
import static java.lang.foreign.ValueLayout.*;
import static me.wineway.vcl.VclException.check;

/** Owns one VCL worker, its epoll session and reusable native structures. */
final class FfmVclIo implements VclIo {
    private final NativeCalls calls;
    private final Thread owner = Thread.currentThread();
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment endpoint = arena.allocate(VclAbi.ENDPOINT);
    private final MemorySegment address = arena.allocate(16);
    private final MemorySegment length = arena.allocate(JAVA_INT);
    private final MemorySegment event = arena.allocate(VclAbi.EPOLL);
    private final MemorySegment events = arena.allocate(12 * 256, 1);
    private LinuxWaiter waiter;
    private int epoll = -1;
    private boolean registered, closed, failed;

    FfmVclIo(NativeCalls calls, VclMode mode, String socket) throws Exception {
        this.calls = calls;
        try {
            if (calls.worker() != -1) throw new IllegalStateException("EventLoop already has a VCL worker");
            int result = calls.register();
            // VCL can allocate TLS/pool state before SAPI registration fails.
            registered = calls.worker() != -1;
            check("worker_register", result);
            epoll = VclException.handle("epoll_create", calls.epollCreate());
            waiter = new LinuxWaiter(mode, socket, mode == VclMode.INTERRUPT ? check("mq_fd", calls.mqFd()) : -1);
            endpoint.set(ADDRESS, VclAbi.offset(VclAbi.ENDPOINT, "ip"), address);
        } catch (Throwable failure) {
            try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw VclException.translate(failure);
        }
    }
    private void owner() {
        if (Thread.currentThread() != owner || closed) throw new IllegalStateException("VCL worker is closed or on another thread");
    }
    private void usable() throws java.io.IOException {
        owner();
        if (failed) throw new java.io.IOException("VCL worker failed; native session I/O is disabled");
    }
    private void endpoint(InetSocketAddress socket) {
        if (socket.isUnresolved()) throw new java.nio.channels.UnresolvedAddressException();
        byte[] bytes = socket.getAddress().getAddress();
        address.fill((byte) 0);
        MemorySegment.copy(bytes, 0, address, JAVA_BYTE, 0, bytes.length);
        endpoint.set(JAVA_BYTE, VclAbi.offset(VclAbi.ENDPOINT, "is_ip4"), (byte) (bytes.length == 4 ? 1 : 0));
        endpoint.set(JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN), VclAbi.offset(VclAbi.ENDPOINT, "port"), (short) socket.getPort());
    }
    private InetSocketAddress endpoint() throws UnknownHostException {
        int bytes = endpoint.get(JAVA_BYTE, VclAbi.offset(VclAbi.ENDPOINT, "is_ip4")) != 0 ? 4 : 16;
        return new InetSocketAddress(InetAddress.getByAddress(address.asSlice(0, bytes).toArray(JAVA_BYTE)),
                Short.toUnsignedInt(endpoint.get(JAVA_SHORT.withOrder(ByteOrder.BIG_ENDIAN), VclAbi.offset(VclAbi.ENDPOINT, "port"))));
    }
    private static MemorySegment buffer(ByteBuffer buffer) {
        if (!buffer.isDirect()) throw new IllegalArgumentException("FFM I/O requires direct buffer");
        return MemorySegment.ofBuffer(buffer);
    }
    @Override public int create(boolean datagram) throws Exception {
        usable(); try { return VclException.handle("session_create", calls.create(datagram ? 1 : 0)); }
        catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public void closeSession(int session) throws Exception {
        owner(); if (failed) return;
        try { check("session_close", calls.close(session)); }
        catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public void bind(int session, InetSocketAddress socket) throws Exception {
        usable(); endpoint(socket); try { check("bind", calls.bind(session, endpoint)); }
        catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public void listen(int session, int backlog) throws Exception {
        usable(); try { check("listen", calls.listen(session, backlog)); }
        catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public Accepted accept(int session) throws Exception {
        usable(); try {
            int ready = calls.available(session);
            if (ready == 0 || ready == AGAIN) return null;
            check("accept_ready", ready);
            int child = calls.accept(session, endpoint);
            if (child == AGAIN) return null;
            VclException.handle("accept", child);
            try { return new Accepted(child, endpoint()); }
            catch (Throwable failure) { calls.close(child); throw failure; }
        } catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public boolean connect(int session, InetSocketAddress remote) throws Exception {
        usable(); endpoint(remote); try {
            int rv = calls.connect(session, endpoint);
            if (rv == IN_PROGRESS || rv == -114) return false;
            if (rv < 0) throw connectFailure(session, rv);
            return true;
        } catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public boolean finishConnect(int session) throws Exception {
        usable(); try {
            // GET_ERROR is a zero-returning stub in 26.10. GET_NWRITE validates session readiness.
            int rv = calls.attr(session, 1, MemorySegment.NULL, MemorySegment.NULL);
            if (rv < 0) throw connectFailure(session, rv);
            return rv > 0;
        } catch (Throwable t) { throw VclException.translate(t); }
    }
    private ConnectException connectFailure(int session, int result) throws Throwable {
        // Unlike ATTR_GET_ERROR, this public API reports VPP's connection error.
        int detail = calls.sessionError(session);
        if (detail < 0) result = detail;
        var failure = new ConnectException(result == -111 ? "Connection refused" : "VCL connect failed: " + result);
        failure.initCause(new VclException("connect", result));
        return failure;
    }
    @Override public InetSocketAddress localAddress(int session) throws Exception {
        usable(); try {
            length.set(JAVA_INT, 0, (int) VclAbi.ENDPOINT.byteSize());
            check("local_address", calls.attr(session, 4, endpoint, length));
            return endpoint();
        } catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public int read(int session, ByteBuffer destination) throws Exception {
        usable(); try {
            int rv = calls.read(session, buffer(destination), (long) destination.remaining());
            return rv == AGAIN ? AGAIN : check("read", rv);
        } catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public int write(int session, ByteBuffer source) throws Exception {
        usable(); try {
            int rv = calls.write(session, buffer(source), (long) source.remaining());
            return rv == AGAIN ? 0 : check("write", rv);
        } catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public Received receive(int session, ByteBuffer destination) throws Exception {
        usable(); try {
            int rv = calls.recvfrom(session, buffer(destination), destination.remaining(), endpoint);
            if (rv == AGAIN) return null;
            check("recvfrom", rv);
            return new Received(rv, endpoint());
        } catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public int send(int session, ByteBuffer source, InetSocketAddress recipient) throws Exception {
        usable(); if (recipient != null) endpoint(recipient);
        try {
            int rv = calls.sendto(session, buffer(source), source.remaining(), recipient == null ? MemorySegment.NULL : endpoint);
            return rv == AGAIN ? 0 : check("sendto", rv);
        } catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public void shutdown(int session, int how) throws Exception {
        usable(); try { check("shutdown", calls.shutdown(session, how)); }
        catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public void interest(int session, long token, int flags, boolean add) throws Exception {
        usable(); event.set(JAVA_INT_UNALIGNED, 0, flags);
        event.set(JAVA_LONG_UNALIGNED, 4, token);
        try { check("epoll_ctl", calls.epollCtl(epoll, add ? 1 : 3, session, event)); }
        catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public void remove(int session) throws Exception {
        usable(); try { check("epoll_del", calls.epollCtl(epoll, 2, session, MemorySegment.NULL)); }
        catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public int poll(long[] tokens, int[] flags) throws Exception {
        usable(); try {
            int n = check("epoll_wait", calls.poll(epoll, events.asSlice(0, Math.min(256, tokens.length) * 12L)));
            for (int i = 0; i < n; i++) {
                tokens[i] = events.get(JAVA_LONG_UNALIGNED, i * 12L + 4);
                flags[i] = events.get(JAVA_INT_UNALIGNED, i * 12L);
            }
            return n;
        } catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public boolean alive() throws Exception { usable(); return waiter.alive(); }
    @Override public boolean await(long nanos) throws Exception { usable(); return waiter.await(nanos); }
    @Override public void wakeup() {
        var current = waiter;
        if (current != null) try { current.wakeup(); } catch (java.io.IOException e) { throw new io.netty.channel.ChannelException(e); }
    }
    @Override public void failed(Throwable failure) { owner(); failed = true; }
    @Override public void close() throws Exception {
        if (closed) return;
        owner();
        Throwable failure = null;
        try { if (waiter != null) waiter.close(); } catch (Throwable t) { failure = t; }
        try { if (epoll != -1 && !failed) calls.close(epoll); } catch (Throwable t) { if (failure == null) failure = t; else failure.addSuppressed(t); }
        try { if (registered) calls.unregister(); } catch (Throwable t) { if (failure == null) failure = t; else failure.addSuppressed(t); }
        closed = true;
        arena.close();
        if (failure != null) throw VclException.translate(failure);
    }
}

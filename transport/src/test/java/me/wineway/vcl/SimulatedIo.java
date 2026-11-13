/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

/** Deterministic Java test peer. These tests do not validate native VPP semantics. */
final class SimulatedIo implements VclIo {
    static final class Session {
        InetSocketAddress local, peer;
        final Deque<ByteBuffer> input = new ArrayDeque<>();
        final Deque<ReceivedData> datagrams = new ArrayDeque<>();
        final Deque<Accepted> accepted = new ArrayDeque<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean eof, connecting, listener, inputClosed, outputClosed;
        int interests;
        long token;
    }
    record ReceivedData(byte[] data, InetSocketAddress sender) { }
    final Map<Integer, Session> sessions = new LinkedHashMap<>();
    final Thread owner = Thread.currentThread();
    int next = 1, closedSessions, maxWrite = Integer.MAX_VALUE;
    boolean closed, delayConnect, rejectBind, failConnect, transientConnectHup;
    volatile boolean alive = true;
    boolean writable = true;
    int polls, awaits;
    long staleToken;
    private void owner() { if (Thread.currentThread() != owner) throw new AssertionError("worker thread changed"); }
    private Session session(int id) { owner(); return Objects.requireNonNull(sessions.get(id), "unknown session"); }
    @Override public int create(boolean datagram) { owner(); sessions.put(next, new Session()); return next++; }
    @Override public void closeSession(int id) { owner(); if (sessions.remove(id) == null) throw new AssertionError("duplicate session close"); closedSessions++; }
    @Override public void bind(int id, InetSocketAddress address) throws Exception {
        if (rejectBind) throw new BindException("injected bind failure");
        session(id).local = new InetSocketAddress(address.getAddress(), address.getPort() == 0 ? 32000 : address.getPort());
    }
    @Override public void listen(int id, int backlog) { session(id).listener = true; }
    @Override public Accepted accept(int id) { return session(id).accepted.poll(); }
    @Override public boolean connect(int id, InetSocketAddress remote) {
        var s = session(id); s.peer = remote; s.connecting = delayConnect;
        if (s.local == null) s.local = new InetSocketAddress("127.0.0.1", 33000 + (id & 255));
        return !delayConnect;
    }
    @Override public boolean finishConnect(int id) throws Exception {
        if (failConnect) throw new ConnectException("injected refusal");
        return !session(id).connecting;
    }
    @Override public InetSocketAddress localAddress(int id) { return session(id).local; }
    @Override public int read(int id, ByteBuffer destination) {
        var s = session(id); ByteBuffer head = s.input.peek();
        if (head == null) return s.eof ? 0 : AGAIN;
        int n = Math.min(destination.remaining(), head.remaining());
        destination.put(head.slice(head.position(), n)); head.position(head.position() + n);
        if (!head.hasRemaining()) s.input.remove(); return n;
    }
    @Override public int write(int id, ByteBuffer source) {
        var s = session(id); if (!writable) return 0;
        if (s.outputClosed) throw new AssertionError("write after output shutdown");
        int n = Math.min(maxWrite, source.remaining()); byte[] bytes = new byte[n]; source.get(bytes); s.output.writeBytes(bytes); return n;
    }
    @Override public Received receive(int id, ByteBuffer destination) {
        var packet = session(id).datagrams.poll(); if (packet == null) return null;
        int n = Math.min(destination.remaining(), packet.data.length); destination.put(packet.data, 0, n); return new Received(n, packet.sender);
    }
    @Override public int send(int id, ByteBuffer source, InetSocketAddress recipient) { if (session(id).local == null) session(id).local = new InetSocketAddress("127.0.0.1", 35000 + (id & 255)); return write(id, source); }
    @Override public void shutdown(int id, int how) {
        if (how == 0 || how == 2) session(id).inputClosed = true;
        if (how == 1 || how == 2) session(id).outputClosed = true;
    }
    @Override public void interest(int id, long token, int flags, boolean add) {
        var s = session(id); s.token = token; s.interests = flags;
    }
    @Override public void remove(int id) { session(id).token = 0; }
    @Override public int poll(long[] tokens, int[] flags) {
        owner(); polls++;
        int n = 0;
        if (staleToken != 0) { tokens[n] = staleToken; flags[n++] = ERROR; staleToken = 0; }
        for (var s : sessions.values()) {
            if (n >= tokens.length) break;
            int ready = 0;
            if ((s.interests & READ) != 0 && (!s.input.isEmpty() || !s.datagrams.isEmpty() || !s.accepted.isEmpty() || s.eof)) ready |= READ;
            if ((s.interests & WRITE) != 0 && writable && !s.connecting) ready |= WRITE;
            if (s.connecting && transientConnectHup) { ready |= HUP; transientConnectHup = false; }
            if (s.connecting && failConnect) ready |= ERROR;
            if (ready != 0 && s.token != 0) { tokens[n] = s.token; flags[n++] = ready; }
        }
        return n;
    }
    @Override public boolean await(long nanos) { owner(); awaits++; LockSupport.parkNanos(Math.max(1, nanos)); return alive; }
    @Override public boolean alive() { owner(); return alive; }
    @Override public void wakeup() { LockSupport.unpark(owner); }
    @Override public void close() { owner(); if (!sessions.isEmpty()) throw new AssertionError("sessions leaked: " + sessions.size()); closed = true; }
}

package dev.vcl.netty;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/** M0 executable: real VCL multi-worker echo, without Netty or any project C library. */
public final class VclProbe {
    private VclProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalArgumentException("server IP PORT WORKERS CONNECTIONS | client IP PORT CONNECTIONS");
        InetSocketAddress address = new InetSocketAddress(args[1], Integer.parseInt(args[2]));
        try (VclRuntime runtime = VclRuntime.open("netty-vcl-probe-" + args[0])) {
            if (args[0].equals("server")) server(runtime, address, Integer.parseInt(args[3]), Integer.parseInt(args[4]));
            else if (args[0].equals("client")) client(runtime, address, Integer.parseInt(args[3]));
            else throw new IllegalArgumentException("Unknown mode: " + args[0]);
        }
    }

    private static void server(VclRuntime runtime, InetSocketAddress address, int workers, int expected) throws Exception {
        if (workers < 2 || expected < workers) throw new IllegalArgumentException("M0 needs >=2 workers and >=workers connections");
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        AtomicInteger total = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(workers);
        List<Future<Integer>> results = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try {
            for (int n = 0; n < workers; n++) results.add(executor.submit(() -> {
                try (VclPoller poller = new VclPoller(runtime); Arena arena = Arena.ofConfined()) {
                    NativeVcl api = poller.api;
                    int listener = VclException.check("create listener", api.create());
                    Map<Long, Connection> sessions = new HashMap<>();
                    int completed = 0;
                    long nextToken = 1;
                    try {
                        VclException.check("bind", api.bind(listener, address));
                        VclException.check("listen", api.listen(listener, 128));
                        poller.control(NativeVcl.ADD, listener, NativeVcl.IN, 0);
                        ready.countDown();
                        if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("listener startup timeout");
                        System.out.println("LISTEN worker=" + poller.workerIndex);
                        while (total.get() < expected && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                            int events = poller.poll(10);
                            for (int i = 0; i < events; i++) {
                                long token = poller.token(i);
                                if (token == 0) {
                                    for (;;) {
                                        int sh = api.accept(listener);
                                        if (sh == NativeVcl.AGAIN) break;
                                        VclException.check("accept", sh);
                                        Connection connection = new Connection(sh, arena.allocate(64));
                                        sessions.put(nextToken, connection);
                                        poller.control(NativeVcl.ADD, sh, NativeVcl.IN, nextToken++);
                                    }
                                } else {
                                    Connection connection = sessions.get(token);
                                    if (connection == null) continue;
                                    if (connection.read < 64) {
                                        int read = api.read(connection.sh, connection.data.asSlice(connection.read), 64 - connection.read);
                                        if (read == 0) throw new IllegalStateException("unexpected EOF");
                                        if (read != NativeVcl.AGAIN) connection.read += VclException.check("read", read);
                                    }
                                    if (connection.read == 64) {
                                        int wrote = api.write(connection.sh, connection.data.asSlice(connection.written), 64 - connection.written);
                                        if (wrote != NativeVcl.AGAIN) connection.written += VclException.check("write", wrote);
                                        if (connection.written == 64) {
                                            poller.control(NativeVcl.DEL, connection.sh, 0, token);
                                            api.close(connection.sh); sessions.remove(token);
                                            completed++; total.incrementAndGet();
                                        } else poller.control(NativeVcl.MOD, connection.sh, NativeVcl.OUT, token);
                                    }
                                }
                            }
                        }
                        System.out.println("COMPLETE worker=" + poller.workerIndex + " connections=" + completed);
                        if (completed == 0) throw new IllegalStateException("worker received no connections");
                        return completed;
                    } finally {
                        for (Connection c : sessions.values()) api.close(c.sh);
                        api.close(listener);
                    }
                }
            }));
            int completed = 0;
            for (Future<Integer> result : results) completed += result.get(35, TimeUnit.SECONDS);
            if (completed != expected) throw new IllegalStateException("Expected " + expected + ", got " + completed);
            System.out.println("M0 SERVER PASS workers=" + workers + " connections=" + completed);
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) throw new IllegalStateException("workers did not terminate");
        }
    }

    private static void client(VclRuntime runtime, InetSocketAddress address, int connections) throws Exception {
        try (VclPoller poller = new VclPoller(runtime); Arena arena = Arena.ofConfined()) {
            MemorySegment send = arena.allocate(64), receive = arena.allocate(64);
            for (int sequence = 0; sequence < connections; sequence++) {
                for (int n = 0; n < 64; n++) send.set(JAVA_BYTE, n, (byte) (sequence + n));
                int sh = VclException.check("create", poller.api.create());
                try {
                    int result = poller.api.connect(sh, address);
                    if (result != NativeVcl.IN_PROGRESS) VclException.check("connect", result);
                    poller.control(NativeVcl.ADD, sh, NativeVcl.IN | NativeVcl.OUT, 1);
                    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    int sent = 0, received = 0;
                    boolean connected = result == 0;
                    while (received < 64 && System.nanoTime() < deadline) {
                        int ready = poller.poll(10);
                        for (int i = 0; i < ready; i++) {
                            if (!connected && (poller.events(i) & (NativeVcl.OUT | NativeVcl.ERR | NativeVcl.HUP)) != 0) {
                                int error = poller.api.error(sh);
                                if (error != 0) throw new VclException("connect completion", error);
                                connected = true;
                            }
                        }
                        if (!connected) continue;
                        if (sent < 64) {
                            int count = poller.api.write(sh, send.asSlice(sent), 64 - sent);
                            if (count != NativeVcl.AGAIN) sent += VclException.check("write", count);
                            if (sent == 64) poller.control(NativeVcl.MOD, sh, NativeVcl.IN, 1);
                        }
                        int count = poller.api.read(sh, receive.asSlice(received), 64 - received);
                        if (count == 0) throw new IllegalStateException("EOF before echo");
                        if (count != NativeVcl.AGAIN) received += VclException.check("read", count);
                    }
                    if (received != 64 || send.mismatch(receive) != -1) throw new IllegalStateException("echo mismatch/timeout sequence=" + sequence);
                    poller.control(NativeVcl.DEL, sh, 0, 1);
                } finally { poller.api.close(sh); }
            }
            System.out.println("M0 CLIENT PASS connections=" + connections);
        }
    }

    private static final class Connection {
        final int sh;
        final MemorySegment data;
        int read, written;
        Connection(int sh, MemorySegment data) { this.sh = sh; this.data = data; }
    }
}

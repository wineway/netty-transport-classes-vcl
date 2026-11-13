/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl.preflight;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import static java.lang.foreign.ValueLayout.*;

/** Independent SAPI socket, used only to detect closure, without VCL private structure access. */
final class LivenessProbe implements AutoCloseable {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle SOCKET = call("socket", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    private static final MethodHandle CONNECT = call("connect", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle POLL = call("poll", JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT);
    private static final MethodHandle CLOSE = call("close", JAVA_INT, JAVA_INT);
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment pollfd = arena.allocate(8, 4);
    private int fd = -1;

    private static MethodHandle call(String name, MemoryLayout result, MemoryLayout... args) {
        return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(),
                FunctionDescriptor.of(result, args));
    }

    LivenessProbe(String path) throws Throwable {
        try {
            byte[] name = path.getBytes(StandardCharsets.UTF_8);
            if (name.length >= 108) throw new IllegalArgumentException("SAPI socket path too long");
            fd = Probe.ok((int) SOCKET.invokeExact(1, 5 | 2048 | 524288, 0), "socket(AF_UNIX,SEQPACKET)");
            var address = arena.allocate(110, 2);
            address.set(JAVA_SHORT, 0, (short) 1);
            MemorySegment.copy(name, 0, address, JAVA_BYTE, 2, name.length);
            Probe.ok((int) CONNECT.invokeExact(fd, address, name.length + 3), "connect(liveness)");
            pollfd.set(JAVA_INT, 0, fd);
            pollfd.set(JAVA_SHORT, 4, (short) 0);
        } catch (Throwable t) {
            close(); throw t;
        }
    }

    boolean alive() throws Throwable {
        if (fd < 0) return false;
        pollfd.set(JAVA_SHORT, 6, (short) 0);
        Probe.ok((int) POLL.invokeExact(pollfd, 1L, 0), "poll(liveness)");
        return (pollfd.get(JAVA_SHORT, 6) & (8 | 16 | 32)) == 0;
    }

    @Override public void close() {
        try {
            if (fd >= 0) {
                int value = fd; fd = -1;
                Probe.ok((int) CLOSE.invokeExact(value), "close(liveness)");
            }
        } catch (Throwable t) { throw new IllegalStateException(t); }
        finally { arena.close(); }
    }
}

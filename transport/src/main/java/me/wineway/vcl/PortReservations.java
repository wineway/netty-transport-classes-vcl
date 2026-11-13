/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import java.net.BindException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** VPP listen does not allocate port zero. Coordinate candidate ports across this application's workers. */
final class PortReservations {
    private static final Map<Integer, Integer> TCP = new HashMap<>(), UDP = new HashMap<>();
    private static int cursor = ThreadLocalRandom.current().nextInt(49152, 65536);
    private PortReservations() { }
    static synchronized Lease reserve(boolean datagram, int requested) throws BindException {
        Map<Integer, Integer> ports = datagram ? UDP : TCP;
        int port = requested;
        if (port == 0) {
            int attempts = 16384;
            do {
                port = cursor++;
                if (cursor == 65536) cursor = 49152;
                if (!ports.containsKey(port)) break;
                if (--attempts == 0) throw new BindException("VCL ephemeral port candidates exhausted");
            } while (true);
        }
        ports.merge(port, 1, Integer::sum);
        return new Lease(ports, port);
    }
    static final class Lease implements AutoCloseable {
        private final Map<Integer, Integer> ports;
        final int port;
        private boolean closed;
        Lease(Map<Integer, Integer> ports, int port) { this.ports = ports; this.port = port; }
        @Override public void close() {
            synchronized (PortReservations.class) {
                if (closed) return;
                closed = true;
                int remaining = ports.get(port) - 1;
                if (remaining == 0) ports.remove(port); else ports.put(port, remaining);
            }
        }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import java.lang.foreign.Arena;
import java.nio.file.Path;

final class FfmVclApplication implements VclApplication {
    private final NativeCalls calls;
    private final String socket;
    private final VclMode mode;
    private final int maxWorkers;
    FfmVclApplication(Path library, Path socket, VclMode mode, int maxWorkers) {
        calls = NativeCalls.load(library); this.socket = socket.toAbsolutePath().toString();
        this.mode = mode; this.maxWorkers = maxWorkers;
    }
    @Override public void start() throws Exception {
        try (var arena = Arena.ofConfined()) {
            // vppcom_app_init_common performs app_destroy itself on failed attach.
            VclException.check("app_create", calls.createApp(NativeCalls.config(
                    arena, socket, mode == VclMode.INTERRUPT, maxWorkers + 1)));
        } catch (Throwable t) { throw VclException.translate(t); }
    }
    @Override public VclIo newWorker() throws Exception { return new FfmVclIo(calls, mode, socket); }
    @Override public void destroy() throws Exception {
        try { calls.destroyApp(); } catch (Throwable t) { throw VclException.translate(t); }
    }
}

/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

/** Test seam for process lifecycle; start/destroy run on the same platform thread. */
interface VclApplication {
    void start() throws Exception;
    VclIo newWorker() throws Exception;
    void destroy() throws Exception;
}

/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

/** Selected once for the lifetime of the process-wide VCL application. */
public enum VclMode {
    INTERRUPT,
    POLL
}

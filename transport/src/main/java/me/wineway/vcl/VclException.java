/* SPDX-License-Identifier: Apache-2.0 */
package me.wineway.vcl;

import java.io.IOException;

/** A negative error returned by the public VCL API (not libc errno). */
public final class VclException extends IOException {
    private final int error;
    VclException(String operation, int result) {
        super(operation + " failed: VCL error " + result);
        error = result;
    }
    public int error() { return error; }
    static int check(String operation, int result) throws VclException {
        if (result < 0) throw new VclException(operation, result);
        return result;
    }
    static int handle(String operation, int result) throws VclException {
        // Session handles carry the worker in their high byte. Workers 128..254
        // produce valid signed-negative Java ints; only the errno band is an error.
        if (result < 0 && result >= -4095) throw new VclException(operation, result);
        return result;
    }
    static Exception translate(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) return exception;
        return new IOException(failure);
    }
}

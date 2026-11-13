package dev.vcl.netty;

import java.io.IOException;

/** VCL returns negative errno directly, independently of libc's errno. */
public final class VclException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int errno;

    public VclException(String operation, int result) {
        super(operation + " failed: errno=" + Math.abs(result));
        errno = Math.abs(result);
    }

    public int errno() { return errno; }

    static int check(String operation, int result) throws VclException {
        if (result < 0) throw new VclException(operation, result);
        return result;
    }
}

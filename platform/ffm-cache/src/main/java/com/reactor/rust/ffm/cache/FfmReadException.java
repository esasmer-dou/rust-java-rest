package com.reactor.rust.ffm.cache;

/** Active-path failure: callers may reject/back off, but must not replay this read through JNI. */
public final class FfmReadException extends RuntimeException {
    private final int nativeStatus;
    public FfmReadException(int nativeStatus, String operation) {
        super(operation + " failed; native status=" + nativeStatus + "; no JNI replay");
        this.nativeStatus = nativeStatus;
    }
    public int nativeStatus() { return nativeStatus; }
}

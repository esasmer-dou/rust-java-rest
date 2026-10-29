package com.reactor.rust.security;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import com.reactor.rust.exception.DependencyFailure;

/** Fixed non-blocking admission, no TLS or background threads. Exhaustion is overload, not bad credentials. */
final class JwtWorkPool<T> {
    private static final RuntimeException EXHAUSTED = new CapacityExhausted();
    private final T[] workers;
    private final AtomicIntegerArray busy;
    private final AtomicInteger cursor = new AtomicInteger();

    JwtWorkPool(T[] workers) {
        checkSize(workers.length);
        this.workers = workers;
        busy = new AtomicIntegerArray(workers.length);
    }

    static void checkSize(int size) {
        if (size < 1 || size > 256 || Integer.bitCount(size) != 1) {
            throw new IllegalArgumentException("JWT maxWorkers must be a power of two in 1..256");
        }
    }

    int acquire() {
        int start = cursor.getAndIncrement();
        for (int i = 0; i < workers.length; i++) {
            int slot = (start + i) & (workers.length - 1);
            if (busy.compareAndSet(slot, 0, 1)) return slot;
        }
        throw EXHAUSTED;
    }

    T worker(int index) { return workers[index]; }
    void release(int index) { busy.set(index, 0); }

    /** Shared failure cannot retain caller stacks, causes or suppressed exceptions. */
    private static final class CapacityExhausted extends RuntimeException implements DependencyFailure {
        private CapacityExhausted() { super("JWT crypto worker capacity exhausted", null, false, false); }
    }
}

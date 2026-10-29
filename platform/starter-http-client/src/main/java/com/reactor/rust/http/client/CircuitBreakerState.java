package com.reactor.rust.http.client;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Bounded, lock-free count window, ordered by final outcome completion (not admission).
 * Each call must complete its primitive lease exactly once. CLOSED leases identify a
 * generation; HALF_OPEN leases additionally identify one of at most 64 probes.
 * No per-call allocation, scheduler, locks or maps. Generation transitions alone
 * allocate bounded state. A generation is never reused, including after exhaustion.
 */
public final class CircuitBreakerState {
    public enum Phase { CLOSED, OPEN, HALF_OPEN }

    private static final long MAX_GENERATION = Long.MAX_VALUE >>> 7;
    private static final long MAX_SEQUENCE = Long.MAX_VALUE >>> 16;
    private static final long PENDING = 1L;
    private static final long FAILURE = 2L;
    private final int windowSize;
    private final int minimumCalls;
    private final int threshold;
    private final long openNanos;
    private final int halfOpenMaxCalls;
    private final boolean record5xxAsFailure;
    private final LongSupplier clock;
    private final AtomicReference<Generation> current;

    public CircuitBreakerState(int windowSize, int minimumCalls, int failureRateThreshold,
                               long openDurationMs, int halfOpenMaxCalls, boolean record5xxAsFailure) {
        this(windowSize, minimumCalls, failureRateThreshold, openDurationMs,
                halfOpenMaxCalls, record5xxAsFailure, System::nanoTime);
    }

    public CircuitBreakerState(int windowSize, int minimumCalls, int failureRateThreshold,
                               long openDurationMs, int halfOpenMaxCalls, boolean record5xxAsFailure,
                               LongSupplier clock) {
        if (windowSize < 1 || windowSize > 64 || minimumCalls < 1 || minimumCalls > windowSize
                || failureRateThreshold < 1 || failureRateThreshold > 100
                || openDurationMs < 1 || openDurationMs > Long.MAX_VALUE / 1_000_000L
                || halfOpenMaxCalls < 1 || halfOpenMaxCalls > 64) {
            throw new IllegalArgumentException("Invalid HTTP circuit breaker configuration: windowSize=1..64, "
                    + "minimumCalls=1..windowSize, failureRateThreshold=1..100, "
                    + "openDurationMs=1.." + Long.MAX_VALUE / 1_000_000L + ", halfOpenMaxCalls=1..64");
        }
        this.windowSize = windowSize;
        this.minimumCalls = minimumCalls;
        this.threshold = failureRateThreshold;
        this.openNanos = openDurationMs * 1_000_000L;
        this.halfOpenMaxCalls = halfOpenMaxCalls;
        this.record5xxAsFailure = record5xxAsFailure;
        this.clock = Objects.requireNonNull(clock, "clock");
        current = new AtomicReference<>(new Closed(1L, windowSize));
    }

    /** Returns an allocation-free opaque lease, or throws the shared rejection. */
    public long acquirePermission() {
        for (;;) {
            Generation generation = current.get();
            if (generation.id == MAX_GENERATION) throw HttpCircuitOpenException.INSTANCE;
            if (generation instanceof Closed) return generation.id << 7;
            if (generation instanceof Open open) {
                if (clock.getAsLong() - open.openedAt < openNanos) throw HttpCircuitOpenException.INSTANCE;
                current.compareAndSet(open, new HalfOpen(open.id + 1));
                continue;
            }
            HalfOpen half = (HalfOpen) generation;
            int issued = half.issued.get();
            if (issued >= halfOpenMaxCalls) throw HttpCircuitOpenException.INSTANCE;
            if (half.issued.compareAndSet(issued, issued + 1)) {
                return (half.id << 7) | (issued + 1);
            }
        }
    }

    public void recordSuccess(long lease) {
        Generation generation = current.get();
        if (generation instanceof Closed closed && (lease & 127L) == 0L && lease >>> 7 == generation.id) {
            long before = closed.control.get();
            long sequence = before >>> 16;
            if ((before & PENDING) == 0L && ((before >>> 9) & 127L) == 0L && sequence < MAX_SEQUENCE) {
                long size = Math.min(((before >>> 2) & 127L) + 1, windowSize);
                if (closed.control.compareAndSet(before, ((sequence + 1) << 16) | (size << 2))) return;
            }
        }
        record(lease, false);
    }
    public void recordFailure(long lease) { record(lease, true); }
    public boolean record5xxAsFailure() { return record5xxAsFailure; }
    public Phase phase() { return current.get().phase; }

    private void record(long lease, boolean failure) {
        Generation generation = current.get();
        if (lease >>> 7 != generation.id) return;
        if (generation instanceof Closed closed) {
            if ((lease & 127L) != 0L) return;
            recordClosed(closed, failure);
        } else if (generation instanceof HalfOpen half) {
            int probe = (int) (lease & 127L) - 1;
            if (probe < 0 || probe >= half.issued.get()) return;
            long bit = 1L << probe;
            long completed;
            do {
                completed = half.completed.get();
                if ((completed & bit) != 0L) return;
            } while (!half.completed.compareAndSet(completed, completed | bit));
            if (failure) {
                open(half);
            } else if (half.successes.incrementAndGet() == halfOpenMaxCalls) {
                current.compareAndSet(half, new Closed(half.id + 1, windowSize));
            }
        }
    }

    private void recordClosed(Closed closed, boolean failure) {
        while (current.get() == closed) {
            long before = closed.control.get();
            if ((before & PENDING) != 0L) {
                help(closed, before);
                continue;
            }
            long sequence = before >>> 16;
            if (sequence == MAX_SEQUENCE) {
                // Never reuse a slot stamp (ABA), even after astronomical event counts.
                current.compareAndSet(closed, new Open(MAX_GENERATION, clock.getAsLong()));
                return;
            }
            int size = (int) ((before >>> 2) & 127L);
            int failures = (int) ((before >>> 9) & 127L);
            if (!failure && failures == 0) {
                // An empty failure window has only zero bits. Advance its logical
                // position without publishing a descriptor or rewriting zero slots.
                long healthy = ((sequence + 1) << 16) | ((long) Math.min(size + 1, windowSize) << 2);
                if (closed.control.compareAndSet(before, healthy)) return;
                continue;
            }
            int slot = (int) (sequence % windowSize);
            int evicted = size == windowSize ? (int) (closed.results.get(slot) & 1L) : 0;
            int nextSize = Math.min(size + 1, windowSize);
            int nextFailures = failures + (failure ? 1 : 0) - evicted;
            long pending = ((sequence + 1) << 16) | ((long) nextFailures << 9)
                    | ((long) nextSize << 2) | (failure ? FAILURE : 0L) | PENDING;
            if (closed.control.compareAndSet(before, pending)) {
                help(closed, pending);
                return;
            }
        }
    }

    // A pending descriptor is helpable by ANY recorder: a paused writer holds no lock.
    // Slot stamps prevent a delayed helper from overwriting a later turn of the ring.
    private void help(Closed closed, long pending) {
        long sequence = pending >>> 16;
        int slot = (int) ((sequence - 1) % windowSize);
        long result = (sequence << 1) | ((pending & FAILURE) >>> 1);
        long previous;
        do {
            previous = closed.results.get(slot);
            if ((previous >>> 1) >= sequence) break;
        } while (!closed.results.compareAndSet(slot, previous, result));
        int size = (int) ((pending >>> 2) & 127L);
        int failures = (int) ((pending >>> 9) & 127L);
        if (size >= minimumCalls && failures * 100 >= threshold * size) open(closed);
        closed.control.compareAndSet(pending, pending & ~(PENDING | FAILURE));
    }

    private void open(Generation expected) {
        if (current.get() == expected) {
            current.compareAndSet(expected, new Open(expected.id + 1, clock.getAsLong()));
        }
    }

    private abstract static class Generation {
        final long id;
        final Phase phase;
        Generation(long id, Phase phase) { this.id = id; this.phase = phase; }
    }

    private static final class Closed extends Generation {
        final AtomicLong control = new AtomicLong();
        final AtomicLongArray results;
        Closed(long id, int windowSize) {
            super(id, Phase.CLOSED);
            results = new AtomicLongArray(windowSize);
        }
    }

    private static final class Open extends Generation {
        final long openedAt;
        Open(long id, long openedAt) { super(id, Phase.OPEN); this.openedAt = openedAt; }
    }

    private static final class HalfOpen extends Generation {
        final AtomicInteger issued = new AtomicInteger();
        final AtomicLong completed = new AtomicLong();
        final AtomicInteger successes = new AtomicInteger();
        HalfOpen(long id) { super(id, Phase.HALF_OPEN); }
    }
}

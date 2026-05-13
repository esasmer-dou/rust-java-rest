package com.reactor.rust.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Non-blocking adaptive concurrency limiter for route-level backpressure.
 *
 * <p>The limiter uses AIMD: additive increase while latency is healthy,
 * multiplicative decrease when latency or errors indicate pressure.</p>
 */
public final class AdaptiveBulkhead {

    private final String name;
    private final boolean adaptiveEnabled;
    private final int minLimit;
    private final int maxLimit;
    private final int increaseStep;
    private final int decreasePercent;
    private final int sampleSize;
    private final long targetLatencyNanos;
    private final long highLatencyNanos;

    private final AtomicInteger currentLimit;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger sampleCount = new AtomicInteger();
    private final AtomicInteger sampleErrors = new AtomicInteger();
    private final AtomicLong sampleLatencyNanos = new AtomicLong();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder timedOut = new LongAdder();

    public AdaptiveBulkhead(Config config) {
        this.name = requireText(config.name(), "name");
        this.adaptiveEnabled = config.adaptiveEnabled();
        this.minLimit = Math.max(1, config.minLimit());
        this.maxLimit = Math.max(this.minLimit, config.maxLimit());
        int initialLimit = config.initialLimit() <= 0
                ? this.minLimit
                : config.initialLimit();
        this.currentLimit = new AtomicInteger(clamp(initialLimit, this.minLimit, this.maxLimit));
        this.increaseStep = Math.max(1, config.increaseStep());
        this.decreasePercent = clamp(config.decreasePercent(), 1, 99);
        this.sampleSize = Math.max(8, config.sampleSize());
        this.targetLatencyNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, config.targetLatencyMs()));
        this.highLatencyNanos = TimeUnit.MILLISECONDS.toNanos(
                Math.max(config.targetLatencyMs(), config.highLatencyMs()));
    }

    public Lease tryAcquire() {
        while (true) {
            int current = inFlight.get();
            int limit = currentLimit.get();
            if (current >= limit) {
                rejected.increment();
                return null;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                accepted.increment();
                return new Lease(this, System.nanoTime());
            }
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                name,
                currentLimit.get(),
                minLimit,
                maxLimit,
                inFlight.get(),
                accepted.sum(),
                rejected.sum(),
                completed.sum(),
                timedOut.sum());
    }

    private void release(boolean success, long durationNanos, boolean timeout) {
        inFlight.updateAndGet(value -> Math.max(0, value - 1));
        completed.increment();
        if (timeout) {
            timedOut.increment();
        }
        if (!success) {
            sampleErrors.incrementAndGet();
        }
        sampleLatencyNanos.addAndGet(Math.max(0, durationNanos));
        int count = sampleCount.incrementAndGet();
        if (count >= sampleSize) {
            adjust();
        }
    }

    private synchronized void adjust() {
        int count = sampleCount.getAndSet(0);
        if (count <= 0) {
            sampleLatencyNanos.set(0);
            sampleErrors.set(0);
            return;
        }
        long totalLatency = sampleLatencyNanos.getAndSet(0);
        int errors = sampleErrors.getAndSet(0);
        if (!adaptiveEnabled) {
            return;
        }

        long avgLatency = totalLatency / count;
        int limit = currentLimit.get();
        int next = limit;
        if (errors > 0 || avgLatency >= highLatencyNanos) {
            next = Math.max(minLimit, (limit * decreasePercent) / 100);
        } else if (avgLatency <= targetLatencyNanos) {
            next = Math.min(maxLimit, limit + increaseStep);
        }
        if (next != limit) {
            currentLimit.set(next);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Config(
            String name,
            boolean adaptiveEnabled,
            int minLimit,
            int initialLimit,
            int maxLimit,
            int targetLatencyMs,
            int highLatencyMs,
            int sampleSize,
            int increaseStep,
            int decreasePercent) {}

    public record Snapshot(
            String name,
            int limit,
            int minLimit,
            int maxLimit,
            int inFlight,
            long accepted,
            long rejected,
            long completed,
            long timedOut) {}

    public static final class Lease {
        private final AdaptiveBulkhead owner;
        private final long startNanos;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(AdaptiveBulkhead owner, long startNanos) {
            this.owner = owner;
            this.startNanos = startNanos;
        }

        public long elapsedNanos() {
            return System.nanoTime() - startNanos;
        }

        public boolean release(boolean success) {
            return release(success, false);
        }

        public boolean release(boolean success, boolean timeout) {
            if (!released.compareAndSet(false, true)) {
                return false;
            }
            owner.release(success, elapsedNanos(), timeout);
            return true;
        }
    }
}

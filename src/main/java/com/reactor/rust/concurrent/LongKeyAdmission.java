package com.reactor.rust.concurrent;

import com.reactor.rust.config.PropertiesLoader;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Bounded, striped admission control for commands targeting the same long key. */
public final class LongKeyAdmission {

    private final boolean enabled;
    private final Semaphore[] stripes;
    private final int mask;
    private final int maxConcurrentPerStripe;
    private final LongAdder accepted = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    public LongKeyAdmission(boolean enabled, int maxConcurrentPerKey, int stripeCount) {
        if (maxConcurrentPerKey < 1) {
            throw new IllegalArgumentException("maxConcurrentPerKey must be positive");
        }
        this.enabled = enabled;
        this.maxConcurrentPerStripe = maxConcurrentPerKey;
        this.stripes = new Semaphore[nextPowerOfTwo(stripeCount)];
        this.mask = stripes.length - 1;
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new Semaphore(maxConcurrentPerKey);
        }
    }

    public static LongKeyAdmission fromProperties(String prefix) {
        String root = requireText(prefix);
        return new LongKeyAdmission(
                PropertiesLoader.requireBoolean(root + ".enabled"),
                PropertiesLoader.requireInt(root + ".max-concurrent-per-key"),
                PropertiesLoader.requireInt(root + ".stripes"));
    }

    public <T> CompletableFuture<T> execute(long key, Supplier<CompletableFuture<T>> action) {
        Objects.requireNonNull(action, "action");
        if (!enabled) {
            return action.get();
        }
        Semaphore semaphore = stripes[stripeIndex(key)];
        if (!semaphore.tryAcquire()) {
            rejected.increment();
            return CompletableFuture.failedFuture(
                    new RejectedExecutionException("command key is already in flight"));
        }
        accepted.increment();
        try {
            return action.get().whenComplete((ignored, error) -> semaphore.release());
        } catch (Throwable error) {
            semaphore.release();
            return CompletableFuture.failedFuture(error);
        }
    }

    public void reset() {
        accepted.reset();
        rejected.reset();
    }

    public String metricsJson() {
        return "{\"enabled\":" + enabled
                + ",\"stripes\":" + stripes.length
                + ",\"maxConcurrentPerStripe\":" + maxConcurrentPerStripe
                + ",\"accepted\":" + accepted.sum()
                + ",\"rejected\":" + rejected.sum() + '}';
    }

    private int stripeIndex(long key) {
        long mixed = key ^ (key >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return ((int) mixed) & mask;
    }

    private static int nextPowerOfTwo(int value) {
        int normalized = Math.max(1, Math.min(value, 65_536));
        return normalized == 1 ? 1 : Integer.highestOneBit(normalized - 1) << 1;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        return value.trim();
    }
}

package com.reactor.rust.concurrent;

import com.reactor.rust.config.PropertiesLoader;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Bounded, striped admission control for commands targeting the same long or String key. */
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
        Permit permit = null;
        try {
            permit = new Permit(acquire(key));
            CompletableFuture<T> source = Objects.requireNonNull(action.get(), "action future");
            // Keep the releasing observer private, while preserving dependent-future cancellation semantics.
            return source.whenComplete(permit).thenApply(value -> value);
        } catch (Throwable error) {
            if (permit != null) permit.release();
            return CompletableFuture.failedFuture(error);
        }
    }

    public <T> CompletableFuture<T> execute(String key, Supplier<CompletableFuture<T>> action) {
        return execute(stableHash(key), action);
    }

    <T> T invoke(long key, KeyAdmissionSupport.Action<T> action) throws Throwable {
        return invoke(key, action, null);
    }

    <T> T invoke(long key, KeyAdmissionSupport.Action<T> action,
                 KeyAdmissionSupport.Rejection<T> onRejected) throws Throwable {
        if (!enabled) return action.get();
        Semaphore semaphore;
        try {
            semaphore = acquire(key);
        } catch (RejectedExecutionException error) {
            if (onRejected == null) throw error;
            return onRejected.apply(error);
        }
        T result;
        try {
            result = action.get();
        } catch (Throwable error) {
            semaphore.release();
            throw error;
        }
        if (result instanceof CompletionStage<?> stage) {
            Permit permit = new Permit(semaphore);
            try {
                // Never expose the completion observer: caller cancellation must not skip release.
                stage.whenComplete(permit);
            } catch (Throwable error) {
                permit.release();
                throw error;
            }
        } else {
            semaphore.release();
        }
        return result;
    }

    private Semaphore acquire(long key) {
        Semaphore semaphore = stripes[stripeIndex(key)];
        if (!semaphore.tryAcquire()) {
            rejected.increment();
            throw new RejectedExecutionException("command key is already in flight");
        }
        accepted.increment();
        return semaphore;
    }

    /** FNV-1a over UTF-16 code units; deterministic and allocation-free for all Java Strings. */
    static long stableHash(String key) {
        Objects.requireNonNull(key, "key");
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < key.length(); index++) {
            hash = (hash ^ key.charAt(index)) * 0x100000001b3L;
        }
        return hash;
    }

    private static final class Permit extends AtomicBoolean implements BiConsumer<Object, Throwable> {
        private final Semaphore semaphore;

        Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void accept(Object value, Throwable error) {
            release();
        }

        void release() {
            if (compareAndSet(false, true)) semaphore.release();
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

package com.reactor.rust.test;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Bounded readiness/recovery assertion. Never use this helper to replay business writes. */
public final class Eventually {
    private Eventually() {}
    public static void await(String description, Duration timeout, Duration interval, Callable<Boolean> probe) {
        Objects.requireNonNull(probe);
        long budget = timeout.toNanos();
        long pause = interval.toNanos();
        if (budget <= 0 || pause <= 0 || budget > Duration.ofMinutes(5).toNanos())
            throw new IllegalArgumentException("Positive timeout <= 5 minutes and positive interval required");
        long started = System.nanoTime();
        Exception last = null;
        while (System.nanoTime() - started < budget) {
            try { if (Boolean.TRUE.equals(probe.call())) return; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(description, interrupted); }
            catch (Exception failure) { last = failure; }
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) break;
            try { java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(pause, remaining)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(description, interrupted); }
        }
        throw new AssertionError(description + " did not become ready within " + timeout, last);
    }
}

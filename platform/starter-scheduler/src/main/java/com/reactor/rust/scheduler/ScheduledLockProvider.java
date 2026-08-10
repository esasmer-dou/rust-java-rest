package com.reactor.rust.scheduler;

import java.time.Duration;

/** Optional distributed lock SPI. Return {@code null} when another replica owns the lock. */
@FunctionalInterface
public interface ScheduledLockProvider {
    LockLease tryAcquire(String lockName, Duration atMost);

    @FunctionalInterface
    interface LockLease extends AutoCloseable {
        @Override
        void close();
    }
}

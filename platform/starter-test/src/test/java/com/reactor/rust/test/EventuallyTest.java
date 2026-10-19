package com.reactor.rust.test;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EventuallyTest {
    @Test void recoversAndBoundsFailureWithoutCreatingThreads() {
        var attempts=new AtomicInteger();
        Eventually.await("ready",Duration.ofMillis(200),Duration.ofMillis(1),()->attempts.incrementAndGet()==3);
        assertEquals(3,attempts.get());
        var error=assertThrows(AssertionError.class,()->Eventually.await("ready",Duration.ofMillis(5),
                Duration.ofMillis(1),()->{throw new IllegalStateException("offline");}));
        assertInstanceOf(IllegalStateException.class,error.getCause());
    }
    @Test void preservesInterruption() {
        try {
            assertThrows(AssertionError.class,()->Eventually.await("ready",Duration.ofMillis(5),
                    Duration.ofMillis(1),()->{throw new InterruptedException();}));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}

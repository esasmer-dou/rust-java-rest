package com.reactor.rust.concurrent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveBulkheadTest {

    @Test
    void rejectsAboveCurrentLimitWithoutBlocking() {
        AdaptiveBulkhead bulkhead = new AdaptiveBulkhead(new AdaptiveBulkhead.Config(
                "test",
                true,
                1,
                2,
                8,
                10,
                50,
                8,
                1,
                75));

        AdaptiveBulkhead.Lease first = bulkhead.tryAcquire();
        AdaptiveBulkhead.Lease second = bulkhead.tryAcquire();

        assertNotNull(first);
        assertNotNull(second);
        assertNull(bulkhead.tryAcquire());
        assertEquals(2, bulkhead.snapshot().inFlight());

        assertTrue(first.release(true));
        assertTrue(second.release(true));
        assertEquals(0, bulkhead.snapshot().inFlight());
    }

    @Test
    void decreasesLimitAfterErrorSample() {
        AdaptiveBulkhead bulkhead = new AdaptiveBulkhead(new AdaptiveBulkhead.Config(
                "test",
                true,
                2,
                8,
                16,
                10,
                50,
                8,
                1,
                50));

        for (int i = 0; i < 8; i++) {
            AdaptiveBulkhead.Lease lease = bulkhead.tryAcquire();
            assertNotNull(lease);
            lease.release(false);
        }

        assertEquals(4, bulkhead.snapshot().limit());
    }
}

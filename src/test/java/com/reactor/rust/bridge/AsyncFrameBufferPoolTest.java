package com.reactor.rust.bridge;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncFrameBufferPoolTest {

    @Test
    void boundsRetainedBuffersAcrossThreads() {
        AsyncFrameBufferPool pool = new AsyncFrameBufferPool(2, 1024, 2048, 8192, false);
        ByteBuffer first = pool.acquire(1024);
        ByteBuffer second = pool.acquire(1024);
        ByteBuffer third = pool.acquire(1024);

        pool.release(first);
        pool.release(second);
        pool.release(third);

        AsyncFrameBufferPool.Snapshot snapshot = pool.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals(2, snapshot.returned());
        assertEquals(1, snapshot.dropped());
        assertFalse(first.isDirect());

        ByteBuffer reused = pool.acquire(1024);
        assertTrue(reused == first || reused == second);
        assertEquals(1, pool.snapshot().hits());
    }

    @Test
    void dropsOversizedBuffersInsteadOfPinningBurstMemory() {
        AsyncFrameBufferPool pool = new AsyncFrameBufferPool(2, 1024, 2048, 8192, false);
        ByteBuffer buffer = pool.acquire(4096);

        pool.release(buffer);

        assertEquals(0, pool.snapshot().size());
        assertEquals(1, pool.snapshot().dropped());
    }
}

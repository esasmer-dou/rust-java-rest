package com.reactor.rust.bridge;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded ownership pool for async response frames.
 *
 * A borrowed buffer belongs to exactly one response until {@link #release(ByteBuffer)}.
 * This avoids retaining one buffer per completion thread while keeping the async path reusable.
 */
final class AsyncFrameBufferPool {

    private final AtomicReferenceArray<ByteBuffer> buffers;
    private final AtomicInteger retainedCount = new AtomicInteger();
    private final AtomicInteger takeCursor = new AtomicInteger();
    private final AtomicInteger returnCursor = new AtomicInteger();
    private final int configuredCapacity;
    private final int initialBufferBytes;
    private final int retainMaxBytes;
    private final int frameMaxBytes;
    private final boolean direct;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder returned = new LongAdder();
    private final LongAdder dropped = new LongAdder();

    AsyncFrameBufferPool(
            int configuredCapacity,
            int initialBufferBytes,
            int retainMaxBytes,
            int frameMaxBytes,
            boolean direct
    ) {
        if (configuredCapacity < 0) {
            throw new IllegalArgumentException("configuredCapacity must be >= 0");
        }
        if (initialBufferBytes <= 0 || retainMaxBytes < initialBufferBytes || frameMaxBytes < retainMaxBytes) {
            throw new IllegalArgumentException("invalid async frame buffer limits");
        }
        this.configuredCapacity = configuredCapacity;
        this.initialBufferBytes = initialBufferBytes;
        this.retainMaxBytes = retainMaxBytes;
        this.frameMaxBytes = frameMaxBytes;
        this.direct = direct;
        this.buffers = new AtomicReferenceArray<>(Math.max(1, configuredCapacity));
    }

    ByteBuffer acquire(int requiredCapacity) {
        validateRequiredCapacity(requiredCapacity);
        if (configuredCapacity > 0) {
            ByteBuffer candidate = poll();
            if (candidate != null) {
                if (candidate.capacity() >= requiredCapacity) {
                    hits.increment();
                    candidate.clear();
                    return candidate;
                }
                // Keep the useful small buffer. A large request must not evict the normal async working set.
                if (!offer(candidate)) {
                    dropped.increment();
                }
            }
        }
        misses.increment();
        return allocate(normalizedCapacity(requiredCapacity));
    }

    ByteBuffer grow(ByteBuffer current, int requiredCapacity) {
        validateRequiredCapacity(requiredCapacity);
        int doubled = current.capacity() > frameMaxBytes / 2
                ? frameMaxBytes
                : current.capacity() * 2;
        ByteBuffer replacement = acquire(Math.max(requiredCapacity, doubled));
        release(current);
        return replacement;
    }

    void release(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        buffer.clear();
        if (configuredCapacity == 0 || buffer.capacity() > retainMaxBytes || !offer(buffer)) {
            dropped.increment();
            return;
        }
        returned.increment();
    }

    Snapshot snapshot() {
        return new Snapshot(
                configuredCapacity,
                retainedCount.get(),
                initialBufferBytes,
                retainMaxBytes,
                direct,
                hits.sum(),
                misses.sum(),
                returned.sum(),
                dropped.sum()
        );
    }

    void resetMetrics() {
        hits.reset();
        misses.reset();
        returned.reset();
        dropped.reset();
    }

    void clear() {
        for (int index = 0; index < buffers.length(); index++) {
            buffers.getAndSet(index, null);
        }
        retainedCount.set(0);
    }

    private int normalizedCapacity(int requiredCapacity) {
        int capacity = initialBufferBytes;
        while (capacity < requiredCapacity && capacity <= frameMaxBytes / 2) {
            capacity *= 2;
        }
        return Math.max(requiredCapacity, Math.min(capacity, frameMaxBytes));
    }

    private void validateRequiredCapacity(int requiredCapacity) {
        if (requiredCapacity <= 0 || requiredCapacity > frameMaxBytes) {
            throw new IllegalStateException("async response frame too large: " + requiredCapacity);
        }
    }

    private ByteBuffer allocate(int capacity) {
        return direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
    }

    private ByteBuffer poll() {
        int start = Math.floorMod(takeCursor.getAndIncrement(), configuredCapacity);
        for (int offset = 0; offset < configuredCapacity; offset++) {
            int index = (start + offset) % configuredCapacity;
            ByteBuffer candidate = buffers.getAndSet(index, null);
            if (candidate != null) {
                retainedCount.decrementAndGet();
                return candidate;
            }
        }
        return null;
    }

    private boolean offer(ByteBuffer buffer) {
        int start = Math.floorMod(returnCursor.getAndIncrement(), configuredCapacity);
        for (int offset = 0; offset < configuredCapacity; offset++) {
            int index = (start + offset) % configuredCapacity;
            if (buffers.compareAndSet(index, null, buffer)) {
                retainedCount.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    record Snapshot(
            int capacity,
            int size,
            int initialBufferBytes,
            int retainMaxBytes,
            boolean direct,
            long hits,
            long misses,
            long returned,
            long dropped
    ) {}
}

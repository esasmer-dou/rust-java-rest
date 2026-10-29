package com.reactor.rust.view;

import java.nio.ByteBuffer;

/**
 * Thread-confined borrowed storage, not an owner of a native lease.
 * The caller must keep storage immutable and its lease alive for the ENTIRE borrow,
 * then close the view before releasing the lease. No access is safe after lease release.
 * Views, nested views and slices must not escape a borrow or be used after a subsequent borrow.
 * Clearing a view detaches Java references; it does not release native storage.
 */
public interface NativeFlyweightView extends AutoCloseable {
    /** Uses buffer.limit() as the record boundary; prefer explicit byteLength for adjacent records. */
    void wrap(ByteBuffer buffer, int baseOffset);
    /** Bounds every descriptor, including nested descriptors, to this record rather than capacity. */
    void wrap(ByteBuffer buffer, int baseOffset, int byteLength);
    ByteBuffer backingBuffer();
    int baseOffset();
    int byteSize();
    void clear();

    @Override
    default void close() {
        clear();
    }
}

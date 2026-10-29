package com.reactor.rust.view;

import java.nio.ByteBuffer;

/** Lifecycle support for generated views. Idle thread-local instances retain no buffers. */
public abstract class AbstractNativeView implements NativeFlyweightView {
    protected ByteBuffer buffer;
    protected int base;
    protected int size;
    private Thread owner;

    protected final void beginWrap(ByteBuffer source, int offset, int length, int headerSize) {
        if (buffer != null) {
            throw new IllegalStateException("Outstanding borrow: clear/close before wrapping again");
        }
        ViewSupport.checkRange(source, offset, length);
        if (length < headerSize) {
            throw new IllegalArgumentException("Truncated view header");
        }
        buffer = source;
        base = offset;
        size = headerSize;
        owner = Thread.currentThread();
    }

    protected final void checkBound() {
        if (buffer == null || owner != Thread.currentThread()) {
            throw new IllegalStateException("View is detached or used from another thread");
        }
    }

    @Override
    public final ByteBuffer backingBuffer() {
        checkBound();
        return buffer;
    }

    @Override
    public final int baseOffset() {
        checkBound();
        return base;
    }

    @Override
    public final int byteSize() {
        checkBound();
        return size;
    }

    @Override
    public void clear() {
        if (buffer != null) {
            checkBound();
        }
        buffer = null;
        base = 0;
        size = 0;
        owner = null;
    }
}

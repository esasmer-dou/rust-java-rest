package com.reactor.rust.json;

import java.nio.ByteBuffer;

/**
 * Build-time/generated JSON writer contract.
 *
 * <p>Generated implementations should write directly to {@code out} and return the same convention
 * as native handlers: positive body length on success, negative required body length on overflow.</p>
 */
@FunctionalInterface
public interface DirectJsonWriter<T> {
    int write(T value, ByteBuffer out, int offset);
}

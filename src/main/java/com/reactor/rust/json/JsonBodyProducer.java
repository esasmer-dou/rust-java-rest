package com.reactor.rust.json;

import java.nio.ByteBuffer;

/**
 * Object-graph-free JSON producer contract.
 *
 * <p>Implementations write JSON directly into the native response buffer and return the same
 * convention as native handlers: positive body length on success, negative required length on
 * overflow.</p>
 *
 * <p>After overflow, the same producer may be called again with a larger buffer.
 * Keep {@code write} replayable: perform database/RPC calls and other side effects
 * in the handler, then capture their result in the producer.</p>
 */
@FunctionalInterface
public interface JsonBodyProducer {
    int write(ByteBuffer out, int offset);
}

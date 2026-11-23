package com.reactor.rust.websocket;

/**
 * Opts a WebSocket handler into forwarding unchanged binary messages without
 * copying their payload into Java. Do not combine with a byte[] callback.
 */
@FunctionalInterface
public interface WebSocketBinaryForwarder {
    /**
     * Returns true to enqueue the original message on this session, false to drop it.
     * Runs synchronously; the decision must not block. Other sends made here precede the forwarded message.
     */
    boolean shouldForwardBinary(WebSocketSession session, int length);
}

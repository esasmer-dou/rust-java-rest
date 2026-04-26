package com.reactor.rust.websocket;

import com.reactor.rust.bridge.NativeBridge;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a WebSocket connection session.
 */
public class WebSocketSession {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

    private final long id;
    private final String path;
    private final Map<String, String> pathParams;
    private final Map<String, String> queryParams;
    private final Map<String, Object> attributes;
    private volatile boolean open = true;

    public WebSocketSession(String path) {
        this(path, Map.of(), Map.of());
    }

    public WebSocketSession(String path, Map<String, String> pathParams, Map<String, String> queryParams) {
        this.id = ID_GENERATOR.incrementAndGet();
        this.path = path;
        this.pathParams = new ConcurrentHashMap<>(pathParams);
        this.queryParams = new ConcurrentHashMap<>(queryParams);
        this.attributes = new ConcurrentHashMap<>();
    }

    /**
     * Constructor with explicit session ID (used by Rust JNI callbacks).
     */
    public WebSocketSession(long sessionId, String path, Map<String, String> pathParams, Map<String, String> queryParams) {
        this.id = sessionId;
        this.path = path;
        this.pathParams = new ConcurrentHashMap<>(pathParams);
        this.queryParams = new ConcurrentHashMap<>(queryParams);
        this.attributes = new ConcurrentHashMap<>();
    }

    /**
     * Get unique session ID.
     */
    public long getId() {
        return id;
    }

    /**
     * Get the WebSocket path.
     */
    public String getPath() {
        return path;
    }

    /**
     * Get path parameters (e.g., /ws/chat/{roomId}).
     */
    public Map<String, String> getPathParams() {
        return pathParams;
    }

    /**
     * Get query parameters.
     */
    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    /**
     * Get session attributes (can store custom data).
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * Check if session is open.
     */
    public boolean isOpen() {
        return open;
    }

    /**
     * Send text message to client.
     */
    public void sendText(String message) {
        if (!open) {
            throw new IllegalStateException("Session is closed");
        }
        Objects.requireNonNull(message, "message");
        ensureSendAccepted(NativeBridge.sendWebSocketText(id, message));
    }

    /**
     * Send binary message to client.
     */
    public void sendBinary(byte[] data) {
        if (!open) {
            throw new IllegalStateException("Session is closed");
        }
        Objects.requireNonNull(data, "data");
        ensureSendAccepted(NativeBridge.sendWebSocketBinary(id, data, data.length));
    }

    /**
     * Send binary message from ByteBuffer.
     */
    public void sendBinary(ByteBuffer buffer) {
        if (!open) {
            throw new IllegalStateException("Session is closed");
        }
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        ensureSendAccepted(NativeBridge.sendWebSocketBinary(id, data, data.length));
    }

    /**
     * Close the WebSocket connection.
     */
    public void close() {
        if (open) {
            open = false;
            NativeBridge.closeWebSocket(id);
        }
    }

    /**
     * Close with custom code and reason.
     */
    public void close(int code, String reason) {
        if (open) {
            open = false;
            NativeBridge.closeWebSocketWithReason(id, code, reason == null ? "" : reason);
        }
    }

    /**
     * Mark closed from Rust close callback.
     */
    void markClosed() {
        open = false;
    }

    private void ensureSendAccepted(int status) {
        if (status == NativeBridge.WS_SEND_OK) {
            return;
        }
        if (status == NativeBridge.WS_SEND_NOT_FOUND) {
            open = false;
            throw new IllegalStateException("WebSocket session is not registered in native runtime: " + id);
        }
        if (status == NativeBridge.WS_SEND_QUEUE_FULL) {
            open = false;
            throw new IllegalStateException("WebSocket outbound queue is full; slow consumer closed: " + id);
        }
        if (status == NativeBridge.WS_SEND_TOO_LARGE) {
            throw new IllegalArgumentException("WebSocket frame exceeds configured max frame size");
        }
        throw new IllegalStateException("WebSocket native send failed with status " + status + " for session " + id);
    }

    @Override
    public String toString() {
        return "WebSocketSession{" +
                "id=" + id +
                ", path='" + path + '\'' +
                ", open=" + open +
                '}';
    }
}

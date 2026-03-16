package com.reactor.rust.websocket;

import java.nio.ByteBuffer;
import java.util.Map;
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

    // Native pointer for Rust-side connection
    private long nativePtr = 0;

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
        nativeSendText(nativePtr, message);
    }

    /**
     * Send binary message to client.
     */
    public void sendBinary(byte[] data) {
        if (!open) {
            throw new IllegalStateException("Session is closed");
        }
        nativeSendBinary(nativePtr, data, data.length);
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
        nativeSendBinary(nativePtr, data, data.length);
    }

    /**
     * Close the WebSocket connection.
     */
    public void close() {
        if (open) {
            open = false;
            nativeClose(nativePtr);
        }
    }

    /**
     * Close with custom code and reason.
     */
    public void close(int code, String reason) {
        if (open) {
            open = false;
            nativeCloseWithReason(nativePtr, code, reason);
        }
    }

    /**
     * Set native pointer (called from Rust).
     */
    public void setNativePtr(long ptr) {
        this.nativePtr = ptr;
    }

    // Native methods implemented in Rust
    private native void nativeSendText(long ptr, String message);
    private native void nativeSendBinary(long ptr, byte[] data, int len);
    private native void nativeClose(long ptr);
    private native void nativeCloseWithReason(long ptr, int code, String reason);

    @Override
    public String toString() {
        return "WebSocketSession{" +
                "id=" + id +
                ", path='" + path + '\'' +
                ", open=" + open +
                '}';
    }
}

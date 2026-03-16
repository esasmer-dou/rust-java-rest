package com.reactor.rust.bridge;

import com.reactor.rust.websocket.WebSocketRegistry;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JNI bridge between Rust HTTP server and Java handlers.
 * Single entry point - no version variants.
 *
 * Native library loading order:
 * 1. System property: -Drust.lib.path=/path/to/library
 * 2. java.library.path: System.loadLibrary("rust_hyper")
 * 3. JAR resources: native/{platform}/rust_hyper.{ext}
 */
public class NativeBridge {

    private static final AtomicLong counter = new AtomicLong();

    static {
        NativeLibraryLoader.load();
    }

    public static native void releaseNativeMemory();

    // ======================
    // WEBSOCKET NATIVE METHODS
    // ======================

    /**
     * Register a WebSocket route with Rust.
     * @param path WebSocket path (e.g., "/ws/echo")
     * @param handlerId Handler ID for routing
     */
    public static native void registerWebSocketRoute(String path, int handlerId);

    // ======================
    // RUST → JAVA register
    // ======================
    public static native void passNativeBridgeClass(Class<?> clazz);

    // ======================
    // JAVA → RUST
    // ======================
    public static native void startHttpServer(int port);

    public static native void registerRoutes(List<RouteDef> routes);

    /**
     * Single handler entry point from Rust.
     * Signature: (ByteBuffer, int offset, byte[] body, String pathParams, String queryString, String headers)
     */
    public static int handleRustRequestIntoBuffer(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) {
        long c = counter.incrementAndGet();
        if (c % 5000 == 0) {
            try {
                releaseNativeMemory();
            } catch (Exception ignored) {}
        }

        HandlerRegistry registry = HandlerRegistry.getInstance();

        try {
            int written = registry.invokeBuffered(
                    handlerId,
                    outBuffer,
                    offset,
                    inBytes,
                    pathParams,
                    queryString,
                    headers
            );

            if (written < 0) {
                return written;
            }
            if (written > capacity) {
                return -written;
            }
            return written;

        } catch (Throwable e) {
            byte[] err = ("{\"error\":\"" + e.getMessage() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            if (err.length > capacity) {
                return -err.length;
            }
            outBuffer.position(offset);
            outBuffer.put(err);
            return err.length;
        }
    }

    // ======================
    // WEBSOCKET CALLBACKS (Called from Rust)
    // ======================

    /**
     * Called from Rust when a WebSocket connection is opened.
     */
    public static void onWebSocketOpen(long sessionId, String path) {
        try {
            WebSocketRegistry registry = WebSocketRegistry.getInstance();
            registry.onOpen(sessionId, path, "", "");
            System.out.println("[NativeBridge] WebSocket opened: sessionId=" + sessionId + ", path=" + path);
        } catch (Exception e) {
            System.err.println("[NativeBridge] Error in onWebSocketOpen: " + e.getMessage());
        }
    }

    /**
     * Called from Rust when a WebSocket text message is received.
     * Returns the response to send back, or null to echo the original message.
     */
    public static String onWebSocketMessage(long sessionId, String message) {
        try {
            WebSocketRegistry registry = WebSocketRegistry.getInstance();
            // Store the message for the handler to process
            registry.onMessage(sessionId, message);
            // Return the message to echo back (handlers can use sendText for custom responses)
            return message;
        } catch (Exception e) {
            System.err.println("[NativeBridge] Error in onWebSocketMessage: " + e.getMessage());
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * Called from Rust when a WebSocket binary message is received.
     * Returns the response to send back, or null to echo the original data.
     */
    public static byte[] onWebSocketBinary(long sessionId, byte[] data) {
        try {
            WebSocketRegistry registry = WebSocketRegistry.getInstance();
            registry.onBinary(sessionId, data);
            // Return the data to echo back
            return data;
        } catch (Exception e) {
            System.err.println("[NativeBridge] Error in onWebSocketBinary: " + e.getMessage());
            return ("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Called from Rust when a WebSocket connection is closed.
     */
    public static void onWebSocketClose(long sessionId) {
        try {
            WebSocketRegistry registry = WebSocketRegistry.getInstance();
            registry.onClose(sessionId);
            System.out.println("[NativeBridge] WebSocket closed: sessionId=" + sessionId);
        } catch (Exception e) {
            System.err.println("[NativeBridge] Error in onWebSocketClose: " + e.getMessage());
        }
    }

    /**
     * Called from Rust when a WebSocket error occurs.
     */
    public static void onWebSocketError(long sessionId, String error) {
        try {
            WebSocketRegistry registry = WebSocketRegistry.getInstance();
            registry.onError(sessionId, error);
            System.err.println("[NativeBridge] WebSocket error: sessionId=" + sessionId + ", error=" + error);
        } catch (Exception e) {
            System.err.println("[NativeBridge] Error in onWebSocketError: " + e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}

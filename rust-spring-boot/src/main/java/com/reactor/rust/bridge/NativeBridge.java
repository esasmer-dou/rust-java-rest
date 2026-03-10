package com.reactor.rust.bridge;

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
}

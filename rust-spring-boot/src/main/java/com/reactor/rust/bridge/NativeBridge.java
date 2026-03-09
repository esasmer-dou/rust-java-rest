package com.reactor.rust.bridge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JNI bridge between Rust HTTP server and Java handlers.
 * Single entry point - no version variants.
 */
public class NativeBridge {

    private static final AtomicLong counter = new AtomicLong();

    static {
        loadNativeLibrary();
    }

    private static void loadNativeLibrary() {
        // 1. Try environment variable RUST_LIB_PATH
        String libPath = System.getenv("RUST_LIB_PATH");
        if (libPath != null && !libPath.isEmpty()) {
            String libFile = isWindows() ? libPath + "\\rust_hyper.dll" : libPath + "/rust_hyper.so";
            System.load(libFile);
            return;
        }

        // 2. Find project root (go up until we find rust-spring directory)
        String workDir = System.getProperty("user.dir");
        java.io.File dir = new java.io.File(workDir);

        // Go up to find the project root (where rust-spring and rust-spring-boot live)
        while (dir != null) {
            java.io.File rustSpringDir = new java.io.File(dir, "rust-spring");
            if (rustSpringDir.exists() && rustSpringDir.isDirectory()) {
                // Found project root
                String nativePath = isWindows()
                    ? new java.io.File(dir, "native\\rust_hyper.dll").getAbsolutePath()
                    : new java.io.File(dir, "native/rust_hyper.so").getAbsolutePath();
                java.io.File nativeFile = new java.io.File(nativePath);
                if (nativeFile.exists()) {
                    System.load(nativePath);
                    return;
                }

                String releasePath = isWindows()
                    ? new java.io.File(rustSpringDir, "target\\release\\rust_hyper.dll").getAbsolutePath()
                    : new java.io.File(rustSpringDir, "target/release/rust_hyper.so").getAbsolutePath();
                java.io.File releaseFile = new java.io.File(releasePath);
                if (releaseFile.exists()) {
                    System.load(releasePath);
                    return;
                }
                break;
            }
            dir = dir.getParentFile();
        }

        throw new UnsatisfiedLinkError(
            "Native library not found. Set RUST_LIB_PATH environment variable or ensure rust-spring/target/release/rust_hyper.dll exists"
        );
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
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

package com.reactor.rust.component;


import java.nio.ByteBuffer;

/**
 * @deprecated Direct-buffer lifetime is owned by the framework/native pools. Explicit cleaner
 * reflection is not portable on Java 21/OpenJ9 and must not be used for production memory policy.
 */
@Deprecated(forRemoval = true)
public final class CleanerUtils {

    private CleanerUtils() {}

    /**
     * Compatibility no-op. Drop references normally and use measured native idle-trim policy for
     * allocator retention; forcing a JDK-internal cleaner can invalidate borrowed buffers.
     */
    @Deprecated(forRemoval = true)
    public static void free(ByteBuffer buffer) {
        // Intentionally empty; retained for source and binary compatibility.
    }
}

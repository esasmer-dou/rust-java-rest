package com.reactor.rust.json;

import com.dslplatform.json.Configuration;
import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonWriter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;

/**
 * JSON serialization service using DSL-JSON 2.0.2.
 * Compile-time annotation processing ile ZERO overhead.
 *
 * OPTIMIZED: ThreadLocal JsonWriter reuse (Phase 1.1)
 */
public final class DslJsonService {

    // Initialize DSL-JSON with ServiceLoader to discover generated Configuration classes
    private static final DslJson<Object> DSL_JSON;

    static {
        DslJson<Object> json = null;
        System.err.println("[DslJsonService] === Starting initialization ===");
        System.err.flush();

        try {
            System.err.println("[DslJsonService] Step 1: Creating DslJson instance with explicit ClassLoader...");
            System.err.flush();

            // Use explicit ClassLoader to avoid null pointer in DSL-JSON's internal ServiceLoader
            ClassLoader classLoader = DslJsonService.class.getClassLoader();
            if (classLoader == null) {
                classLoader = ClassLoader.getSystemClassLoader();
            }
            System.err.println("[DslJsonService] ClassLoader: " + classLoader);
            System.err.flush();

            // Create DslJson with settings that use our ClassLoader
            json = new DslJson<>(new DslJson.Settings().includeServiceLoader(classLoader));
            System.err.println("[DslJsonService] Step 2: DslJson instance created successfully");
            System.err.flush();

            // Additional manual configuration loading for our generated converters
            int configCount = 0;
            System.err.println("[DslJsonService] Step 3: Loading additional configurations via ServiceLoader...");
            System.err.flush();

            ServiceLoader<Configuration> loader = ServiceLoader.load(Configuration.class, classLoader);
            for (Configuration config : loader) {
                System.err.println("[DslJsonService] Configuring: " + config.getClass().getName());
                System.err.flush();
                config.configure(json);
                configCount++;
            }
            System.err.println("[DslJsonService] Total configurations loaded: " + configCount);
            System.err.flush();

        } catch (Throwable e) {
            System.err.println("[DslJsonService] === EXCEPTION: " + e.getClass().getName() + " ===");
            System.err.println("[DslJsonService] Message: " + e.getMessage());
            System.err.flush();
            e.printStackTrace(System.err);
            System.err.flush();
            // Fallback to basic instance without ServiceLoader
            try {
                json = new DslJson<>(new DslJson.Settings());
            } catch (Throwable e2) {
                System.err.println("[DslJsonService] Fallback also failed: " + e2.getMessage());
                json = new DslJson<>();
            }
        }

        DSL_JSON = json;
        System.err.println("[DslJsonService] === Initialization complete ===");
        System.err.flush();
    }

    // Thread-local writer pool - eliminates allocation per call
    private static final ThreadLocal<JsonWriter> WRITER_CACHE =
        ThreadLocal.withInitial(() -> DSL_JSON.newWriter());

    // Pre-allocated null bytes
    private static final byte[] NULL_BYTES = "null".getBytes(StandardCharsets.UTF_8);

    private DslJsonService() {}

    /**
     * Serialize object to ByteBuffer (zero-copy compatible).
     *
     * @param obj Object to serialize
     * @param out Target ByteBuffer
     * @param offset Starting position in buffer
     * @return Number of bytes written
     */
    public static int writeToBuffer(Object obj, ByteBuffer out, int offset) {
        if (obj == null) {
            out.position(offset);
            out.put(NULL_BYTES);
            return NULL_BYTES.length;
        }

        try {
            // Reuse ThreadLocal writer - no allocation
            JsonWriter writer = WRITER_CACHE.get();
            writer.reset();
            DSL_JSON.serialize(writer, obj);

            // Write directly to buffer
            byte[] data = writer.getByteBuffer();
            int size = writer.size();

            // Check if buffer has enough capacity
            int remaining = out.capacity() - offset;
            if (size > remaining) {
                throw new RuntimeException("Buffer overflow: needed " + size + " bytes but only " + remaining + " available");
            }

            out.position(offset);
            out.put(data, 0, size);
            return size;
        } catch (Exception e) {
            String errorType = e.getClass().getName();
            String errorMsg = e.getMessage();
            String fullError = errorType + ": " + (errorMsg != null ? errorMsg : "no message");
            System.err.println("[DslJsonService] Serialization error: " + fullError);
            e.printStackTrace(System.err);
            throw new RuntimeException("Failed to serialize JSON: " + fullError, e);
        }
    }

    /**
     * Deserialize byte array to object.
     *
     * @param bytes JSON bytes
     * @param clazz Target class
     * @return Deserialized object
     */
    public static <T> T parse(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return DSL_JSON.deserialize(clazz, bytes, bytes.length);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Serialize object to byte array.
     * Uses ThreadLocal writer for reduced allocation.
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) return NULL_BYTES;
        try {
            // Reuse ThreadLocal writer - no allocation
            JsonWriter writer = WRITER_CACHE.get();
            writer.reset();
            DSL_JSON.serialize(writer, obj);
            return writer.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Get the size of serialized object without allocating byte array.
     * Useful for pre-sizing buffers.
     */
    public static int getSerializedSize(Object obj) {
        if (obj == null) return NULL_BYTES.length;
        try {
            JsonWriter writer = WRITER_CACHE.get();
            writer.reset();
            DSL_JSON.serialize(writer, obj);
            return writer.size();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get serialized size: " + e.getMessage(), e);
        }
    }
}

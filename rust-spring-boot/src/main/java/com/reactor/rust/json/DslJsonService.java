package com.reactor.rust.json;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonWriter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * JSON serialization service using DSL-JSON 2.0.2.
 * Compile-time annotation processing ile ZERO overhead.
 *
 * OPTIMIZED: ThreadLocal JsonWriter reuse (Phase 1.1)
 */
public final class DslJsonService {

    private static final DslJson<Object> DSL_JSON = new DslJson<>();

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

            out.position(offset);
            out.put(data, 0, size);
            return size;
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize JSON: " + e.getMessage(), e);
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

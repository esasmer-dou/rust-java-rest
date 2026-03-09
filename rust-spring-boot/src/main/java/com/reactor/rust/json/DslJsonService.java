package com.reactor.rust.json;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonWriter;

import java.nio.ByteBuffer;

/**
 * JSON serialization service using DSL-JSON 2.0.2.
 * Compile-time annotation processing ile ZERO overhead.
 */
public final class DslJsonService {

    private static final DslJson<Object> DSL_JSON = new DslJson<>();

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
        byte[] json = serialize(obj);
        out.position(offset);
        out.put(json);
        return json.length;
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
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) return "null".getBytes();
        try {
            JsonWriter writer = DSL_JSON.newWriter();
            DSL_JSON.serialize(writer, obj);
            return writer.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize JSON: " + e.getMessage(), e);
        }
    }
}

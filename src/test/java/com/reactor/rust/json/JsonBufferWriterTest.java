package com.reactor.rust.json;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonBufferWriterTest {

    @Test
    void reusableWriterResetsStateBetweenBuffersOnSameThread() {
        ByteBuffer firstBuffer = ByteBuffer.allocate(64);
        JsonBufferWriter first = JsonBufferWriter.reusable(firstBuffer, 0);
        first.beginObject().fieldInt("id", 1).endObject();
        int firstLength = first.result();

        ByteBuffer secondBuffer = ByteBuffer.allocate(64);
        JsonBufferWriter second = JsonBufferWriter.reusable(secondBuffer, 0);
        second.beginObject().fieldString("city", "İstanbul").endObject();
        int secondLength = second.result();

        assertSame(first, second);
        assertEquals("{\"id\":1}", read(firstBuffer, firstLength));
        assertEquals("{\"city\":\"İstanbul\"}", read(secondBuffer, secondLength));
    }

    @Test
    void reusableWriterReportsRequiredLengthWhenBufferIsTooSmall() {
        ByteBuffer small = ByteBuffer.allocate(8);
        JsonBufferWriter json = JsonBufferWriter.reusable(small, 0);

        json.beginObject().fieldString("city", "İstanbul").endObject();

        int result = json.result();
        assertTrue(result < 0);
        assertEquals("{\"city\":\"İstanbul\"}".getBytes(StandardCharsets.UTF_8).length, -result);
    }

    private static String read(ByteBuffer buffer, int length) {
        byte[] bytes = new byte[length];
        buffer.position(0);
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

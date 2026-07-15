package com.reactor.rust.json;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DslJsonServiceTest {

    @Test
    void writeErrorToBufferUsesSmallFallbackWhenMessageDoesNotFit() {
        ByteBuffer out = ByteBuffer.allocate(40);

        int written = DslJsonService.writeErrorToBuffer("x".repeat(512), out, 0);

        byte[] bytes = new byte[written];
        out.position(0);
        out.get(bytes);
        assertEquals("{\"error\":\"error response too large\"}", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void writeErrorToBufferRejectsBufferTooSmallForFallback() {
        ByteBuffer out = ByteBuffer.allocate(8);

        assertThrows(
                IllegalArgumentException.class,
                () -> DslJsonService.writeErrorToBuffer("boom", out, 0)
        );
    }

    @Test
    void writeErrorToBufferRejectsInvalidOffsetBeforeWriting() {
        ByteBuffer out = ByteBuffer.allocate(64);

        assertThrows(
                IllegalArgumentException.class,
                () -> DslJsonService.writeErrorToBuffer("boom", out, -1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> DslJsonService.writeErrorToBuffer("boom", out, out.limit() + 1)
        );
        assertEquals(0, out.position());
    }

    @Test
    void writeErrorToBufferEscapesAllJsonControlCharacters() {
        ByteBuffer out = ByteBuffer.allocate(128);

        int written = DslJsonService.writeErrorToBuffer("a\b\f\u0001b", out, 0);

        byte[] bytes = new byte[written];
        out.position(0);
        out.get(bytes);
        assertEquals("{\"error\":\"a\\b\\f\\u0001b\"}", new String(bytes, StandardCharsets.UTF_8));
    }
}

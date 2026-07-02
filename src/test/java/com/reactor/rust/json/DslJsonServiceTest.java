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
}

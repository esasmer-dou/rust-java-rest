package com.reactor.rust.exception;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpErrorMapperTest {

    @Test
    void mapsPublicRequestFailuresToStableStatuses() {
        assertEquals(400, HttpErrorMapper.map(new BadRequestException("invalid input")).status());
        assertEquals(404, HttpErrorMapper.map(new NotFoundException("missing")).status());
        assertEquals(405, HttpErrorMapper.map(new MethodNotAllowedException("POST", "/items")).status());
        assertEquals(503, HttpErrorMapper.map(new RejectedExecutionException("queue full")).status());
        assertEquals(504, HttpErrorMapper.map(new CompletionException(new TimeoutException("slow"))).status());
    }

    @Test
    void hidesInternalMessagesAndEscapesControlCharacters() {
        HttpErrorMapper.MappedError internal = HttpErrorMapper.map(
                new IllegalStateException("jdbc:postgresql://secret-host/password")
        );

        assertEquals(500, internal.status());
        assertEquals("Internal server error", internal.message());

        byte[] json = HttpErrorMapper.toJsonBytes(
                new HttpErrorMapper.MappedError(400, "bad_request", "bad\u0001\n\"value")
        );
        String body = new String(json, StandardCharsets.UTF_8);
        assertTrue(body.contains("\\u0001"));
        assertTrue(body.contains("\\n"));
        assertTrue(body.contains("\\\"value"));
        assertFalse(body.contains("bad\u0001"));
    }
}

package com.reactor.rust.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseEntityTest {

    @Test
    void headerlessResponseDoesNotMaterializeMutableHeadersForEncoding() {
        ResponseEntity<String> response = ResponseEntity.ok("ok");

        Map<String, String> first = response.readOnlyHeaders();
        Map<String, String> second = response.readOnlyHeaders();

        assertSame(first, second);
        assertTrue(first.isEmpty());
    }

    @Test
    void getHeadersRemainsMutableForCompatibility() {
        ResponseEntity<String> response = ResponseEntity.ok("ok");
        Map<String, String> encodedView = response.readOnlyHeaders();

        response.getHeaders().put("X-Trace", "abc");

        assertNotSame(encodedView, response.readOnlyHeaders());
        assertEquals("abc", response.readOnlyHeaders().get("X-Trace"));
    }

    @Test
    void bodyBuilderPreservesHeadersWithoutTouchingMutableCompatibilityApi() {
        HttpResponse<Void> builder = HttpResponse.<Void>status(HttpStatus.ACCEPTED)
                .header("X-Request-ID", "42");

        HttpResponse<String> response = builder.body("accepted");

        assertEquals(HttpStatus.ACCEPTED, response.getStatus());
        assertEquals("accepted", response.getBody());
        assertEquals("42", response.readOnlyHeaders().get("X-Request-ID"));
    }
}

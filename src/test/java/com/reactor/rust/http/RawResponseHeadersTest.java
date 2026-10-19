package com.reactor.rust.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RawResponseHeadersTest {
    @Test
    void jsonHeaderFastPathPreservesOwnedBytesAndMutation() {
        RawResponse first = RawResponse.json(new byte[0]);
        RawResponse second = RawResponse.json(new byte[0]);
        byte[] a = first.getEncodedHeaders();
        byte[] b = second.getEncodedHeaders();
        assertEquals("Content-Type: application/json; charset=utf-8\n", new String(a, StandardCharsets.UTF_8));
        assertNotSame(a, b);
        a[0] = '!';
        assertEquals('C', b[0]);
        assertSame(a, first.getEncodedHeaders());
        first.header("Content-Type", "text/plain");
        assertEquals("Content-Type: text/plain; charset=utf-8\n",
                new String(first.getEncodedHeaders(), StandardCharsets.UTF_8));
    }

    @Test
    void customHeadersKeepFullEncoding() {
        RawResponse response = RawResponse.json(new byte[0]).header("X-Correlation-Id", "request-1");
        String encoded = new String(response.getEncodedHeaders(), StandardCharsets.UTF_8);
        assertTrue(encoded.contains("Content-Type: application/json; charset=utf-8\n"));
        assertTrue(encoded.contains("X-Correlation-Id: request-1\n"));
        response.getHeaders().remove("X-Correlation-Id");
        assertEquals("Content-Type: application/json; charset=utf-8\n",
                new String(response.getEncodedHeaders(), StandardCharsets.UTF_8));
    }
}

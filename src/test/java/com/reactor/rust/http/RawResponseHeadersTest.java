package com.reactor.rust.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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

    @Test
    void readingJsonHeadersPreservesCachedEncodedBytes() {
        RawResponse response = RawResponse.json(new byte[0]);
        byte[] encoded = response.getEncodedHeaders();
        encoded[0] = '!';

        Map<String, String> headers = response.getHeaders();
        assertEquals(Map.of("Content-Type", MediaType.APPLICATION_JSON_UTF8), headers);
        assertSame(headers, response.getHeaders());
        assertSame(encoded, response.getEncodedHeaders());
        assertEquals('!', response.getEncodedHeaders()[0]);
    }

    @Test
    void jsonHeaderMapIsMutableBeforeEncoding() {
        RawResponse response = RawResponse.json(new byte[0]);
        Map<String, String> headers = response.getHeaders();

        assertEquals(MediaType.APPLICATION_JSON_UTF8, headers.put("Content-Type", "text/plain"));
        headers.putAll(Map.of("X-Correlation-Id", "request-1"));
        String encoded = new String(response.getEncodedHeaders(), StandardCharsets.UTF_8);
        assertTrue(encoded.contains("Content-Type: text/plain; charset=utf-8\n"));
        assertTrue(encoded.contains("X-Correlation-Id: request-1\n"));
    }

    @Test
    void jsonHeaderMapMutationsInvalidateCachedBytes() {
        RawResponse response = RawResponse.json(new byte[0]);
        byte[] original = response.getEncodedHeaders();
        Map<String, String> headers = response.getHeaders();

        headers.put("Content-Type", "text/plain");
        byte[] changed = response.getEncodedHeaders();
        assertNotSame(original, changed);
        assertEquals("Content-Type: text/plain; charset=utf-8\n",
                new String(changed, StandardCharsets.UTF_8));

        headers.remove("Content-Type");
        assertEquals(0, response.getEncodedHeaders().length);
        headers.putAll(Map.of("Content-Type", "application/problem+json"));
        assertEquals("Content-Type: application/problem+json; charset=utf-8\n",
                new String(response.getEncodedHeaders(), StandardCharsets.UTF_8));
        headers.clear();
        assertEquals(0, response.getEncodedHeaders().length);
    }

    @Test
    void materializedJsonHeadersRemainResponseLocal() {
        RawResponse first = RawResponse.json(new byte[0]);
        RawResponse second = RawResponse.json(new byte[0]);
        first.getHeaders().clear();

        assertEquals(0, first.getEncodedHeaders().length);
        assertEquals(Map.of("Content-Type", MediaType.APPLICATION_JSON_UTF8), second.getHeaders());
        assertEquals("Content-Type: application/json; charset=utf-8\n",
                new String(second.getEncodedHeaders(), StandardCharsets.UTF_8));
    }

    @Test
    void jsonPreservesBodyAndHeapResponseDefaults() {
        byte[] body = {1, 2, 3};
        RawResponse response = RawResponse.json(body);

        assertSame(body, response.getBody());
        assertEquals(0, response.getNativeId());
        assertFalse(response.ownsNativeResponse());
        assertEquals(0, RawResponse.json(null).getBody().length);
    }

    @Test
    void ordinaryFactoriesPreserveTextualNormalization() {
        assertEquals("text/plain; charset=utf-8",
                RawResponse.text("body", "text/plain").getHeaders().get("Content-Type"));
        assertEquals("application/problem+json; charset=utf-8",
                RawResponse.bytes(new byte[0], "application/problem+json").getHeaders().get("Content-Type"));
        assertEquals("application/octet-stream",
                RawResponse.bytes(new byte[0], null).getHeaders().get("Content-Type"));
    }
}

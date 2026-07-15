package com.reactor.rust.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonResponsesTest {

    @Test
    void writesUtf8AndEscapesControlCharacters() {
        RawResponse response = JsonResponses.stringField("başlık", "Çağrı \"hazır\"\n");

        assertEquals(
                "{\"başlık\":\"Çağrı \\\"hazır\\\"\\n\"}",
                new String(response.getBody(), StandardCharsets.UTF_8));
        assertEquals(MediaType.APPLICATION_JSON_UTF8, response.getHeaders().get("Content-Type"));
    }

    @Test
    void writesStableErrorContract() {
        RawResponse response = JsonResponses.error("cache_miss", "Henüz hazır değil");

        assertEquals(
                "{\"code\":\"cache_miss\",\"message\":\"Henüz hazır değil\"}",
                new String(response.getBody(), StandardCharsets.UTF_8));
    }
}

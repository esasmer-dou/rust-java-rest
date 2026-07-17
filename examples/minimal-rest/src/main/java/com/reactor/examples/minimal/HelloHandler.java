package com.reactor.examples.minimal;

import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

public final class HelloHandler {

    private static final byte[] BODY =
            "{\"status\":\"UP\",\"service\":\"minimal-rest\"}".getBytes(StandardCharsets.UTF_8);

    @GetMapping(value = "/app/health", responseType = RawResponse.class)
    public ResponseEntity<RawResponse> health() {
        return ResponseEntity.ok(RawResponse.json(BODY));
    }
}

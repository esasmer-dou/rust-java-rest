package com.reactor.rust.starter.openapi;

import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;

/** Small bundled contract viewer, installed only when explicitly enabled. */
public final class OpenApiUiRoute {
    private final RawResponse ui;

    OpenApiUiRoute(RawResponse ui) {
        this.ui = ui;
    }

    @GetMapping(value = "/docs", responseType = RawResponse.class)
    public ResponseEntity<RawResponse> ui() {
        return ResponseEntity.ok(ui);
    }
}

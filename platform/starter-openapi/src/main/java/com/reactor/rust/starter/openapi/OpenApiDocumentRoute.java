package com.reactor.rust.starter.openapi;

import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;

/** Build-time generated contract route, installed only when OpenAPI is enabled. */
public final class OpenApiDocumentRoute {
    private final RawResponse document;

    OpenApiDocumentRoute(RawResponse document) {
        this.document = document;
    }

    @GetMapping(value = "/openapi.json", responseType = RawResponse.class)
    public ResponseEntity<RawResponse> document() {
        return ResponseEntity.ok(document);
    }
}

package com.reactor.rust.http;

import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.exception.HttpErrorMapper;

/** Same error contract for sync handlers and CompletionStage.exceptionally(ErrorResponses::from). */
public final class ErrorResponses {
    private ErrorResponses() {}

    public static ResponseEntity<RawResponse> from(Throwable error) {
        return from(HttpErrorMapper.map(error));
    }

    public static ResponseEntity<RawResponse> from(HttpErrorMapper.MappedError error) {
        boolean problem = "problem-details".equalsIgnoreCase(PropertiesLoader.get("reactor.rust.errors.format", "problem-details"));
        return ResponseEntity.status(HttpStatus.valueOf(error.status()), RawResponse.bytes(
                HttpErrorMapper.toJsonBytes(error), problem ? "application/problem+json; charset=utf-8" : MediaType.APPLICATION_JSON_UTF8));
    }
}

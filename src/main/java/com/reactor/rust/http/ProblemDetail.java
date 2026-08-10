package com.reactor.rust.http;

import com.dslplatform.json.CompiledJson;

/** RFC 9457-compatible error body for application exception handlers. */
@CompiledJson
public record ProblemDetail(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code) {

    public ProblemDetail {
        type = type == null || type.isBlank() ? "about:blank" : type;
    }

    public static ProblemDetail of(HttpStatus status, String detail) {
        return new ProblemDetail(
                "about:blank",
                status.name(),
                status.getCode(),
                detail,
                null,
                null);
    }

    public ProblemDetail withInstance(String instance) {
        return new ProblemDetail(type, title, status, detail, instance, code);
    }

    public ProblemDetail withCode(String code) {
        return new ProblemDetail(type, title, status, detail, instance, code);
    }
}

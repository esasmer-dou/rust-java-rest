package com.reactor.rust.http;

import java.util.Map;

/**
 * Concise response API for handlers that need a custom status or headers.
 *
 * <p>Handlers may still return a DTO directly for the allocation-minimal 200 response path.</p>
 */
public final class HttpResponse<T> extends ResponseEntity<T> {

    private HttpResponse(T body, HttpStatus status) {
        super(body, status);
    }

    private HttpResponse(T body, HttpStatus status, Map<String, String> headers) {
        super(body, status, headers);
    }

    public static <T> HttpResponse<T> ok(T body) {
        return new HttpResponse<>(body, HttpStatus.OK);
    }

    public static <T> HttpResponse<T> created(T body) {
        return new HttpResponse<>(body, HttpStatus.CREATED);
    }

    public static <T> HttpResponse<T> accepted(T body) {
        return new HttpResponse<>(body, HttpStatus.ACCEPTED);
    }

    public static <T> HttpResponse<T> noContent() {
        return new HttpResponse<>(null, HttpStatus.NO_CONTENT);
    }

    public static <T> HttpResponse<T> badRequest(T body) {
        return new HttpResponse<>(body, HttpStatus.BAD_REQUEST);
    }

    public static <T> HttpResponse<T> notFound(T body) {
        return new HttpResponse<>(body, HttpStatus.NOT_FOUND);
    }

    public static <T> HttpResponse<T> internalServerError(T body) {
        return new HttpResponse<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public static <T> HttpResponse<T> status(HttpStatus status) {
        return new HttpResponse<>(null, status);
    }

    public static <T> HttpResponse<T> status(HttpStatus status, T body) {
        return new HttpResponse<>(body, status);
    }

    @Override
    public HttpResponse<T> header(String name, String value) {
        super.header(name, value);
        return this;
    }

    @Override
    public <B> HttpResponse<B> body(B body) {
        return new HttpResponse<>(body, getStatus(), readOnlyHeaders());
    }
}

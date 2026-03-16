package com.reactor.rust.exception;

import com.dslplatform.json.CompiledJson;

/**
 * Standard error response record.
 * Used by exception handlers to return consistent error responses.
 */
@CompiledJson
public record ErrorResponse(
    int code,
    String message
) {
    /**
     * Create a 400 Bad Request error.
     */
    public static ErrorResponse badRequest(String message) {
        return new ErrorResponse(400, message);
    }

    /**
     * Create a 404 Not Found error.
     */
    public static ErrorResponse notFound(String message) {
        return new ErrorResponse(404, message);
    }

    /**
     * Create a 500 Internal Server Error.
     */
    public static ErrorResponse internalError(String message) {
        return new ErrorResponse(500, message);
    }
}

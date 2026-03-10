package com.reactor.rust.exception;

/**
 * Exception thrown when request validation fails.
 * Results in HTTP 400 Bad Request.
 */
public class BadRequestException extends RuntimeException {

    private final String field;
    private final Object invalidValue;

    public BadRequestException(String message) {
        super(message);
        this.field = null;
        this.invalidValue = null;
    }

    public BadRequestException(String field, String message, Object invalidValue) {
        super(message);
        this.field = field;
        this.invalidValue = invalidValue;
    }

    public String getField() {
        return field;
    }

    public Object getInvalidValue() {
        return invalidValue;
    }
}

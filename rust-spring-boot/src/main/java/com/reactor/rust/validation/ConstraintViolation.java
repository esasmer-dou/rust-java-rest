package com.reactor.rust.validation;

/**
 * Represents a single validation constraint violation.
 */
public class ConstraintViolation {

    private final String field;
    private final String message;
    private final Object invalidValue;

    public ConstraintViolation(String field, String message, Object invalidValue) {
        this.field = field;
        this.message = message;
        this.invalidValue = invalidValue;
    }

    public String getField() {
        return field;
    }

    public String getMessage() {
        return message;
    }

    public Object getInvalidValue() {
        return invalidValue;
    }

    @Override
    public String toString() {
        return "ConstraintViolation{" +
                "field='" + field + '\'' +
                ", message='" + message + '\'' +
                ", invalidValue=" + invalidValue +
                '}';
    }
}

package com.reactor.rust.exception;

import com.reactor.rust.validation.ValidationResult;

/**
 * Exception thrown when validation fails.
 * Results in HTTP 400 Bad Request with detailed error messages.
 */
public class ValidationException extends RuntimeException {

    private final ValidationResult validationResult;

    public ValidationException(ValidationResult validationResult) {
        super(validationExceptionMessage(validationResult));
        this.validationResult = validationResult;
    }

    public ValidationException(String message) {
        super(message);
        this.validationResult = null;
    }

    public ValidationResult getValidationResult() {
        return validationResult;
    }

    private static String validationExceptionMessage(ValidationResult result) {
        if (result == null) {
            return "Validation failed";
        }
        return result.getErrorMessages();
    }
}

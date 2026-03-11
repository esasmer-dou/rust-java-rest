package com.reactor.rust.di.exception;

/**
 * Exception thrown when bean creation fails.
 */
public class BeanCreationException extends RuntimeException {

    public BeanCreationException(String message) {
        super(message);
    }

    public BeanCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}

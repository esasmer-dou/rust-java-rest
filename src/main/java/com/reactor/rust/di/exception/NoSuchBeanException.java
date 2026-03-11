package com.reactor.rust.di.exception;

/**
 * Exception thrown when a required bean is not found in the container.
 */
public class NoSuchBeanException extends RuntimeException {

    public NoSuchBeanException(String message) {
        super(message);
    }

    public NoSuchBeanException(String message, Throwable cause) {
        super(message, cause);
    }
}

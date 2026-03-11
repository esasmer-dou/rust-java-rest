package com.reactor.rust.di.exception;

/**
 * Exception thrown when circular dependency is detected.
 */
public class CircularDependencyException extends RuntimeException {

    public CircularDependencyException(String message) {
        super(message);
    }
}

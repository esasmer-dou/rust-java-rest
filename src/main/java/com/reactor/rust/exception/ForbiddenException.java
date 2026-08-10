package com.reactor.rust.exception;

public final class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}

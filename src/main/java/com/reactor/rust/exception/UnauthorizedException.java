package com.reactor.rust.exception;

public final class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) { super(message); }
}

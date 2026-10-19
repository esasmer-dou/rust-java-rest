package com.reactor.rust.exception;

/** A public, client-safe business conflict, not a transport retry signal. */
public final class ConflictException extends RuntimeException {
    public ConflictException(String publicMessage) { super(publicMessage); }
}

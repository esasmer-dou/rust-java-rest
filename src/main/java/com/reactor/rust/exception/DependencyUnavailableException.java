package com.reactor.rust.exception;

/** The cause is retained for diagnostics but is never exposed in the HTTP response. */
public final class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(Throwable cause) { super("Dependency unavailable", cause); }
}

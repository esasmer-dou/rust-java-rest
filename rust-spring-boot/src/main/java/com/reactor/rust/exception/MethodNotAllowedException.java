package com.reactor.rust.exception;

/**
 * Exception thrown when HTTP method is not allowed.
 * Results in HTTP 405 Method Not Allowed.
 */
public class MethodNotAllowedException extends RuntimeException {

    private final String method;
    private final String path;

    public MethodNotAllowedException(String method, String path) {
        super("Method " + method + " not allowed for " + path);
        this.method = method;
        this.path = path;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }
}

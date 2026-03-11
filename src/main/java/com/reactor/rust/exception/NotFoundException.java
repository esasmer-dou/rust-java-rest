package com.reactor.rust.exception;

/**
 * Exception thrown when a resource is not found.
 * Results in HTTP 404 Not Found.
 */
public class NotFoundException extends RuntimeException {

    private final String resource;
    private final String identifier;

    public NotFoundException(String message) {
        super(message);
        this.resource = null;
        this.identifier = null;
    }

    public NotFoundException(String resource, String identifier) {
        super(resource + " not found with identifier: " + identifier);
        this.resource = resource;
        this.identifier = identifier;
    }

    public String getResource() {
        return resource;
    }

    public String getIdentifier() {
        return identifier;
    }
}

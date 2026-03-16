package com.reactor.rust.http;

/**
 * Utility class for creating HTTP redirect responses.
 * Supports 301 (Permanent), 302 (Found), 303 (See Other), 307 (Temporary), 308 (Permanent) redirects.
 */
public final class RedirectResponse {

    private final String location;
    private final HttpStatus status;

    private RedirectResponse(String location, HttpStatus status) {
        this.location = location;
        this.status = status;
    }

    /**
     * Get the redirect location URL.
     */
    public String getLocation() {
        return location;
    }

    /**
     * Get the HTTP status code for the redirect.
     */
    public HttpStatus getStatus() {
        return status;
    }

    /**
     * Create a 301 Moved Permanently redirect.
     * Use for permanent URL changes - browsers will cache this.
     */
    public static RedirectResponse movedPermanently(String location) {
        return new RedirectResponse(location, HttpStatus.MOVED_PERMANENTLY);
    }

    /**
     * Create a 302 Found redirect (temporary).
     * Default for temporary redirects. Changes method to GET for historical reasons.
     */
    public static RedirectResponse found(String location) {
        return new RedirectResponse(location, HttpStatus.FOUND);
    }

    /**
     * Create a 303 See Other redirect.
     * Used after POST/PUT to redirect to a new resource (forces GET).
     */
    public static RedirectResponse seeOther(String location) {
        return new RedirectResponse(location, HttpStatus.SEE_OTHER);
    }

    /**
     * Create a 307 Temporary Redirect.
     * Preserves the request method (POST stays POST).
     */
    public static RedirectResponse temporaryRedirect(String location) {
        return new RedirectResponse(location, HttpStatus.TEMPORARY_REDIRECT);
    }

    /**
     * Create a 308 Permanent Redirect.
     * Preserves the request method (POST stays POST).
     */
    public static RedirectResponse permanentRedirect(String location) {
        return new RedirectResponse(location, HttpStatus.PERMANENT_REDIRECT);
    }

    /**
     * Create ResponseEntity from this redirect.
     */
    public ResponseEntity<Void> toResponseEntity() {
        ResponseEntity<Void> entity = ResponseEntity.status(status, null);
        entity.header("Location", location);
        return entity;
    }

    @Override
    public String toString() {
        return "RedirectResponse{" +
                "location='" + location + '\'' +
                ", status=" + status +
                '}';
    }
}

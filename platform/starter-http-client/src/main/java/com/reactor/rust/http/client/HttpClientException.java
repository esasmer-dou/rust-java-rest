package com.reactor.rust.http.client;

public class HttpClientException extends RuntimeException {
    private final int status;

    protected HttpClientException(String message, int status, Throwable cause,
                                  boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
        this.status = status;
    }

    // Distinguishes an actual HTTP status from local failures also represented as 502/503.
    static final class ResponseStatus extends HttpClientException {
        ResponseStatus(int status) {
            super("Outbound HTTP response status " + status, status);
        }
    }

    public HttpClientException(String message, int status) {
        super(message);
        this.status = status;
    }

    public HttpClientException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public HttpClientException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}

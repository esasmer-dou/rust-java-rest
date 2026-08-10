package com.reactor.rust.http.client;

public final class HttpClientException extends RuntimeException {
    private final int status;

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

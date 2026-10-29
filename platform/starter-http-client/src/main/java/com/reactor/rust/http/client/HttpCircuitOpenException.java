package com.reactor.rust.http.client;

/** Shared immutable, stackless rejection. Never attach request-specific data to it. */
public final class HttpCircuitOpenException extends HttpClientException
        implements com.reactor.rust.exception.DependencyFailure {
    private static final long serialVersionUID = 1L;
    public static final HttpCircuitOpenException INSTANCE = new HttpCircuitOpenException();

    private HttpCircuitOpenException() {
        super("Outbound HTTP circuit is open", 503, null, false, false);
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    @Override
    public Throwable initCause(Throwable cause) {
        return this;
    }

    private Object readResolve() {
        return INSTANCE;
    }
}

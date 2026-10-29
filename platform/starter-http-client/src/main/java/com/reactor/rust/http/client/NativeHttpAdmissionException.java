package com.reactor.rust.http.client;

/** JNI can construct this String-only exception without executing completion/business code. */
public final class NativeHttpAdmissionException extends HttpClientException {
    public NativeHttpAdmissionException(String message) { super(message, 503); }
}

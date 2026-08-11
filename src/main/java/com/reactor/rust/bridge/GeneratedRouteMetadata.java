package com.reactor.rust.bridge;

import java.util.Objects;

/** Build-time route mapping metadata retained only until native route registration completes. */
public record GeneratedRouteMetadata(
        String httpMethod,
        String path,
        Class<?> requestType,
        Class<?> responseType,
        long maxRequestBodyBytes,
        long maxResponseBodyBytes) {

    public GeneratedRouteMetadata {
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(responseType, "responseType");
    }
}

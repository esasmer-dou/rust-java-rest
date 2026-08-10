package com.reactor.rust.http.client;

import java.net.http.HttpHeaders;

/** Typed response wrapper for callers that need status or headers. */
public record HttpClientResponse<T>(int status, HttpHeaders headers, T body) {}

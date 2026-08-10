package com.reactor.rust.tracing;

/** Completed Java handler invocation span. */
public record TraceSpan(
        String name,
        String traceId,
        String spanId,
        long durationNanos,
        boolean sampled,
        boolean success,
        String errorType) {}

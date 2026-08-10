package com.reactor.rust.tracing;

/** Optional non-blocking trace export hook. Implementations must never block handler threads. */
@FunctionalInterface
public interface TraceExporter {
    void export(TraceSpan span);
}

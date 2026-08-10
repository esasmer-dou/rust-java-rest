package com.reactor.rust.tracing;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class CapturingTraceExporter implements TraceExporter {
    static final AtomicInteger CREATIONS = new AtomicInteger();
    static final ConcurrentLinkedQueue<TraceSpan> SPANS = new ConcurrentLinkedQueue<>();

    public CapturingTraceExporter() {
        CREATIONS.incrementAndGet();
    }

    @Override
    public void export(TraceSpan span) {
        SPANS.add(span);
    }

    static void reset() {
        CREATIONS.set(0);
        SPANS.clear();
    }
}

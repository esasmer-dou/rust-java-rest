package com.reactor.rust.tracing;

import java.util.Objects;
import java.util.Optional;

/** W3C trace context available while Java business code is being invoked. */
public record TraceContext(String traceId, String spanId, boolean sampled) {
    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    public TraceContext {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(spanId, "spanId");
    }

    public static Optional<TraceContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static TraceContext currentOrNull() {
        return CURRENT.get();
    }

    public String traceparent() {
        return "00-" + traceId + '-' + spanId + (sampled ? "-01" : "-00");
    }

    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        TraceContext captured = CURRENT.get();
        if (captured == null) return task;
        return () -> {
            TraceContext previous = CURRENT.get();
            CURRENT.set(captured);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    static void set(TraceContext context) {
        CURRENT.set(context);
    }

    static void clear() {
        CURRENT.remove();
    }

    private static void restore(TraceContext previous) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}

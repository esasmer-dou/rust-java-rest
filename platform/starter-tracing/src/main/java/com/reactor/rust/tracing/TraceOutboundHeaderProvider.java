package com.reactor.rust.tracing;

import com.reactor.rust.http.client.OutboundHeaderProvider;

import java.util.function.BiConsumer;

/** Propagates the active W3C trace context to generated outbound HTTP clients. */
public final class TraceOutboundHeaderProvider implements OutboundHeaderProvider {
    @Override
    public void contribute(BiConsumer<String, String> headers) {
        TraceContext context = TraceContext.currentOrNull();
        if (context != null) headers.accept("traceparent", context.traceparent());
    }
}

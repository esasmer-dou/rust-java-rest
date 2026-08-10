package com.reactor.rust.tracing;

import com.reactor.rust.bridge.RequestGuard;
import com.reactor.rust.bridge.RequestGuardContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracingRequestGuardFactoryTest {
    @BeforeEach
    void reset() {
        CapturingTraceExporter.reset();
        System.clearProperty("reactor.tracing.enabled");
        System.clearProperty("reactor.tracing.annotated-only");
        System.clearProperty("reactor.tracing.sample-ratio");
    }

    @AfterEach
    void cleanup() {
        TraceContext.clear();
        reset();
    }

    @Test
    void disabledStarterDoesNotLoadExporter() throws Exception {
        TracingRequestGuardFactory factory = new TracingRequestGuardFactory();

        assertNull(factory.create(Handler.class, method()));
        assertEquals(0, CapturingTraceExporter.CREATIONS.get());
    }

    @Test
    void exportsAsyncFailureOnlyWhenStageCompletesAndClearsWorkerContext() throws Exception {
        System.setProperty("reactor.tracing.enabled", "true");
        System.setProperty("reactor.tracing.sample-ratio", "1.0");
        TracingRequestGuardFactory factory = new TracingRequestGuardFactory();
        RequestGuard guard = factory.create(Handler.class, method());
        String traceId = "0123456789abcdef0123456789abcdef";
        guard.before(new RequestGuardContext(
                "", "", "traceparent: 00-" + traceId + "-0123456789abcdef-01\n", new byte[0]));
        CompletableFuture<String> source = new CompletableFuture<>();

        var wrapped = guard.afterAsync(source);

        assertFalse(TraceContext.current().isPresent());
        assertTrue(CapturingTraceExporter.SPANS.isEmpty());
        source.completeExceptionally(new IllegalStateException("failed"));
        try {
            wrapped.toCompletableFuture().join();
        } catch (CompletionException ignored) {
        }
        TraceSpan span = CapturingTraceExporter.SPANS.poll();
        assertNotNull(span);
        assertEquals(traceId, span.traceId());
        assertFalse(span.success());
        assertEquals(IllegalStateException.class.getName(), span.errorType());
    }

    private static Method method() throws NoSuchMethodException {
        return Handler.class.getDeclaredMethod("handle");
    }

    static final class Handler {
        @Traced("orders.fetch")
        void handle() {}
    }
}

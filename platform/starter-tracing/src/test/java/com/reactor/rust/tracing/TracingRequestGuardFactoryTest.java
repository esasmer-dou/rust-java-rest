package com.reactor.rust.tracing;

import com.reactor.rust.bridge.RequestGuard;
import com.reactor.rust.bridge.RequestGuardContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals("0123456789abcdef", span.parentSpanId());
        assertFalse(span.success());
        assertEquals(IllegalStateException.class.getName(), span.errorType());
        assertNull(TraceContext.currentOrNull());
        assertTrue(CapturingTraceExporter.SPANS.isEmpty());
    }

    @Test
    void keepsSevenArgumentSpanConstructorAndAddsParentAsEighthComponent() {
        TraceSpan root = new TraceSpan("name", "trace", "span", 42L, true, true, "");
        TraceSpan child = new TraceSpan("name", "trace", "span", 42L, true, true, "", "parent");

        assertNull(root.parentSpanId());
        assertEquals("parent", child.parentSpanId());
        assertEquals("parentSpanId", TraceSpan.class.getRecordComponents()[7].getName());
    }

    @Test
    void exportsRemoteParentAndPropagatesNewLocalSpanInsteadOfRemoteSpan() throws Exception {
        RequestGuard guard = enabledGuard();
        String traceId = "0123456789abcdef0123456789abcdef";
        String parentId = "fedcba9876543210";
        guard.before(request("fe-" + traceId + '-' + parentId + "-a9-opaque-extension"));
        TraceContext active = TraceContext.currentOrNull();
        assertNotNull(active);
        assertEquals(traceId, active.traceId());
        assertNotEquals(parentId, active.spanId());
        AtomicReference<String> outbound = new AtomicReference<>();
        new TraceOutboundHeaderProvider().contribute((name, value) -> {
            assertEquals("traceparent", name);
            outbound.set(value);
        });
        assertEquals(active.traceparent(), outbound.get());

        guard.after();

        TraceSpan span = CapturingTraceExporter.SPANS.poll();
        assertNotNull(span);
        assertEquals(traceId, span.traceId());
        assertEquals(active.spanId(), span.spanId());
        assertEquals(parentId, span.parentSpanId());
        assertEquals("orders.fetch", span.name());
        assertTrue(span.success());
        assertTrue(span.sampled());
        assertTrue(span.durationNanos() >= 0L);
        assertNull(TraceContext.currentOrNull());
        new TraceOutboundHeaderProvider().contribute((name, value) -> {
            throw new AssertionError("No outbound header without active context");
        });
    }

    @Test
    void missingOrInvalidParentsStartIndependentRootSpans() throws Exception {
        RequestGuard guard = enabledGuard();
        String traceId = "0123456789abcdef0123456789abcdef";
        for (String header : new String[]{null, "invalid", "ff-" + traceId + "-0123456789abcdef-01",
                "00-" + traceId + "-0000000000000000-01",
                "00-" + traceId + "-0123456789abcdef-01-extra"}) {
            guard.before(request(header));
            TraceContext active = TraceContext.currentOrNull();
            assertNotNull(active);
            assertNotEquals(traceId, active.traceId());
            assertNotNull(W3cTraceparent.parse(active.traceparent()));

            guard.after();

            TraceSpan span = CapturingTraceExporter.SPANS.poll();
            assertNotNull(span);
            assertNull(span.parentSpanId());
            assertNull(TraceContext.currentOrNull());
        }
    }

    @Test
    void unsampledRemoteParentOverridesLocalSamplingAndAsyncCleanupReturnsOriginalStage() throws Exception {
        RequestGuard guard = enabledGuard();
        guard.before(request("01-0123456789abcdef0123456789abcdef-0123456789abcdef-fe-extra"));
        assertFalse(TraceContext.currentOrNull().sampled());
        CompletableFuture<String> source = new CompletableFuture<>();

        assertSame(source, guard.afterAsync(source));

        assertNull(TraceContext.currentOrNull());
        source.complete("ok");
        assertTrue(CapturingTraceExporter.SPANS.isEmpty());
    }

    @Test
    void sampledRemoteParentOverridesZeroLocalSamplingRatio() throws Exception {
        System.setProperty("reactor.tracing.enabled", "true");
        System.setProperty("reactor.tracing.sample-ratio", "0.0");
        RequestGuard guard = new TracingRequestGuardFactory().create(Handler.class, method());
        String parentId = "0123456789abcdef";
        guard.before(request("00-0123456789abcdef0123456789abcdef-" + parentId + "-ff"));
        assertTrue(TraceContext.currentOrNull().sampled());

        guard.after();

        TraceSpan span = CapturingTraceExporter.SPANS.poll();
        assertNotNull(span);
        assertEquals(parentId, span.parentSpanId());
        assertNull(TraceContext.currentOrNull());
    }

    @Test
    void asyncSuccessRetainsItsParentAcrossWorkerReuseWithoutClearingCompletionThreadContext() throws Exception {
        RequestGuard guard = enabledGuard();
        String traceId = "0123456789abcdef0123456789abcdef";
        String firstParent = "1111111111111111";
        String secondParent = "2222222222222222";
        guard.before(request("00-" + traceId + '-' + firstParent + "-01"));
        String firstSpan = TraceContext.currentOrNull().spanId();
        CompletableFuture<String> source = new CompletableFuture<>();
        var wrapped = guard.afterAsync(source);
        assertNull(TraceContext.currentOrNull());
        guard.before(request("00-" + traceId + '-' + secondParent + "-01"));
        TraceContext secondContext = TraceContext.currentOrNull();

        source.complete("ok");

        assertEquals("ok", wrapped.toCompletableFuture().join());
        assertSame(secondContext, TraceContext.currentOrNull());
        TraceSpan first = CapturingTraceExporter.SPANS.poll();
        assertNotNull(first);
        assertEquals(firstParent, first.parentSpanId());
        assertEquals(firstSpan, first.spanId());
        assertTrue(first.success());
        guard.after(new IllegalStateException("second"));
        TraceSpan second = CapturingTraceExporter.SPANS.poll();
        assertNotNull(second);
        assertEquals(secondParent, second.parentSpanId());
        assertEquals(secondContext.spanId(), second.spanId());
        assertFalse(second.success());
        assertNull(TraceContext.currentOrNull());
        guard.after();
        assertTrue(CapturingTraceExporter.SPANS.isEmpty());
    }

    @Test
    void wrappedTasksRestorePreviousContextOnSuccessAndFailure() {
        TraceContext captured = new TraceContext("1".repeat(32), "1".repeat(16), true);
        TraceContext previous = new TraceContext("2".repeat(32), "2".repeat(16), false);
        TraceContext.set(captured);
        Runnable success = TraceContext.wrap(() -> assertSame(captured, TraceContext.currentOrNull()));
        Runnable failure = TraceContext.wrap(() -> {
            assertSame(captured, TraceContext.currentOrNull());
            throw new IllegalStateException("task failed");
        });
        TraceContext.set(previous);

        success.run();
        assertSame(previous, TraceContext.currentOrNull());
        assertThrows(IllegalStateException.class, failure::run);
        assertSame(previous, TraceContext.currentOrNull());
        TraceContext.clear();
        success.run();
        assertNull(TraceContext.currentOrNull());
        assertThrows(IllegalStateException.class, failure::run);
        assertNull(TraceContext.currentOrNull());
        Runnable plain = () -> {};
        assertSame(plain, TraceContext.wrap(plain));
    }

    private static RequestGuard enabledGuard() throws Exception {
        System.setProperty("reactor.tracing.enabled", "true");
        System.setProperty("reactor.tracing.sample-ratio", "1.0");
        return new TracingRequestGuardFactory().create(Handler.class, method());
    }

    private static RequestGuardContext request(String traceparent) {
        return new RequestGuardContext("", "", traceparent == null ? "" : "traceparent: " + traceparent + '\n',
                new byte[0]);
    }

    private static Method method() throws NoSuchMethodException {
        return Handler.class.getDeclaredMethod("handle");
    }

    static final class Handler {
        @Traced("orders.fetch")
        void handle() {}
    }
}

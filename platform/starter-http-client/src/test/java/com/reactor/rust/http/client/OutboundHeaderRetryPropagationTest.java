package com.reactor.rust.http.client;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OutboundHeaderRetryPropagationTest {
    @Test
    void delayedIoRetryKeepsCallerSnapshotAfterContextWasClearedAndReplaced() throws Exception {
        Thread caller = Thread.currentThread();
        ThreadLocal<String> context = new ThreadLocal<>();
        AtomicInteger builds = new AtomicInteger();
        AtomicReference<Thread> providerThread = new AtomicReference<>();
        var requests = new CopyOnWriteArrayList<HttpRequest>();
        var firstAttempt = new CompletableFuture<java.net.http.HttpResponse<byte[]>>();
        String original = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        String other = "00-fedcba9876543210fedcba9876543210-fedcba9876543210-00";
        OutboundHeaderProvider provider = headers -> {
            builds.incrementAndGet();
            providerThread.set(Thread.currentThread());
            headers.accept("traceparent", context.get());
            headers.accept("Idempotency-Key", "order-42");
        };
        System.setProperty("phase3c.header.base-url", "http://127.0.0.1:1");
        context.set(original);
        try (var runtime = HttpClientTestSupport.runtime(request -> {
            requests.add(request);
            if (requests.size() == 1) return firstAttempt;
            return HttpClientTestSupport.response(request, 200, "ok");
        }, List.of(provider))) {
            var result = runtime.client("phase3c.header.base-url")
                    .request("GET", "/retry", null, null, 1000, 1, true).execute(String.class);
            context.remove();
            Thread completion = new Thread(() -> {
                context.set(other);
                try { firstAttempt.completeExceptionally(new IOException("disconnected")); }
                finally { context.remove(); }
            }, "retry-test-completion");
            completion.start();
            completion.join(5000);
            assertFalse(completion.isAlive());
            assertEquals("ok", result.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, builds.get());
            assertSame(caller, providerThread.get());
            assertEquals(2, requests.size());
            assertSame(requests.get(0), requests.get(1));
            for (HttpRequest request : requests) {
                assertEquals(List.of(original), request.headers().allValues("traceparent"));
                assertEquals(List.of("order-42"), request.headers().allValues("Idempotency-Key"));
            }
        } finally {
            context.remove();
            System.clearProperty("phase3c.header.base-url");
        }
    }

    @Test
    void buildsHeadersOnceOnCallerAndReusesSameRequestThroughFinalRetry() {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> providerThread = new AtomicReference<>();
        AtomicInteger builds = new AtomicInteger();
        var requests = new CopyOnWriteArrayList<HttpRequest>();
        String traceparent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        OutboundHeaderProvider provider = headers -> {
            builds.incrementAndGet();
            providerThread.set(Thread.currentThread());
            headers.accept("traceparent", traceparent);
        };
        System.setProperty("phase3a.header.base-url", "http://127.0.0.1:1");
        try (var runtime = HttpClientTestSupport.runtime(request -> {
            requests.add(request);
            return HttpClientTestSupport.response(request, requests.size() <= 2 ? 503 : 200, "ok");
        }, List.of(provider))) {
            var breaker = new CircuitBreakerState(16, 2, 50, 1000L, 1, true);
            String value = HttpCircuitBreakerInvocation.execute(runtime.client("phase3a.header.base-url")
                            .request("GET", "/retry", null, null, 1000, 2, true), String.class,
                    breaker, breaker.acquirePermission()).toCompletableFuture().join();
            assertEquals("ok", value);
            assertEquals(1, builds.get());
            assertSame(caller, providerThread.get());
            assertEquals(3, requests.size());
            for (HttpRequest request : requests) {
                assertSame(requests.getFirst(), request);
                assertEquals(traceparent, request.headers().firstValue("traceparent").orElseThrow());
            }
            // Two failed attempts must not have been recorded as logical request failures.
            assertEquals(CircuitBreakerState.Phase.CLOSED, breaker.phase());
            breaker.recordFailure(breaker.acquirePermission());
            assertEquals(CircuitBreakerState.Phase.OPEN, breaker.phase());
        } finally {
            System.clearProperty("phase3a.header.base-url");
        }
    }
}

package com.reactor.rust.http.client;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class HttpCircuitBreakerInvocationTest {
    @Test
    void failedRequestCountsOnceOnlyAfterFinalRetry() {
        var breaker = new CircuitBreakerState(4, 2, 100, 1000, 1, true);
        AtomicInteger sends = new AtomicInteger();
        try (var runtime = HttpClientTestSupport.runtime(request -> {
            sends.incrementAndGet();
            return HttpClientTestSupport.response(request, 503, "busy");
        }, List.of())) {
            assertThrows(CompletionException.class, () -> HttpCircuitBreakerInvocation.execute(
                    request(runtime, "GET", 2), String.class, breaker, breaker.acquirePermission()).toCompletableFuture().join());
            assertEquals(3, sends.get());
            assertEquals(CircuitBreakerState.Phase.CLOSED, breaker.phase());
            assertThrows(CompletionException.class, () -> HttpCircuitBreakerInvocation.execute(
                    request(runtime, "GET", 0), String.class, breaker, breaker.acquirePermission()).toCompletableFuture().join());
            assertEquals(4, sends.get());
            assertEquals(CircuitBreakerState.Phase.OPEN, breaker.phase());
        }
    }

    @Test
    void finalStatusAndRawResponseFollowPolicyWithoutAddingPostRetries() {
        for (boolean raw : new boolean[]{false, true}) for (boolean record5xx : new boolean[]{false, true}) {
            AtomicInteger sends = new AtomicInteger();
            var breaker = breaker(record5xx);
            try (var runtime = HttpClientTestSupport.runtime(request -> {
                sends.incrementAndGet();
                return HttpClientTestSupport.response(request, 503, "busy");
            }, List.of())) {
                var request = request(runtime, "POST", -1);
                var stage = raw
                        ? HttpCircuitBreakerInvocation.executeResponse(request, String.class, breaker, breaker.acquirePermission())
                        : HttpCircuitBreakerInvocation.execute(request, String.class, breaker, breaker.acquirePermission());
                if (raw) assertEquals(503, ((HttpClientResponse<?>) stage.toCompletableFuture().join()).status());
                else assertEquals(503, assertInstanceOf(HttpClientException.class, assertThrows(CompletionException.class,
                        () -> stage.toCompletableFuture().join()).getCause()).status());
                assertEquals(record5xx ? CircuitBreakerState.Phase.OPEN : CircuitBreakerState.Phase.CLOSED, breaker.phase());
                assertEquals(1, sends.get());
            }
        }
    }

    @Test
    void fourHundredResponseDoesNotBecomeTransportFailure() {
        var breaker = breaker(true);
        try (var runtime = HttpClientTestSupport.runtime(request -> HttpClientTestSupport.response(request, 404, "missing"), List.of())) {
            assertThrows(CompletionException.class, () -> HttpCircuitBreakerInvocation.execute(
                    request(runtime, "GET", 0), String.class, breaker, breaker.acquirePermission()).toCompletableFuture().join());
            assertEquals(CircuitBreakerState.Phase.CLOSED, breaker.phase());
        }
    }

    @Test
    void disablingFiveHundredsDoesNotIgnoreDecodeOrTransportFailures() {
        var invalid = breaker(false);
        try (var runtime = HttpClientTestSupport.runtime(request -> HttpClientTestSupport.response(request, 200, "invalid JSON"), List.of())) {
            assertThrows(CompletionException.class, () -> HttpCircuitBreakerInvocation.executeList(
                    request(runtime, "GET", 0), String.class, invalid, invalid.acquirePermission()).toCompletableFuture().join());
            assertEquals(CircuitBreakerState.Phase.OPEN, invalid.phase());
        }
        var transport = breaker(false);
        AtomicInteger sends = new AtomicInteger();
        try (var runtime = HttpClientTestSupport.runtime(request -> {
            sends.incrementAndGet();
            return CompletableFuture.failedFuture(new ConnectException("offline"));
        }, List.of())) {
            assertThrows(CompletionException.class, () -> HttpCircuitBreakerInvocation.execute(
                    request(runtime, "GET", 1), String.class, transport, transport.acquirePermission()).toCompletableFuture().join());
            assertEquals(2, sends.get());
            assertEquals(CircuitBreakerState.Phase.OPEN, transport.phase());
        }
    }

    @Test
    void rawListFiveHundredsStillCountAndListSuccessClosesProbe() {
        AtomicLong time = new AtomicLong();
        var breaker = new CircuitBreakerState(1,1,50,10,1,true,time::get);
        AtomicInteger calls = new AtomicInteger();
        try (var runtime = HttpClientTestSupport.runtime(request -> HttpClientTestSupport.response(
                request, calls.incrementAndGet() == 1 ? 500 : 200, "[\"ok\"]"), List.of())) {
            var response = HttpCircuitBreakerInvocation.executeListResponse(request(runtime, "GET", 0), String.class,
                    breaker, breaker.acquirePermission()).toCompletableFuture().join();
            assertEquals(List.of("ok"), response.body());
            assertEquals(CircuitBreakerState.Phase.OPEN, breaker.phase());
            time.addAndGet(10_000_000L);
            assertEquals(List.of("ok"), HttpCircuitBreakerInvocation.executeList(request(runtime, "GET", 0), String.class,
                    breaker, breaker.acquirePermission()).toCompletableFuture().join());
            assertEquals(CircuitBreakerState.Phase.CLOSED, breaker.phase());
        }
    }

    @Test
    void cancellationDrainsBulkheadAndDoesNotRecordLateSuccess() {
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        var breaker = breaker(true);
        var sent = new java.util.concurrent.atomic.AtomicReference<HttpRequest>();
        try (var runtime = HttpClientTestSupport.runtime(request -> { sent.set(request); return pending; }, List.of())) {
            var future = HttpCircuitBreakerInvocation.execute(request(runtime, "GET", 0), String.class,
                    breaker, breaker.acquirePermission()).toCompletableFuture();
            assertTrue(future.cancel(false));
            assertEquals(CircuitBreakerState.Phase.OPEN, breaker.phase());
            pending.complete(HttpClientTestSupport.response(sent.get(), 200, "late").join());
            assertEquals(CircuitBreakerState.Phase.OPEN, breaker.phase());
            // A drained logical cancellation leaves all four runtime bulkhead permits usable.
            for (int i = 0; i < 8; i++) assertEquals("late", request(runtime, "GET", 0)
                    .execute(String.class).toCompletableFuture().join());
        }
    }

    @Test
    void completionAndCancellationRaceRecordExactlyOneOutcome() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 100; i++) {
                var breaker = new CircuitBreakerState(64, 2, 50, 1000, 1, true);
                var source = new CompletableFuture<HttpClientResponse<String>>();
                var result = HttpCircuitBreakerInvocation.observe(source, breaker, breaker.acquirePermission(), HttpClientResponse::body)
                        .toCompletableFuture();
                var start = new java.util.concurrent.CountDownLatch(1);
                var cancel = pool.submit(() -> { start.await(); return result.cancel(false); });
                var complete = pool.submit(() -> { start.await(); return source.complete(new HttpClientResponse<>(200, null, "ok")); });
                start.countDown();
                boolean cancelled = cancel.get(5, java.util.concurrent.TimeUnit.SECONDS);
                complete.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(CircuitBreakerState.Phase.CLOSED, breaker.phase());
                breaker.recordSuccess(breaker.acquirePermission());
                assertEquals(cancelled ? CircuitBreakerState.Phase.OPEN : CircuitBreakerState.Phase.CLOSED, breaker.phase());
            }
        }
    }

    private static CircuitBreakerState breaker(boolean record5xx) {
        return new CircuitBreakerState(1,1,50,1000,1,record5xx);
    }

    private static ReactorHttpClientRuntime.Request request(ReactorHttpClientRuntime runtime, String method, int retries) {
        System.setProperty("phase3a.invoke.base-url", "http://127.0.0.1:1");
        try { return runtime.client("phase3a.invoke.base-url").request(method, "/test", null, null, 1000L, retries, false); }
        finally { System.clearProperty("phase3a.invoke.base-url"); }
    }
}

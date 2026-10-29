package com.reactor.rust.http.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Final-outcome hooks used only by generated, explicitly annotated HTTP methods. */
public final class HttpCircuitBreakerInvocation {
    private HttpCircuitBreakerInvocation() {}

    public static <T> CompletionStage<T> execute(ReactorHttpClientRuntime.Request request, Class<T> type,
                                                CircuitBreakerState breaker, long lease) {
        return request.circuitExecute(type, breaker, lease);
    }

    public static <T> CompletionStage<HttpClientResponse<T>> executeResponse(
            ReactorHttpClientRuntime.Request request, Class<T> type, CircuitBreakerState breaker, long lease) {
        return request.circuitExecuteResponse(type, breaker, lease);
    }

    public static <T> CompletionStage<List<T>> executeList(ReactorHttpClientRuntime.Request request, Class<T> type,
                                                         CircuitBreakerState breaker, long lease) {
        return request.circuitExecuteList(type, breaker, lease);
    }

    public static <T> CompletionStage<HttpClientResponse<List<T>>> executeListResponse(
            ReactorHttpClientRuntime.Request request, Class<T> type, CircuitBreakerState breaker, long lease) {
        return request.circuitExecuteListResponse(type, breaker, lease);
    }

    static <T, R> CompletionStage<R> observe(CompletionStage<HttpClientResponse<T>> source,
                                            CircuitBreakerState breaker, long lease,
                                            Function<HttpClientResponse<T>, R> decode) {
        OutcomeFuture<R> result = new OutcomeFuture<>(breaker, lease,
                source instanceof CancellableHttpExchange ? source.toCompletableFuture() : null);
        source.whenComplete((response, failure) -> {
            if (!result.recorded.compareAndSet(false, true)) return;
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            try {
                boolean failed = cause != null;
                if (cause instanceof HttpClientException.ResponseStatus status) {
                    failed = breaker.record5xxAsFailure() && status.status() >= 500 && status.status() <= 599;
                } else if (cause == null) {
                    failed = breaker.record5xxAsFailure() && response.status() >= 500 && response.status() <= 599;
                }
                R value = failure == null ? decode.apply(response) : null;
                if (failed) breaker.recordFailure(lease);
                else breaker.recordSuccess(lease);
                if (failure != null) result.completeExceptionally(failure);
                else result.complete(value);
            } catch (RuntimeException | Error decodingFailure) {
                // No hung future/half-open lease if a decoder fails synchronously.
                breaker.recordFailure(lease);
                result.completeExceptionally(decodingFailure);
            }
        });
        return result;
    }

    private static final class OutcomeFuture<T> extends CompletableFuture<T> {
        private final AtomicBoolean recorded = new AtomicBoolean();
        private final CircuitBreakerState breaker;
        private final long lease;
        private final CompletableFuture<?> cancellation;

        private OutcomeFuture(CircuitBreakerState breaker, long lease, CompletableFuture<?> cancellation) {
            this.breaker = breaker;
            this.lease = lease;
            this.cancellation = cancellation;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!recorded.compareAndSet(false, true)) return isCancelled();
            // JDK retains its original drain behavior. Native cancellation waits for its I/O
            // acknowledgment before reclaiming the slot, independently of this wrapper.
            breaker.recordFailure(lease);
            if (cancellation != null) cancellation.cancel(mayInterruptIfRunning);
            return super.cancel(mayInterruptIfRunning);
        }
    }
}

package com.reactor.rust.example.handler;

import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.concurrent.AdaptiveBulkhead;
import com.reactor.rust.di.annotation.Autowired;
import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.di.annotation.PreDestroy;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.dubbo.sample.NestedCatalogService;
import com.reactor.rust.http.HttpStatus;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.example.dubbo.NativeNestedCatalogServiceClient;
import com.reactor.rust.metrics.Metrics;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DubboCatalogHandler {

    private static final byte[] BULKHEAD_JSON = "{\"error\":\"Dubbo route overloaded\",\"detail\":\"bulkhead full\"}"
            .getBytes(StandardCharsets.UTF_8);

    private static final Metrics METRICS = Metrics.getInstance();

    @Autowired(required = false)
    private NestedCatalogService nestedCatalogService;

    private final AdaptiveBulkhead bulkhead;
    private final int responseTimeoutMs;
    private final ThreadPoolExecutor rpcExecutor;

    public DubboCatalogHandler() {
        int configuredJniWorkers = PropertiesLoader.getInt("reactor.rust.jni.workers", 16);
        int defaultInflight = configuredJniWorkers > 0 ? configuredJniWorkers : 16;
        int maxInflight = Math.max(1, PropertiesLoader.getInt(
                "reactor.dubbo.catalog.max-inflight",
                defaultInflight
        ));
        int nativeAsyncWorkers = Math.max(1, PropertiesLoader.getInt(
                "reactor.dubbo.native-async-workers",
                Math.max(1, maxInflight / 2)
        ));
        int initialInflight = PropertiesLoader.getInt(
                "reactor.dubbo.catalog.initial-inflight",
                Math.min(maxInflight, nativeAsyncWorkers * 2)
        );
        if (initialInflight <= 0) {
            initialInflight = Math.min(maxInflight, nativeAsyncWorkers * 2);
        }
        int minInflight = PropertiesLoader.getInt(
                "reactor.dubbo.catalog.min-inflight",
                Math.min(initialInflight, nativeAsyncWorkers)
        );
        if (minInflight <= 0) {
            minInflight = Math.min(initialInflight, nativeAsyncWorkers);
        }
        this.bulkhead = new AdaptiveBulkhead(new AdaptiveBulkhead.Config(
                "dubbo_catalog",
                PropertiesLoader.getBoolean("reactor.dubbo.catalog.adaptive-enabled", true),
                minInflight,
                initialInflight,
                maxInflight,
                Math.max(1, PropertiesLoader.getInt("reactor.dubbo.catalog.target-latency-ms", 150)),
                Math.max(1, PropertiesLoader.getInt("reactor.dubbo.catalog.high-latency-ms", 500)),
                Math.max(8, PropertiesLoader.getInt("reactor.dubbo.catalog.adaptive-sample-size", 128)),
                Math.max(1, PropertiesLoader.getInt("reactor.dubbo.catalog.adaptive-increase-step", 1)),
                Math.max(1, PropertiesLoader.getInt("reactor.dubbo.catalog.adaptive-decrease-percent", 75))
        ));
        this.responseTimeoutMs = Math.max(0, PropertiesLoader.getInt(
                "reactor.dubbo.catalog.response-timeout-ms",
                0
        ));
        int rpcWorkers = Math.max(1, PropertiesLoader.getInt("reactor.dubbo.catalog.rpc-workers", maxInflight));
        int queueCapacity = Math.max(0, PropertiesLoader.getInt("reactor.dubbo.catalog.rpc-queue-capacity", 0));
        BlockingQueue<Runnable> queue = queueCapacity == 0
                ? new SynchronousQueue<>()
                : new ArrayBlockingQueue<>(queueCapacity);
        this.rpcExecutor = new ThreadPoolExecutor(
                rpcWorkers,
                rpcWorkers,
                30L,
                TimeUnit.SECONDS,
                queue,
                new NamedDaemonThreadFactory("dubbo-catalog-rpc"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.rpcExecutor.allowCoreThreadTimeOut(true);
    }

    @RustRoute(
            method = "GET",
            path = "/api/v1/dubbo/catalog",
            requestType = Void.class,
            responseType = RawResponse.class
    )
    public CompletableFuture<ResponseEntity<RawResponse>> catalog(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        if (nestedCatalogService == null) {
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(RawResponse.json(disabledJson())));
        }
        AdaptiveBulkhead.Lease lease = bulkhead.tryAcquire();
        if (lease == null) {
            METRICS.increment("dubbo_catalog_bulkhead_rejected_total");
            return CompletableFuture.completedFuture(withBulkheadHeaders(
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(RawResponse.json(BULKHEAD_JSON)),
                    "rejected"));
        }
        if (nestedCatalogService instanceof NativeNestedCatalogServiceClient nativeClient) {
            return completeFromNativeProvider(nativeClient, lease);
        }
        CompletableFuture<ResponseEntity<RawResponse>> response = new CompletableFuture<>();
        try {
            rpcExecutor.execute(() -> completeFromProvider(response, lease));
        } catch (RejectedExecutionException e) {
            lease.release(false);
            METRICS.increment("dubbo_catalog_executor_rejected_total");
            return CompletableFuture.completedFuture(withBulkheadHeaders(
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(RawResponse.json(BULKHEAD_JSON)),
                    "executor-rejected"));
        }
        return withResponseTimeout(response, lease);
    }

    private CompletableFuture<ResponseEntity<RawResponse>> completeFromNativeProvider(
            NativeNestedCatalogServiceClient nativeClient,
            AdaptiveBulkhead.Lease lease) {
        CompletableFuture<ResponseEntity<RawResponse>> response = nativeClient.getNestedCatalogJsonAsync()
                .handle((json, error) -> {
                    if (error == null) {
                        lease.release(true);
                        METRICS.increment("dubbo_catalog_success_total");
                        METRICS.recordTiming("dubbo_catalog_latency_ms",
                                TimeUnit.NANOSECONDS.toMillis(lease.elapsedNanos()));
                        return withBulkheadHeaders(ResponseEntity.ok(RawResponse.json(json))
                                        .header("X-Dubbo-Consumer", "rust-java-rest")
                                        .header("X-Dubbo-Service", NestedCatalogService.class.getName())
                                        .header("X-Dubbo-Async", "native-dubbo-demux"),
                                "accepted");
                    }
                    lease.release(false);
                    METRICS.increment("dubbo_catalog_error_total");
                    return withBulkheadHeaders(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                    .body(RawResponse.json(errorJson(error))),
                            "provider-error");
                });
        return withResponseTimeout(response, lease);
    }

    private CompletableFuture<ResponseEntity<RawResponse>> withResponseTimeout(
            CompletableFuture<ResponseEntity<RawResponse>> response,
            AdaptiveBulkhead.Lease lease) {
        if (responseTimeoutMs <= 0) {
            return response;
        }
        return response
                .orTimeout(responseTimeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(error -> {
                    lease.release(false, true);
                    METRICS.increment("dubbo_catalog_route_timeout_total");
                    return withBulkheadHeaders(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                    .body(RawResponse.json(errorJson(new TimeoutException(
                                            "Dubbo route timeout after " + responseTimeoutMs + " ms")))),
                            "route-timeout");
                });
    }

    private ResponseEntity<RawResponse> withBulkheadHeaders(
            ResponseEntity<RawResponse> response,
            String outcome) {
        AdaptiveBulkhead.Snapshot snapshot = bulkhead.snapshot();
        METRICS.setGauge("dubbo_catalog_bulkhead_limit", snapshot.limit());
        METRICS.setGauge("dubbo_catalog_bulkhead_inflight", snapshot.inFlight());
        METRICS.setGauge("dubbo_catalog_bulkhead_rejected", snapshot.rejected());
        METRICS.setGauge("dubbo_catalog_bulkhead_timed_out", snapshot.timedOut());
        return response
                .header("X-Dubbo-Bulkhead", outcome)
                .header("X-Dubbo-Bulkhead-Limit", Integer.toString(snapshot.limit()))
                .header("X-Dubbo-Bulkhead-InFlight", Integer.toString(snapshot.inFlight()));
    }

    private static byte[] errorJson(Throwable e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return ("{\"error\":\"Dubbo provider unavailable\",\"detail\":\"" + escaped + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] disabledJson() {
        return "{\"error\":\"Dubbo consumer disabled\",\"detail\":\"reactor.dubbo.enabled=false\"}"
                .getBytes(StandardCharsets.UTF_8);
    }

    private void completeFromProvider(
            CompletableFuture<ResponseEntity<RawResponse>> response,
            AdaptiveBulkhead.Lease lease) {
        try {
            byte[] json = nestedCatalogService.getNestedCatalogJson();
            lease.release(true);
            METRICS.increment("dubbo_catalog_success_total");
            METRICS.recordTiming("dubbo_catalog_latency_ms",
                    TimeUnit.NANOSECONDS.toMillis(lease.elapsedNanos()));
            response.complete(withBulkheadHeaders(ResponseEntity.ok(RawResponse.json(json))
                            .header("X-Dubbo-Consumer", "rust-java-rest")
                            .header("X-Dubbo-Service", NestedCatalogService.class.getName())
                            .header("X-Dubbo-Async", "native-demux"),
                    "accepted"));
        } catch (RuntimeException e) {
            lease.release(false);
            METRICS.increment("dubbo_catalog_error_total");
            response.complete(withBulkheadHeaders(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(RawResponse.json(errorJson(e))),
                    "provider-error"));
        } finally {
            lease.release(false);
        }
    }

    @PreDestroy
    public void shutdown() {
        rpcExecutor.shutdownNow();
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger(1);

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, prefix + "-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}

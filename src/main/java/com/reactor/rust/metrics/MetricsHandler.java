package com.reactor.rust.metrics;

import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.annotations.GetMapping;

/**
 * Built-in handler for exposing metrics in Prometheus format.
 * Endpoint: GET /metrics
 */
@Component
public class MetricsHandler {

    /**
     * Get metrics in Prometheus format.
     */
    @GetMapping(value = "/metrics", requestType = Void.class, responseType = String.class)
    public String getMetrics() {
        return Metrics.getInstance().toPrometheusFormat();
    }

    /**
     * Get metrics summary (JSON format).
     */
    @GetMapping(value = "/metrics/summary", requestType = Void.class, responseType = MetricsSummary.class)
    public MetricsSummary getMetricsSummary() {
        Metrics metrics = Metrics.getInstance();
        Runtime runtime = Runtime.getRuntime();

        return new MetricsSummary(
            System.currentTimeMillis() - getStartTime(),
            runtime.totalMemory() - runtime.freeMemory(),
            runtime.maxMemory(),
            metrics.getCounter("http_requests_total"),
            metrics.getGauge("http_connections_active")
        );
    }

    private long getStartTime() {
        // Access the private startTime field via reflection or use a different approach
        return System.currentTimeMillis() - (Metrics.getInstance().getGauge("jvm_uptime_ms"));
    }

    /**
     * Reset all metrics (use with caution!).
     */
    @GetMapping(value = "/metrics/reset", requestType = Void.class, responseType = String.class)
    public String resetMetrics() {
        Metrics.getInstance().reset();
        return "{\"status\":\"reset\"}";
    }

    /**
     * Metrics summary record.
     */
    public record MetricsSummary(
        long uptimeMs,
        long usedMemoryBytes,
        long maxMemoryBytes,
        long totalRequests,
        long activeConnections
    ) {}
}

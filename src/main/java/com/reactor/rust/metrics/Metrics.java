package com.reactor.rust.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Simple metrics registry with Prometheus-compatible output.
 * Thread-safe, low-overhead metrics collection.
 */
public final class Metrics {

    private static final Metrics INSTANCE = new Metrics();

    // Counters
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

    // Gauges
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    // Histograms (simple bucket-based)
    private final Map<String, Histogram> histograms = new ConcurrentHashMap<>();

    // Start time for uptime calculation
    private final long startTime = System.currentTimeMillis();

    private Metrics() {}

    public static Metrics getInstance() {
        return INSTANCE;
    }

    // ==================== COUNTERS ====================

    /**
     * Increment a counter by 1.
     */
    public void increment(String name) {
        increment(name, 1);
    }

    /**
     * Increment a counter by value.
     */
    public void increment(String name, long value) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(value);
    }

    /**
     * Get counter value.
     */
    public long getCounter(String name) {
        LongAdder adder = counters.get(name);
        return adder != null ? adder.sum() : 0;
    }

    // ==================== GAUGES ====================

    /**
     * Set a gauge value.
     */
    public void setGauge(String name, long value) {
        gauges.computeIfAbsent(name, k -> new AtomicLong()).set(value);
    }

    /**
     * Get gauge value.
     */
    public long getGauge(String name) {
        AtomicLong gauge = gauges.get(name);
        return gauge != null ? gauge.get() : 0;
    }

    // ==================== HISTOGRAMS ====================

    /**
     * Record a value in a histogram.
     * Uses default buckets: 1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000
     */
    public void record(String name, double value) {
        histograms.computeIfAbsent(name, k -> new Histogram(k, DEFAULT_BUCKETS)).record(value);
    }

    /**
     * Record a timing value in milliseconds.
     */
    public void recordTiming(String name, long millis) {
        record(name, millis);
    }

    // Default histogram buckets (milliseconds for timing)
    private static final double[] DEFAULT_BUCKETS = {
        1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000
    };

    // ==================== HTTP METRICS ====================

    /**
     * Record an HTTP request.
     */
    public void recordRequest(String method, String path, int status, long durationMs) {
        // Total requests
        increment("http_requests_total");

        // Requests by method
        increment("http_requests_" + method.toLowerCase() + "_total");

        // Requests by status
        increment("http_responses_" + status / 100 + "xx_total");

        // Request duration
        recordTiming("http_request_duration_ms", durationMs);

        // Active connections (increment at start, decrement at end - handled separately)
    }

    /**
     * Increment active connections.
     */
    public void connectionStarted() {
        increment("http_connections_total");
        setGauge("http_connections_active", getGauge("http_connections_active") + 1);
    }

    /**
     * Decrement active connections.
     */
    public void connectionEnded() {
        setGauge("http_connections_active", Math.max(0, getGauge("http_connections_active") - 1));
    }

    // ==================== PROMETHEUS OUTPUT ====================

    /**
     * Generate Prometheus-compatible metrics output.
     */
    public String toPrometheusFormat() {
        StringBuilder sb = new StringBuilder();

        // JVM uptime
        long uptimeMs = System.currentTimeMillis() - startTime;
        sb.append("# HELP jvm_uptime_ms JVM uptime in milliseconds\n");
        sb.append("# TYPE jvm_uptime_ms gauge\n");
        sb.append("jvm_uptime_ms ").append(uptimeMs).append("\n\n");

        // Memory info
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();

        sb.append("# HELP jvm_memory_used_bytes Used memory in bytes\n");
        sb.append("# TYPE jvm_memory_used_bytes gauge\n");
        sb.append("jvm_memory_used_bytes ").append(usedMemory).append("\n\n");

        sb.append("# HELP jvm_memory_max_bytes Max memory in bytes\n");
        sb.append("# TYPE jvm_memory_max_bytes gauge\n");
        sb.append("jvm_memory_max_bytes ").append(runtime.maxMemory()).append("\n\n");

        // Counters
        sb.append("# HELP http_requests_total Total HTTP requests\n");
        sb.append("# TYPE http_requests_total counter\n");
        for (Map.Entry<String, LongAdder> entry : counters.entrySet()) {
            sb.append(entry.getKey().replace(".", "_")).append(" ").append(entry.getValue().sum()).append("\n");
        }
        sb.append("\n");

        // Gauges
        if (!gauges.isEmpty()) {
            sb.append("# TYPE gauge gauge\n");
            for (Map.Entry<String, AtomicLong> entry : gauges.entrySet()) {
                sb.append(entry.getKey().replace(".", "_")).append(" ").append(entry.getValue().get()).append("\n");
            }
            sb.append("\n");
        }

        // Histograms
        for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
            sb.append(entry.getValue().toPrometheusFormat());
        }

        return sb.toString();
    }

    /**
     * Reset all metrics.
     */
    public void reset() {
        counters.clear();
        gauges.clear();
        histograms.clear();
    }

    // ==================== HISTOGRAM CLASS ====================

    private static class Histogram {
        private final String name;
        private final double[] buckets;
        private final LongAdder[] bucketCounts;
        private final DoubleAdder sum = new DoubleAdder();
        private final LongAdder count = new LongAdder();

        Histogram(String name, double[] buckets) {
            this.name = name;
            this.buckets = buckets.clone();
            this.bucketCounts = new LongAdder[buckets.length + 1]; // +1 for +Inf bucket
            for (int i = 0; i < bucketCounts.length; i++) {
                bucketCounts[i] = new LongAdder();
            }
        }

        void record(double value) {
            sum.add(value);
            count.increment();

            // Find bucket
            int bucketIndex = buckets.length; // Default to +Inf
            for (int i = 0; i < buckets.length; i++) {
                if (value <= buckets[i]) {
                    bucketIndex = i;
                    break;
                }
            }
            bucketCounts[bucketIndex].increment();
        }

        String toPrometheusFormat() {
            StringBuilder sb = new StringBuilder();

            sb.append("# HELP ").append(name).append(" Histogram\n");
            sb.append("# TYPE ").append(name).append(" histogram\n");

            long cumulative = 0;
            for (int i = 0; i < buckets.length; i++) {
                cumulative += bucketCounts[i].sum();
                sb.append(name).append("_bucket{le=\"").append(buckets[i]).append("\"} ")
                  .append(cumulative).append("\n");
            }

            // +Inf bucket
            cumulative += bucketCounts[buckets.length].sum();
            sb.append(name).append("_bucket{le=\"+Inf\"} ").append(cumulative).append("\n");

            sb.append(name).append("_sum ").append(sum.sum()).append("\n");
            sb.append(name).append("_count ").append(count.sum()).append("\n\n");

            return sb.toString();
        }
    }
}

package com.reactor.rust.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Metrics.
 */
class MetricsTest {

    private Metrics metrics;

    @BeforeEach
    void setUp() {
        metrics = Metrics.getInstance();
        metrics.reset();
    }

    @Test
    @DisplayName("Increment counter by 1")
    void testIncrementCounter() {
        metrics.increment("test_counter");

        assertEquals(1, metrics.getCounter("test_counter"));

        metrics.increment("test_counter");
        assertEquals(2, metrics.getCounter("test_counter"));
    }

    @Test
    @DisplayName("Increment counter by value")
    void testIncrementCounterByValue() {
        metrics.increment("test_counter", 5);

        assertEquals(5, metrics.getCounter("test_counter"));

        metrics.increment("test_counter", 3);
        assertEquals(8, metrics.getCounter("test_counter"));
    }

    @Test
    @DisplayName("Get non-existent counter returns 0")
    void testGetNonExistentCounter() {
        assertEquals(0, metrics.getCounter("non_existent"));
    }

    @Test
    @DisplayName("Set and get gauge")
    void testSetGauge() {
        metrics.setGauge("test_gauge", 42);

        assertEquals(42, metrics.getGauge("test_gauge"));

        metrics.setGauge("test_gauge", 100);
        assertEquals(100, metrics.getGauge("test_gauge"));
    }

    @Test
    @DisplayName("Get non-existent gauge returns 0")
    void testGetNonExistentGauge() {
        assertEquals(0, metrics.getGauge("non_existent"));
    }

    @Test
    @DisplayName("Record value in histogram")
    void testRecordHistogram() {
        metrics.record("test_histogram", 5);

        String output = metrics.toPrometheusFormat();
        assertTrue(output.contains("test_histogram"));
        assertTrue(output.contains("test_histogram_sum"));
        assertTrue(output.contains("test_histogram_count"));
    }

    @Test
    @DisplayName("Record timing")
    void testRecordTiming() {
        metrics.recordTiming("request_duration", 50);

        String output = metrics.toPrometheusFormat();
        assertTrue(output.contains("request_duration"));
    }

    @Test
    @DisplayName("Record HTTP request")
    void testRecordRequest() {
        metrics.recordRequest("GET", "/api/users", 200, 25);

        assertEquals(1, metrics.getCounter("http_requests_total"));
        assertEquals(1, metrics.getCounter("http_requests_get_total"));
        assertEquals(1, metrics.getCounter("http_responses_2xx_total"));
    }

    @Test
    @DisplayName("Record multiple HTTP requests")
    void testRecordMultipleRequests() {
        metrics.recordRequest("GET", "/api/users", 200, 10);
        metrics.recordRequest("POST", "/api/users", 201, 50);
        metrics.recordRequest("GET", "/api/users/1", 404, 5);
        metrics.recordRequest("DELETE", "/api/users/1", 500, 100);

        assertEquals(4, metrics.getCounter("http_requests_total"));
        assertEquals(2, metrics.getCounter("http_requests_get_total"));
        assertEquals(1, metrics.getCounter("http_requests_post_total"));
        assertEquals(1, metrics.getCounter("http_requests_delete_total"));
        assertEquals(2, metrics.getCounter("http_responses_2xx_total"));
        assertEquals(1, metrics.getCounter("http_responses_4xx_total"));
        assertEquals(1, metrics.getCounter("http_responses_5xx_total"));
    }

    @Test
    @DisplayName("Connection tracking")
    void testConnectionTracking() {
        assertEquals(0, metrics.getGauge("http_connections_active"));

        metrics.connectionStarted();
        assertEquals(1, metrics.getGauge("http_connections_active"));
        assertEquals(1, metrics.getCounter("http_connections_total"));

        metrics.connectionStarted();
        assertEquals(2, metrics.getGauge("http_connections_active"));

        metrics.connectionEnded();
        assertEquals(1, metrics.getGauge("http_connections_active"));

        metrics.connectionEnded();
        assertEquals(0, metrics.getGauge("http_connections_active"));
    }

    @Test
    @DisplayName("Connection ended doesn't go negative")
    void testConnectionEndedNotNegative() {
        metrics.connectionEnded();
        assertEquals(0, metrics.getGauge("http_connections_active"));
    }

    @Test
    @DisplayName("Reset clears all metrics")
    void testReset() {
        metrics.increment("counter1", 10);
        metrics.setGauge("gauge1", 100);
        metrics.record("histogram1", 50);

        metrics.reset();

        assertEquals(0, metrics.getCounter("counter1"));
        assertEquals(0, metrics.getGauge("gauge1"));
    }

    @Test
    @DisplayName("toPrometheusFormat includes JVM metrics")
    void testPrometheusFormatJvmMetrics() {
        String output = metrics.toPrometheusFormat();

        assertTrue(output.contains("jvm_uptime_ms"));
        assertTrue(output.contains("jvm_memory_used_bytes"));
        assertTrue(output.contains("jvm_memory_max_bytes"));
    }

    @Test
    @DisplayName("toPrometheusFormat includes counter metrics")
    void testPrometheusFormatCounters() {
        metrics.increment("http_requests_total", 100);

        String output = metrics.toPrometheusFormat();

        assertTrue(output.contains("http_requests_total"));
        assertTrue(output.contains("100"));
    }

    @Test
    @DisplayName("toPrometheusFormat includes gauge metrics")
    void testPrometheusFormatGauges() {
        metrics.setGauge("active_connections", 5);

        String output = metrics.toPrometheusFormat();

        assertTrue(output.contains("active_connections"));
    }

    @Test
    @DisplayName("toPrometheusFormat includes histogram metrics")
    void testPrometheusFormatHistograms() {
        metrics.record("latency", 10);
        metrics.record("latency", 50);
        metrics.record("latency", 100);

        String output = metrics.toPrometheusFormat();

        assertTrue(output.contains("latency_bucket"));
        assertTrue(output.contains("latency_sum"));
        assertTrue(output.contains("latency_count"));
    }

    @Test
    @DisplayName("Singleton instance")
    void testSingletonInstance() {
        Metrics instance1 = Metrics.getInstance();
        Metrics instance2 = Metrics.getInstance();

        assertSame(instance1, instance2);
    }

    @Test
    @DisplayName("Thread-safe counter increment")
    void testThreadSafeCounter() throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                metrics.increment("thread_counter");
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                metrics.increment("thread_counter");
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertEquals(2000, metrics.getCounter("thread_counter"));
    }

    @Test
    @DisplayName("Histogram bucket distribution")
    void testHistogramBuckets() {
        // Values: 1 goes in bucket 1, 5 in bucket 5, etc.
        metrics.record("timing", 0.5);  // bucket le="1"
        metrics.record("timing", 3);    // bucket le="5"
        metrics.record("timing", 50);   // bucket le="50"
        metrics.record("timing", 5000); // bucket le="5000"

        String output = metrics.toPrometheusFormat();

        // Check sum and count
        assertTrue(output.contains("timing_sum"));
        assertTrue(output.contains("timing_count 4"));
    }
}

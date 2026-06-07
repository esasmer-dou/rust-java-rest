package com.reactor.rust.memory;

import com.reactor.rust.metrics.Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NativeIdleMemoryTrimmerTest {

    private final Metrics metrics = Metrics.getInstance();

    @BeforeEach
    void resetBefore() {
        metrics.reset();
    }

    @AfterEach
    void resetAfter() {
        metrics.reset();
    }

    @Test
    void trimsOnlyAfterIdleWindow() {
        AtomicInteger releases = new AtomicInteger();
        NativeIdleMemoryTrimmer trimmer = new NativeIdleMemoryTrimmer(
                new NativeIdleMemoryTrimmer.Config(0, 1_000, 1_000, 0, 0, 2, 0, 0, 0, true),
                releases::incrementAndGet,
                this::activitySnapshot,
                metrics,
                0
        );

        trimmer.tick(999_000_000L);
        assertEquals(0, releases.get());
        assertEquals(1, metrics.getCounter(NativeIdleMemoryTrimmer.METRIC_SKIPPED_NOT_IDLE));

        trimmer.tick(1_000_000_000L);
        assertEquals(1, releases.get());
        assertEquals(1, metrics.getCounter(NativeIdleMemoryTrimmer.METRIC_SUCCESS));
    }

    @Test
    void doesNotTrimWhenActiveConnectionLimitIsExceeded() {
        AtomicInteger releases = new AtomicInteger();
        metrics.setGauge("http_connections_active", 1);
        NativeIdleMemoryTrimmer trimmer = new NativeIdleMemoryTrimmer(
                new NativeIdleMemoryTrimmer.Config(0, 1_000, 1_000, 0, 0, 2, 0, 0, 0, true),
                releases::incrementAndGet,
                this::activitySnapshot,
                metrics,
                0
        );

        trimmer.tick(2_000_000_000L);

        assertEquals(0, releases.get());
        assertEquals(1, metrics.getCounter(NativeIdleMemoryTrimmer.METRIC_SKIPPED_ACTIVE));
    }

    @Test
    void requestActivityResetsIdleWindow() {
        AtomicInteger releases = new AtomicInteger();
        NativeIdleMemoryTrimmer trimmer = new NativeIdleMemoryTrimmer(
                new NativeIdleMemoryTrimmer.Config(0, 1_000, 1_000, 0, 0, 2, 0, 0, 0, true),
                releases::incrementAndGet,
                this::activitySnapshot,
                metrics,
                0
        );

        metrics.increment("http_requests_total");
        trimmer.tick(1_000_000_000L);
        trimmer.tick(1_999_000_000L);
        assertEquals(0, releases.get());

        trimmer.tick(2_000_000_000L);
        assertEquals(1, releases.get());
    }

    @Test
    void requestActivitySchedulesNextCheckAtIdleBoundary() {
        AtomicInteger releases = new AtomicInteger();
        NativeIdleMemoryTrimmer trimmer = new NativeIdleMemoryTrimmer(
                new NativeIdleMemoryTrimmer.Config(0, 60_000, 10_000, 0, 0, 2, 0, 0, 0, true),
                releases::incrementAndGet,
                this::activitySnapshot,
                metrics,
                0
        );

        metrics.increment("http_requests_total");
        long nextDelayMs = trimmer.tick(30_000_000_000L);

        assertEquals(0, releases.get());
        assertEquals(10_000, nextDelayMs);
    }

    @Test
    void earlyIdleCheckSchedulesOnlyRemainingIdleWindow() {
        AtomicInteger releases = new AtomicInteger();
        NativeIdleMemoryTrimmer trimmer = new NativeIdleMemoryTrimmer(
                new NativeIdleMemoryTrimmer.Config(0, 60_000, 10_000, 0, 0, 2, 0, 0, 0, true),
                releases::incrementAndGet,
                this::activitySnapshot,
                metrics,
                0
        );

        long nextDelayMs = trimmer.tick(7_000_000_000L);

        assertEquals(0, releases.get());
        assertEquals(3_000, nextDelayMs);
    }

    @Test
    void managementScrapesDoNotResetIdleWindowWhenUsingUserRequestCounter() {
        AtomicInteger releases = new AtomicInteger();
        NativeIdleMemoryTrimmer trimmer = new NativeIdleMemoryTrimmer(
                new NativeIdleMemoryTrimmer.Config(0, 1_000, 1_000, 0, 0, 2, 0, 0, 0, true),
                releases::incrementAndGet,
                this::userActivitySnapshot,
                metrics,
                0
        );

        metrics.recordRequest("GET", "/metrics", 200, 1);
        metrics.recordRequest("GET", "/diagnostics/memory", 200, 1);
        trimmer.tick(1_000_000_000L);

        assertEquals(1, releases.get());
        assertEquals(2, metrics.getCounter("http_requests_total"));
        assertEquals(0, metrics.getCounter("http_user_requests_total"));
    }

    @Test
    void trimsOnlyOnceForSameIdleRequestCount() {
        AtomicInteger releases = new AtomicInteger();
        NativeIdleMemoryTrimmer trimmer = new NativeIdleMemoryTrimmer(
                new NativeIdleMemoryTrimmer.Config(0, 1_000, 1_000, 0, 0, 2, 0, 0, 0, true),
                releases::incrementAndGet,
                this::activitySnapshot,
                metrics,
                0
        );

        trimmer.tick(1_000_000_000L);
        trimmer.tick(2_000_000_000L);

        assertEquals(1, releases.get());
        assertEquals(1, metrics.getCounter(NativeIdleMemoryTrimmer.METRIC_SKIPPED_UNCHANGED));
    }

    @Test
    void parsesNativePrometheusCounters() {
        String metricsText = """
                # TYPE reactor_native_http_requests_total counter
                reactor_native_http_requests_total 12345
                reactor_native_http_requests_active 7
                reactor_native_http_connections_active 3
                """;

        assertEquals(12345, NativeIdleMemoryTrimmer.parsePrometheusLong(
                metricsText,
                "reactor_native_http_requests_total",
                -1
        ));
        assertEquals(7, NativeIdleMemoryTrimmer.parsePrometheusLong(
                metricsText,
                "reactor_native_http_requests_active",
                -1
        ));
        assertEquals(3, NativeIdleMemoryTrimmer.parsePrometheusLong(
                metricsText,
                "reactor_native_http_connections_active",
                -1
        ));
    }

    @Test
    void readsRetainedPoolTrimConfigFromProperties() {
        System.setProperty(NativeIdleMemoryTrimmer.RETAIN_SMALL_KEY, "3");
        System.setProperty(NativeIdleMemoryTrimmer.RETAIN_MEDIUM_KEY, "2");
        System.setProperty(NativeIdleMemoryTrimmer.RETAIN_LARGE_KEY, "-10");
        System.setProperty(NativeIdleMemoryTrimmer.RETAIN_HUGE_KEY, "1");
        System.setProperty(NativeIdleMemoryTrimmer.ALLOCATOR_TRIM_ENABLED_KEY, "false");
        try {
            NativeIdleMemoryTrimmer.Config config = NativeIdleMemoryTrimmer.Config.fromProperties();

            assertEquals(3, config.retainSmall());
            assertEquals(2, config.retainMedium());
            assertEquals(0, config.retainLarge());
            assertEquals(1, config.retainHuge());
            assertEquals(false, config.allocatorTrimEnabled());
        } finally {
            System.clearProperty(NativeIdleMemoryTrimmer.RETAIN_SMALL_KEY);
            System.clearProperty(NativeIdleMemoryTrimmer.RETAIN_MEDIUM_KEY);
            System.clearProperty(NativeIdleMemoryTrimmer.RETAIN_LARGE_KEY);
            System.clearProperty(NativeIdleMemoryTrimmer.RETAIN_HUGE_KEY);
            System.clearProperty(NativeIdleMemoryTrimmer.ALLOCATOR_TRIM_ENABLED_KEY);
        }
    }

    @Test
    void safetyWarningsRequireP99GateForAggressiveAllocatorTrim() {
        NativeIdleMemoryTrimmer.Config config = new NativeIdleMemoryTrimmer.Config(
                1_000,
                1_000,
                1_000,
                1,
                1,
                2,
                0,
                0,
                0,
                true
        );

        assertFalse(NativeIdleMemoryTrimmer.safetyWarnings(config).isEmpty());
    }

    private NativeIdleMemoryTrimmer.ActivitySnapshot activitySnapshot() {
        return new NativeIdleMemoryTrimmer.ActivitySnapshot(
                metrics.getGauge("http_connections_active"),
                0,
                metrics.getCounter("http_requests_total")
        );
    }

    private NativeIdleMemoryTrimmer.ActivitySnapshot userActivitySnapshot() {
        return new NativeIdleMemoryTrimmer.ActivitySnapshot(
                metrics.getGauge("http_connections_active"),
                0,
                metrics.getCounter("http_user_requests_total")
        );
    }
}

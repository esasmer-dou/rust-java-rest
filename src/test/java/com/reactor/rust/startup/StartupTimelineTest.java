package com.reactor.rust.startup;

import com.reactor.rust.metrics.Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupTimelineTest {

    @BeforeEach
    void setUp() {
        StartupTimeline.resetForTests();
        Metrics.getInstance().reset();
    }

    @AfterEach
    void tearDown() {
        StartupTimeline.resetForTests();
        Metrics.getInstance().reset();
    }

    @Test
    void recordsPhasesAndPublishesReadyMetric() {
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("DI Scan")) {
            // phase scope is intentionally tiny; this validates bookkeeping, not timing accuracy.
        }
        StartupTimeline.ready();

        assertEquals(1, StartupTimeline.phases().size());
        assertTrue(StartupTimeline.readyMillis() >= 0);
        assertTrue(Metrics.getInstance().getGauge("reactor.startup.ready.ms") >= 0);
        assertTrue(Metrics.getInstance().getGauge("reactor.startup.phase.di_scan.ms") >= 0);
    }

    @Test
    void rendersJsonWithEscapedPhaseNames() {
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("json \"phase\"")) {
            // no-op
        }

        String json = StartupTimeline.toJson();

        assertTrue(json.contains("\"ready_ms\":-1"));
        assertTrue(json.contains("\"restored\":false"));
        assertTrue(json.contains("\"ready_since_restore_ms\":-1"));
        assertTrue(json.contains("\"name\":\"json \\\"phase\\\"\""));
        assertTrue(json.contains("\"duration_ms\":"));
    }

    @Test
    void reportsReadySinceRestoreWhenRestoreBaselineIsMarked() {
        StartupTimeline.restoreResumed();
        StartupTimeline.ready();

        assertTrue(StartupTimeline.restored());
        assertTrue(StartupTimeline.readySinceRestoreMillis() >= 0);
        assertTrue(StartupTimeline.toJson().contains("\"restored\":true"));
        assertTrue(Metrics.getInstance().getGauge("reactor.startup.ready_since_restore.ms") >= 0);
    }
}

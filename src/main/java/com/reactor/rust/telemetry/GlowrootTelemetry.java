package com.reactor.rust.telemetry;

import com.reactor.rust.bridge.NativeBridge;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Explicit control plane for the bounded Rust Glowroot telemetry runtime. */
public final class GlowrootTelemetry {

    private GlowrootTelemetry() {}

    public static boolean enabled() {
        return NativeBridge.glowrootConfigured();
    }

    public static TelemetryProfile activeProfile() {
        requireEnabled();
        return TelemetryProfile.fromPropertyValue(NativeBridge.activeGlowrootProfile());
    }

    public static TelemetryProfile configuredProfile() {
        requireEnabled();
        return TelemetryProfile.fromPropertyValue(NativeBridge.configuredGlowrootProfile());
    }

    /** Uses the configured release timeout and returns only after retired native state is dropped. */
    public static void switchTo(TelemetryProfile profile) {
        NativeBridge.setGlowrootProfile(required(profile).propertyValue());
    }

    /** Uses an explicit control-plane timeout; this method is never part of the request hot path. */
    public static void switchTo(TelemetryProfile profile, Duration releaseTimeout) {
        Objects.requireNonNull(releaseTimeout, "releaseTimeout");
        long millis = releaseTimeout.toMillis();
        if (millis < 100 || millis > 60_000) {
            throw new IllegalArgumentException("releaseTimeout must be between 100 ms and 60 s");
        }
        NativeBridge.setGlowrootProfile(required(profile).propertyValue(), Math.toIntExact(millis));
    }

    /** Returns to the profile selected by {@code reactor.glowroot.profile}. */
    public static void restoreConfiguredProfile() {
        NativeBridge.restoreConfiguredGlowrootProfile();
    }

    /** Returns to the configured startup profile with an explicit control-plane timeout. */
    public static void restoreConfiguredProfile(Duration releaseTimeout) {
        Objects.requireNonNull(releaseTimeout, "releaseTimeout");
        long millis = releaseTimeout.toMillis();
        if (millis < 100 || millis > 60_000) {
            throw new IllegalArgumentException("releaseTimeout must be between 100 ms and 60 s");
        }
        NativeBridge.restoreConfiguredGlowrootProfile(Math.toIntExact(millis));
    }

    public static String diagnosticsJson() {
        return NativeBridge.glowrootDiagnosticsJson();
    }

    /** Creates a reusable, allocation-free timing descriptor for one SQL statement. */
    public static SqlStatement sql(String operation, String statement) {
        return new SqlStatement(
                Objects.requireNonNull(operation, "operation"),
                Objects.requireNonNull(statement, "statement")
        );
    }

    public static long requestDiagnostic(DiagnosticOperation operation, Path outputPath) {
        Objects.requireNonNull(outputPath, "outputPath");
        return NativeBridge.submitGlowrootDiagnostic(required(operation).propertyValue, outputPath.toString());
    }

    public enum DiagnosticOperation {
        THREAD_DUMP("thread-dump"),
        HEAP_DUMP("heap-dump"),
        HEAP_HISTOGRAM("heap-histogram");

        private final String propertyValue;

        DiagnosticOperation(String propertyValue) {
            this.propertyValue = propertyValue;
        }
    }

    public static final class SqlStatement {
        private final String operation;
        private final String statement;
        private volatile long generation = Long.MIN_VALUE;
        private volatile int slot = -1;

        private SqlStatement(String operation, String statement) {
            this.operation = operation;
            this.statement = statement;
        }

        public long start() {
            return System.nanoTime();
        }

        public void recordSuccess(long startedAtNanos, long rows) {
            int activeSlot = activeSlot();
            NativeBridge.recordGlowrootSql(
                    activeSlot,
                    elapsedSince(startedAtNanos),
                    false,
                    Math.max(0L, rows)
            );
        }

        public void recordFailure(long startedAtNanos, Throwable error) {
            Objects.requireNonNull(error, "error");
            int activeSlot = activeSlot();
            long durationNanos = elapsedSince(startedAtNanos);
            NativeBridge.recordGlowrootSql(activeSlot, durationNanos, true, 0L);
            if (activeSlot >= 0) {
                NativeBridge.recordGlowrootErrorAtSlot(activeSlot, durationNanos, error);
            }
        }

        private int activeSlot() {
            if (!NativeBridge.glowrootSqlEnabled()) return -1;
            long currentGeneration = NativeBridge.glowrootProfileGeneration();
            if (generation == currentGeneration) return slot;
            synchronized (this) {
                if (generation != currentGeneration) {
                    slot = NativeBridge.registerGlowrootSql(operation, statement);
                    generation = currentGeneration;
                }
                return slot;
            }
        }

        private static long elapsedSince(long startedAtNanos) {
            return Math.max(0L, System.nanoTime() - startedAtNanos);
        }
    }

    private static TelemetryProfile required(TelemetryProfile profile) {
        return Objects.requireNonNull(profile, "profile");
    }

    private static void requireEnabled() {
        if (!enabled()) throw new IllegalStateException("Glowroot telemetry is not configured");
    }

    private static DiagnosticOperation required(DiagnosticOperation operation) {
        return Objects.requireNonNull(operation, "operation");
    }
}

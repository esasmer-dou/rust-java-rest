package com.reactor.rust.startup;

import com.reactor.rust.metrics.Metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-overhead startup phase timeline.
 *
 * <p>Startup is measured with {@link System#nanoTime()} and published as gauges so the cost of
 * properties, DI, route registration, native loading and server start can be compared across JVM
 * profiles without adding request hot-path overhead.</p>
 */
public final class StartupTimeline {

    private static final long BOOT_NANOS = System.nanoTime();
    private static final ArrayList<Phase> PHASES = new ArrayList<>(16);
    private static final AtomicLong READY_NANOS = new AtomicLong();
    private static final AtomicLong RESTORE_RESUME_NANOS = new AtomicLong();

    private StartupTimeline() {
    }

    public static Scope phase(String name) {
        return new Scope(name, System.nanoTime());
    }

    public static void mark(String name) {
        long now = System.nanoTime();
        record(name, now, now);
    }

    public static void ready() {
        long now = System.nanoTime();
        READY_NANOS.compareAndSet(0L, now);
        synchronized (PHASES) {
            PHASES.trimToSize();
        }
        publishMetrics();
    }

    public static void restoreResumed() {
        long now = System.nanoTime();
        RESTORE_RESUME_NANOS.compareAndSet(0L, now);
        mark("instanton.restore.resume");
    }

    public static long readyMillis() {
        long ready = READY_NANOS.get();
        if (ready == 0L) {
            return -1L;
        }
        return nanosToMillis(ready - BOOT_NANOS);
    }

    public static long readySinceRestoreMillis() {
        long restored = RESTORE_RESUME_NANOS.get();
        long ready = READY_NANOS.get();
        if (restored == 0L || ready == 0L) {
            return -1L;
        }
        return nanosToMillis(ready - restored);
    }

    public static boolean restored() {
        return RESTORE_RESUME_NANOS.get() != 0L;
    }

    public static List<Phase> phases() {
        return Collections.unmodifiableList(snapshot());
    }

    static void resetForTests() {
        synchronized (PHASES) {
            PHASES.clear();
        }
        READY_NANOS.set(0L);
        RESTORE_RESUME_NANOS.set(0L);
    }

    public static void publishMetrics() {
        Metrics metrics = Metrics.getInstance();
        if (!metrics.collectionEnabled()) {
            return;
        }
        long readyMillis = readyMillis();
        if (readyMillis >= 0L) {
            metrics.setGauge("reactor.startup.ready.ms", readyMillis);
        }
        long readySinceRestoreMillis = readySinceRestoreMillis();
        if (readySinceRestoreMillis >= 0L) {
            metrics.setGauge("reactor.startup.ready_since_restore.ms", readySinceRestoreMillis);
        }
        metrics.setGauge("reactor.startup.instanton.restored", restored() ? 1 : 0);
        for (Phase phase : snapshot()) {
            metrics.setGauge("reactor.startup.phase." + metricName(phase.name()) + ".ms", phase.durationMillis());
        }
    }

    public static String toJson() {
        publishMetrics();
        List<Phase> phases = snapshot();
        StringBuilder json = new StringBuilder(512 + phases.size() * 160);
        json.append('{');
        json.append("\"ready_ms\":").append(readyMillis()).append(',');
        json.append("\"restored\":").append(restored()).append(',');
        json.append("\"ready_since_restore_ms\":").append(readySinceRestoreMillis()).append(',');
        json.append("\"phases\":[");
        for (int i = 0; i < phases.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            Phase phase = phases.get(i);
            json.append('{')
                    .append("\"name\":").append(jsonString(phase.name())).append(',')
                    .append("\"start_ms\":").append(phase.startMillis()).append(',')
                    .append("\"duration_ms\":").append(phase.durationMillis())
                    .append('}');
        }
        json.append("]}");
        return json.toString();
    }

    private static void record(String name, long startNanos, long endNanos) {
        synchronized (PHASES) {
            PHASES.add(new Phase(
                    name == null || name.isBlank() ? "unnamed" : name,
                    nanosToMillis(startNanos - BOOT_NANOS),
                    Math.max(0L, nanosToMillis(endNanos - startNanos))
            ));
        }
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static String metricName(String name) {
        StringBuilder metric = new StringBuilder(name.length());
        boolean separator = false;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                if (separator && !metric.isEmpty()) {
                    metric.append('_');
                }
                metric.append(ch);
                separator = false;
            } else if (ch >= 'A' && ch <= 'Z') {
                if (separator && !metric.isEmpty()) {
                    metric.append('_');
                }
                metric.append(Character.toLowerCase(ch));
                separator = false;
            } else {
                separator = !metric.isEmpty();
            }
        }
        return metric.toString();
    }

    private static ArrayList<Phase> snapshot() {
        synchronized (PHASES) {
            return new ArrayList<>(PHASES);
        }
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        escaped.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        escaped.append('"');
        return escaped.toString();
    }

    public record Phase(String name, long startMillis, long durationMillis) {
    }

    public static final class Scope implements AutoCloseable {
        private final String name;
        private final long startNanos;
        private boolean closed;

        private Scope(String name, long startNanos) {
            this.name = name;
            this.startNanos = startNanos;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                record(name, startNanos, System.nanoTime());
            }
        }
    }
}

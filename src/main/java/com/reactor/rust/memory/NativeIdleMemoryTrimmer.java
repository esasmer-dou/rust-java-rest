package com.reactor.rust.memory;

import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.metrics.Metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Background native memory trim policy for memory-first profiles.
 *
 * <p>The policy intentionally never runs from request handling code. Native allocator trimming can
 * introduce latency spikes, so it is only attempted after the runtime has been idle long enough and
 * active connections are below the configured threshold.</p>
 */
public final class NativeIdleMemoryTrimmer implements AutoCloseable {

    public static final String ENABLED_KEY = "reactor.rust.native-trim.enabled";
    public static final String INITIAL_DELAY_MS_KEY = "reactor.rust.native-trim.initial-delay-ms";
    public static final String INTERVAL_MS_KEY = "reactor.rust.native-trim.interval-ms";
    public static final String MIN_IDLE_MS_KEY = "reactor.rust.native-trim.min-idle-ms";
    public static final String MAX_ACTIVE_CONNECTIONS_KEY = "reactor.rust.native-trim.max-active-connections";
    public static final String MAX_ACTIVE_REQUESTS_KEY = "reactor.rust.native-trim.max-active-requests";
    public static final String RETAIN_SMALL_KEY = "reactor.rust.native-trim.retain-small";
    public static final String RETAIN_MEDIUM_KEY = "reactor.rust.native-trim.retain-medium";
    public static final String RETAIN_LARGE_KEY = "reactor.rust.native-trim.retain-large";
    public static final String RETAIN_HUGE_KEY = "reactor.rust.native-trim.retain-huge";
    public static final String ALLOCATOR_TRIM_ENABLED_KEY = "reactor.rust.native-trim.allocator-trim-enabled";

    static final String METRIC_ENABLED = "reactor.native_trim.enabled";
    static final String METRIC_ATTEMPTS = "reactor.native_trim.attempts_total";
    static final String METRIC_SUCCESS = "reactor.native_trim.success_total";
    static final String METRIC_SKIPPED_ACTIVE = "reactor.native_trim.skipped_active_total";
    static final String METRIC_SKIPPED_NOT_IDLE = "reactor.native_trim.skipped_not_idle_total";
    static final String METRIC_SKIPPED_UNCHANGED = "reactor.native_trim.skipped_unchanged_total";
    static final String METRIC_ERRORS = "reactor.native_trim.errors_total";
    static final String METRIC_LAST_DURATION_MS = "reactor.native_trim.last_duration_ms";
    static final String METRIC_LAST_EPOCH_MS = "reactor.native_trim.last_epoch_ms";

    private static final String LEGACY_HOT_PATH_INTERVAL_KEY = "rust.native.trim.interval";
    private static final long MIN_INTERVAL_MS = 1_000L;
    private static final long PRODUCTION_INITIAL_DELAY_MS = 30_000L;
    private static final long PRODUCTION_INTERVAL_MS = 60_000L;
    private static final long PRODUCTION_MIN_IDLE_MS = 10_000L;
    private static final long NANOS_PER_MS = 1_000_000L;

    private final Config config;
    private final NativeMemoryReleaser releaser;
    private final ActivityProbe activityProbe;
    private final Metrics metrics;
    private final Thread worker;
    private volatile boolean running;
    private long lastRequestCount;
    private long idleSinceNanos;
    private long lastTrimRequestCount = Long.MIN_VALUE;

    NativeIdleMemoryTrimmer(
            Config config,
            NativeMemoryReleaser releaser,
            ActivityProbe activityProbe,
            Metrics metrics,
            long nowNanos
    ) {
        this.config = config;
        this.releaser = releaser;
        this.activityProbe = activityProbe;
        this.metrics = metrics;
        this.lastRequestCount = activityProbe.snapshot().requestCount;
        this.idleSinceNanos = nowNanos;
        this.worker = new Thread(this::runLoop, "reactor-native-idle-trim");
        this.worker.setDaemon(true);
    }

    public static NativeIdleMemoryTrimmer startFromProperties() {
        warnIfLegacyHotPathTrimIsConfigured();

        Metrics metrics = Metrics.getInstance();
        boolean enabled = PropertiesLoader.getBoolean(ENABLED_KEY, false);
        metrics.setGauge(METRIC_ENABLED, enabled ? 1 : 0);
        if (!enabled) {
            return null;
        }

        Config config = Config.fromProperties();
        warnIfRiskyProductionConfig(config);
        NativeIdleMemoryTrimmer trimmer = new NativeIdleMemoryTrimmer(
                config,
                () -> NativeBridge.releaseNativeMemoryRetaining(
                        config.retainSmall,
                        config.retainMedium,
                        config.retainLarge,
                        config.retainHuge,
                        config.allocatorTrimEnabled
                ),
                NativeIdleMemoryTrimmer::nativeActivitySnapshot,
                metrics,
                System.nanoTime()
        );
        trimmer.start();
        FrameworkLogger.info("[JAVA] Native idle memory trim enabled: intervalMs=" + config.intervalMs
                + ", minIdleMs=" + config.minIdleMs
                + ", maxActiveConnections=" + config.maxActiveConnections
                + ", maxActiveRequests=" + config.maxActiveRequests
                + ", retainSmall=" + config.retainSmall
                + ", retainMedium=" + config.retainMedium
                + ", retainLarge=" + config.retainLarge
                + ", retainHuge=" + config.retainHuge
                + ", allocatorTrimEnabled=" + config.allocatorTrimEnabled);
        return trimmer;
    }

    private static void warnIfLegacyHotPathTrimIsConfigured() {
        String legacy = System.getProperty(LEGACY_HOT_PATH_INTERVAL_KEY);
        if (legacy != null && !legacy.isBlank()) {
            FrameworkLogger.warn("[JAVA] " + LEGACY_HOT_PATH_INTERVAL_KEY
                    + " is no longer executed from request handlers. Use reactor.rust.native-trim.* "
                    + "for idle-only native memory trimming.");
        }
    }

    private static void warnIfRiskyProductionConfig(Config config) {
        List<String> warnings = safetyWarnings(config);
        if (!warnings.isEmpty()) {
            FrameworkLogger.warn("[JAVA] Native idle memory trim is opt-in and not a default micro-rest "
                    + "behavior. Endpoint p99/503 gate is required before enabling it in production: "
                    + String.join("; ", warnings));
        }
    }

    static List<String> safetyWarnings(Config config) {
        List<String> warnings = new ArrayList<>(6);
        if (config.initialDelayMs < PRODUCTION_INITIAL_DELAY_MS) {
            warnings.add(INITIAL_DELAY_MS_KEY + "=" + config.initialDelayMs
                    + " is below conservative production recommendation " + PRODUCTION_INITIAL_DELAY_MS);
        }
        if (config.intervalMs < PRODUCTION_INTERVAL_MS) {
            warnings.add(INTERVAL_MS_KEY + "=" + config.intervalMs
                    + " is below conservative production recommendation " + PRODUCTION_INTERVAL_MS);
        }
        if (config.minIdleMs < PRODUCTION_MIN_IDLE_MS) {
            warnings.add(MIN_IDLE_MS_KEY + "=" + config.minIdleMs
                    + " is below conservative production recommendation " + PRODUCTION_MIN_IDLE_MS);
        }
        if (config.maxActiveConnections > 0) {
            warnings.add(MAX_ACTIVE_CONNECTIONS_KEY + "=" + config.maxActiveConnections
                    + " allows trim while keep-alive connections are still open");
        }
        if (config.maxActiveRequests > 0) {
            warnings.add(MAX_ACTIVE_REQUESTS_KEY + "=" + config.maxActiveRequests
                    + " allows trim while native requests are still active");
        }
        if (config.allocatorTrimEnabled) {
            warnings.add(ALLOCATOR_TRIM_ENABLED_KEY + "=true can improve anon reclaim but may hurt the "
                    + "next traffic burst's p99");
        }
        return warnings;
    }

    private void start() {
        running = true;
        worker.start();
    }

    private void runLoop() {
        sleep(config.initialDelayMs);
        while (running) {
            long nextDelayMs = tick(System.nanoTime());
            sleep(nextDelayMs);
        }
    }

    long tick(long nowNanos) {
        ActivitySnapshot activity = activityProbe.snapshot();
        long activeConnections = activity.activeConnections;
        long activeRequests = activity.activeRequests;
        long requestCount = activity.requestCount;

        if (activeConnections > config.maxActiveConnections || activeRequests > config.maxActiveRequests) {
            resetIdleWindow(requestCount, nowNanos);
            metrics.increment(METRIC_SKIPPED_ACTIVE);
            return nextIdleCheckDelayMs(nowNanos);
        }

        if (requestCount != lastRequestCount) {
            resetIdleWindow(requestCount, nowNanos);
            metrics.increment(METRIC_SKIPPED_NOT_IDLE);
            return nextIdleCheckDelayMs(nowNanos);
        }

        if (!idleLongEnough(nowNanos)) {
            metrics.increment(METRIC_SKIPPED_NOT_IDLE);
            return nextIdleCheckDelayMs(nowNanos);
        }

        if (requestCount == lastTrimRequestCount) {
            metrics.increment(METRIC_SKIPPED_UNCHANGED);
            return config.intervalMs;
        }

        metrics.increment(METRIC_ATTEMPTS);
        long startedNanos = System.nanoTime();
        try {
            releaser.release();
            long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / NANOS_PER_MS);
            lastTrimRequestCount = requestCount;
            metrics.increment(METRIC_SUCCESS);
            metrics.setGauge(METRIC_LAST_DURATION_MS, durationMs);
            metrics.setGauge(METRIC_LAST_EPOCH_MS, System.currentTimeMillis());
            FrameworkLogger.debug("[JAVA] Native idle memory trim completed in " + durationMs + " ms");
        } catch (Throwable error) {
            metrics.increment(METRIC_ERRORS);
            FrameworkLogger.debugError("[JAVA] Native idle memory trim failed: " + error.getMessage());
        }
        return config.intervalMs;
    }

    private void resetIdleWindow(long requestCount, long nowNanos) {
        lastRequestCount = requestCount;
        idleSinceNanos = nowNanos;
    }

    private boolean idleLongEnough(long nowNanos) {
        return nowNanos - idleSinceNanos >= config.minIdleMs * NANOS_PER_MS;
    }

    private long nextIdleCheckDelayMs(long nowNanos) {
        long elapsedMs = Math.max(0L, (nowNanos - idleSinceNanos) / NANOS_PER_MS);
        long remainingMs = Math.max(0L, config.minIdleMs - elapsedMs);
        if (remainingMs == 0L) {
            return MIN_INTERVAL_MS;
        }
        return Math.max(MIN_INTERVAL_MS, Math.min(config.intervalMs, remainingMs));
    }

    private void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            if (!running) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        metrics.setGauge(METRIC_ENABLED, 0);
    }

    record Config(
            long initialDelayMs,
            long intervalMs,
            long minIdleMs,
            long maxActiveConnections,
            long maxActiveRequests,
            int retainSmall,
            int retainMedium,
            int retainLarge,
            int retainHuge,
            boolean allocatorTrimEnabled
    ) {
        static Config fromProperties() {
            long initialDelayMs = positiveOrZero(PropertiesLoader.getLong(INITIAL_DELAY_MS_KEY, 30_000L));
            long intervalMs = Math.max(MIN_INTERVAL_MS, PropertiesLoader.getLong(INTERVAL_MS_KEY, 60_000L));
            long minIdleMs = positiveOrZero(PropertiesLoader.getLong(MIN_IDLE_MS_KEY, 10_000L));
            long maxActiveConnections = positiveOrZero(PropertiesLoader.getLong(MAX_ACTIVE_CONNECTIONS_KEY, 0L));
            long maxActiveRequests = positiveOrZero(PropertiesLoader.getLong(MAX_ACTIVE_REQUESTS_KEY, 0L));
            int retainSmall = positiveOrZero(PropertiesLoader.getInt(RETAIN_SMALL_KEY, 16));
            int retainMedium = positiveOrZero(PropertiesLoader.getInt(RETAIN_MEDIUM_KEY, 0));
            int retainLarge = positiveOrZero(PropertiesLoader.getInt(RETAIN_LARGE_KEY, 0));
            int retainHuge = positiveOrZero(PropertiesLoader.getInt(RETAIN_HUGE_KEY, 0));
            boolean allocatorTrimEnabled = PropertiesLoader.getBoolean(ALLOCATOR_TRIM_ENABLED_KEY, true);
            return new Config(
                    initialDelayMs,
                    intervalMs,
                    minIdleMs,
                    maxActiveConnections,
                    maxActiveRequests,
                    retainSmall,
                    retainMedium,
                    retainLarge,
                    retainHuge,
                    allocatorTrimEnabled
            );
        }

        private static long positiveOrZero(long value) {
            return Math.max(0L, value);
        }

        private static int positiveOrZero(int value) {
            return Math.max(0, value);
        }
    }

    @FunctionalInterface
    interface NativeMemoryReleaser {
        void release();
    }

    @FunctionalInterface
    interface ActivityProbe {
        ActivitySnapshot snapshot();
    }

    record ActivitySnapshot(long activeConnections, long activeRequests, long requestCount) {
    }

    private static ActivitySnapshot nativeActivitySnapshot() {
        try {
            String metricsText = NativeBridge.nativeMetricsPrometheus();
            Metrics javaMetrics = Metrics.getInstance();
            long fallbackAllRequests = parsePrometheusLong(metricsText, "reactor_native_http_requests_total",
                    javaMetrics.getCounter("http_requests_total"));
            long fallbackUserRequests = javaMetrics.getCounter("http_user_requests_total");
            return new ActivitySnapshot(
                    parsePrometheusLong(metricsText, "reactor_native_http_connections_active",
                            javaMetrics.getGauge("http_connections_active")),
                    parsePrometheusLong(metricsText, "reactor_native_http_requests_active", 0),
                    parsePrometheusLong(metricsText, "reactor_native_http_user_requests_total",
                            fallbackUserRequests > 0 ? fallbackUserRequests : fallbackAllRequests)
            );
        } catch (Throwable ignored) {
            Metrics javaMetrics = Metrics.getInstance();
            long userRequests = javaMetrics.getCounter("http_user_requests_total");
            return new ActivitySnapshot(
                    javaMetrics.getGauge("http_connections_active"),
                    0,
                    userRequests > 0 ? userRequests : javaMetrics.getCounter("http_requests_total")
            );
        }
    }

    static long parsePrometheusLong(String metricsText, String metricName, long fallback) {
        if (metricsText == null || metricsText.isBlank()) {
            return fallback;
        }
        int index = 0;
        int nameLength = metricName.length();
        while (index < metricsText.length()) {
            int lineEnd = metricsText.indexOf('\n', index);
            if (lineEnd < 0) {
                lineEnd = metricsText.length();
            }
            int valueStart = index + nameLength;
            if (lineEnd > valueStart
                    && metricsText.startsWith(metricName, index)
                    && metricsText.charAt(valueStart) == ' ') {
                return parseLong(metricsText, valueStart + 1, lineEnd, fallback);
            }
            index = lineEnd + 1;
        }
        return fallback;
    }

    private static long parseLong(String value, int start, int end, long fallback) {
        long result = 0L;
        boolean seenDigit = false;
        for (int i = start; i < end; i++) {
            char ch = value.charAt(i);
            if (ch >= '0' && ch <= '9') {
                seenDigit = true;
                result = result * 10L + (ch - '0');
            } else if (ch == '.' || ch == ' ' || ch == '\r') {
                break;
            } else {
                return fallback;
            }
        }
        return seenDigit ? result : fallback;
    }
}

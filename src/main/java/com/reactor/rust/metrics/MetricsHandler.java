package com.reactor.rust.metrics;

import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RoutePlanRegistry;
import com.reactor.rust.config.RuntimeFootprintGate;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.startup.StartupTimeline;

import java.lang.reflect.Method;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;

/**
 * Built-in handler for exposing metrics in Prometheus format.
 * Endpoint: GET /metrics
 */
@Component
public class MetricsHandler {

    /**
     * Get metrics in Prometheus format.
     */
    @GetMapping(value = "/metrics", requestType = Void.class, responseType = RawResponse.class)
    public RawResponse getMetrics() {
        String nativeMetrics = NativeBridge.nativeMetricsPrometheus();
        if (nativeMetrics == null) {
            nativeMetrics = "";
        }
        String javaMetrics = Metrics.getInstance().toPrometheusFormat();
        return RawResponse.text(
                nativeMetrics + "\n" + javaMetrics,
                "text/plain; version=0.0.4; charset=utf-8"
        );
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
     * Single diagnostic report for RSS/smaps, JVM heap, direct buffers, native pool and JNI metrics.
     */
    @GetMapping(value = "/diagnostics/memory", requestType = Void.class, responseType = RawResponse.class)
    public RawResponse getMemoryDiagnostics() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        ClassLoadingMXBean classLoading = ManagementFactory.getClassLoadingMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Runtime runtime = Runtime.getRuntime();
        String nativeDiagnostics = NativeBridge.nativeMemoryDiagnosticsJson();
        if (nativeDiagnostics == null || nativeDiagnostics.isBlank()) {
            nativeDiagnostics = "{}";
        }
        String nativeMetrics = NativeBridge.nativeMetricsPrometheus();
        if (nativeMetrics == null) {
            nativeMetrics = "";
        }
        String nativeDubboMetrics = invokeOptionalStaticString(
                "com.reactor.rust.dubbo.NativeDubboBridge",
                "metricsJson",
                "{}"
        );
        if (nativeDubboMetrics == null || nativeDubboMetrics.isBlank()) {
            nativeDubboMetrics = "{}";
        }

        StringBuilder json = new StringBuilder(12288);
        boolean first;
        json.append('{');
        json.append("\"jvm\":{")
                .append("\"heap_used_bytes\":").append(heap.getUsed()).append(',')
                .append("\"heap_committed_bytes\":").append(heap.getCommitted()).append(',')
                .append("\"heap_max_bytes\":").append(heap.getMax()).append(',')
                .append("\"non_heap_used_bytes\":").append(nonHeap.getUsed()).append(',')
                .append("\"non_heap_committed_bytes\":").append(nonHeap.getCommitted()).append(',')
                .append("\"runtime_total_bytes\":").append(runtime.totalMemory()).append(',')
                .append("\"runtime_free_bytes\":").append(runtime.freeMemory()).append(',')
                .append("\"available_processors\":").append(runtime.availableProcessors()).append(',')
                .append("\"loaded_class_count\":").append(classLoading.getLoadedClassCount()).append(',')
                .append("\"total_loaded_class_count\":").append(classLoading.getTotalLoadedClassCount()).append(',')
                .append("\"unloaded_class_count\":").append(classLoading.getUnloadedClassCount()).append(',')
                .append("\"thread_count\":").append(threads.getThreadCount()).append(',')
                .append("\"daemon_thread_count\":").append(threads.getDaemonThreadCount()).append(',')
                .append("\"peak_thread_count\":").append(threads.getPeakThreadCount())
                .append("},");
        json.append("\"memory_pools\":[");
        first = true;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            MemoryUsage usage = pool.getUsage();
            json.append('{')
                    .append("\"name\":").append(jsonString(pool.getName())).append(',')
                    .append("\"type\":").append(jsonString(pool.getType().name())).append(',')
                    .append("\"used_bytes\":").append(usage == null ? -1 : usage.getUsed()).append(',')
                    .append("\"committed_bytes\":").append(usage == null ? -1 : usage.getCommitted()).append(',')
                    .append("\"max_bytes\":").append(usage == null ? -1 : usage.getMax())
                    .append('}');
        }
        json.append("],");
        json.append("\"buffer_pools\":[");
        first = true;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('{')
                    .append("\"name\":").append(jsonString(pool.getName())).append(',')
                    .append("\"count\":").append(pool.getCount()).append(',')
                    .append("\"memory_used_bytes\":").append(pool.getMemoryUsed()).append(',')
                    .append("\"total_capacity_bytes\":").append(pool.getTotalCapacity())
                    .append('}');
        }
        json.append("],");
        json.append("\"runtime_gate\":").append(RuntimeFootprintGate.lastReportJson()).append(',');
        json.append("\"native\":").append(nativeDiagnostics).append(',');
        json.append("\"native_dubbo\":").append(nativeDubboMetrics).append(',');
        json.append("\"native_metrics_prometheus\":").append(jsonString(nativeMetrics));
        json.append('}');

        return RawResponse.text(json.toString(), "application/json; charset=utf-8");
    }

    @GetMapping(value = "/diagnostics/native/trim", requestType = Void.class, responseType = RawResponse.class)
    public RawResponse trimNativeMemory() {
        NativeBridge.releaseNativeMemory();
        String nativeDiagnostics = NativeBridge.nativeMemoryDiagnosticsJson();
        if (nativeDiagnostics == null || nativeDiagnostics.isBlank()) {
            nativeDiagnostics = "{}";
        }
        return RawResponse.text(nativeDiagnostics, "application/json; charset=utf-8");
    }

    /**
     * Shows which routes are running through optimized/direct paths versus legacy paths.
     */
    @GetMapping(value = "/diagnostics/routes", requestType = Void.class, responseType = RawResponse.class)
    public RawResponse getRoutePlans() {
        return RawResponse.text(RoutePlanRegistry.getInstance().toJson(), "application/json; charset=utf-8");
    }

    @GetMapping(value = "/diagnostics/startup", requestType = Void.class, responseType = RawResponse.class)
    public RawResponse getStartupDiagnostics() {
        return RawResponse.text(StartupTimeline.toJson(), "application/json; charset=utf-8");
    }

    /**
     * Reset all metrics (use with caution!).
     */
    @GetMapping(value = "/metrics/reset", requestType = Void.class, responseType = String.class)
    public String resetMetrics() {
        NativeBridge.nativeResetMetrics();
        invokeOptionalStaticVoid("com.reactor.rust.dubbo.NativeDubboBridge", "resetMetrics");
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

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
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
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        escaped.append('"');
        return escaped.toString();
    }

    private static String invokeOptionalStaticString(String className, String methodName, String fallback) {
        try {
            Class<?> bridgeClass = Class.forName(className);
            Method method = bridgeClass.getMethod(methodName);
            Object result = method.invoke(null);
            return result instanceof String value ? value : fallback;
        } catch (ReflectiveOperationException | LinkageError e) {
            return fallback;
        }
    }

    private static void invokeOptionalStaticVoid(String className, String methodName) {
        try {
            Class<?> bridgeClass = Class.forName(className);
            Method method = bridgeClass.getMethod(methodName);
            method.invoke(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Dubbo adapter is optional; core REST metrics must work without it.
        }
    }
}

package com.reactor.rust.health;

import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.RouteWorkload;
import com.reactor.rust.http.HttpStatus;
import com.reactor.rust.http.MediaType;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.metrics.Metrics;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Low-overhead liveness and on-demand readiness routes.
 *
 * <p>No polling executor is retained. Each dependency probe runs only for a readiness request
 * in a bounded virtual thread so a stuck dependency cannot hold the HTTP worker indefinitely.</p>
 */
@RouteWorkload(RouteWorkload.Type.STANDARD)
public final class HealthEndpoint {

    private final String applicationName;
    private final List<Dependency> dependencies;
    private final RawResponse liveness;

    HealthEndpoint(String applicationName, List<Dependency> dependencies) {
        this.applicationName = applicationName;
        this.dependencies = List.copyOf(dependencies);
        this.liveness = RawResponse.text(
                "{\"status\":\"UP\",\"app\":" + quoted(applicationName) + "}",
                MediaType.APPLICATION_JSON_UTF8);
    }

    @GetMapping(value = "/app/health", responseType = RawResponse.class)
    public ResponseEntity<RawResponse> health() {
        return ResponseEntity.ok(liveness);
    }

    @GetMapping(value = "/app/readiness", responseType = RawResponse.class)
    public ResponseEntity<RawResponse> readiness() {
        Metrics metrics = Metrics.getInstance();
        metrics.increment("health.readiness.requests");
        boolean ready = true;
        StringBuilder json = new StringBuilder(96 + dependencies.size() * 72);
        json.append("{\"status\":\"");
        int statusOffset = json.length();
        json.append("UP");
        json.append("\",\"app\":").append(quoted(applicationName)).append(",\"dependencies\":[");
        for (int index = 0; index < dependencies.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            Dependency dependency = dependencies.get(index);
            ProbeResult result = check(dependency);
            if (dependency.required() && !result.ready()) {
                ready = false;
            }
            String metric = metricName(dependency.name());
            metrics.setGauge("health.dependency." + metric + ".ready", result.ready() ? 1 : 0);
            metrics.recordTiming("health.dependency." + metric + ".latency_ms", result.elapsedMillis());
            json.append("{\"name\":").append(quoted(dependency.name()))
                    .append(",\"required\":").append(dependency.required())
                    .append(",\"status\":\"").append(result.status()).append("\"}");
        }
        json.append("]}");
        if (!ready) {
            json.replace(statusOffset, statusOffset + 2, "DOWN");
            metrics.increment("health.readiness.failures");
        }
        RawResponse body = RawResponse.text(json.toString(), MediaType.APPLICATION_JSON_UTF8);
        return ready
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE, body);
    }

    private static ProbeResult check(Dependency dependency) {
        long started = System.nanoTime();
        AtomicReference<Boolean> ready = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread probe = Thread.ofVirtual()
                .name("reactor-readiness-" + metricName(dependency.name()))
                .unstarted(() -> {
                    try {
                        ready.set(dependency.probe().ready());
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                });
        probe.start();
        try {
            probe.join(dependency.timeoutMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            probe.interrupt();
            return new ProbeResult(false, "INTERRUPTED", elapsedMillis(started));
        }
        if (probe.isAlive()) {
            probe.interrupt();
            return new ProbeResult(false, "TIMEOUT", elapsedMillis(started));
        }
        if (failure.get() != null) {
            return new ProbeResult(false, "DOWN", elapsedMillis(started));
        }
        boolean available = Boolean.TRUE.equals(ready.get());
        return new ProbeResult(available, available ? "UP" : "DOWN", elapsedMillis(started));
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    static String metricName(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return normalized.replaceAll("^_+|_+$", "");
    }

    private static String quoted(String value) {
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> json.append(current < 0x20 ? '?' : current);
            }
        }
        return json.append('"').toString();
    }

    record Dependency(String name, boolean required, long timeoutMillis, DependencyProbe probe) {}

    private record ProbeResult(boolean ready, String status, long elapsedMillis) {}
}

package com.reactor.rust.config;

import com.reactor.rust.logging.FrameworkLogger;

import java.util.Locale;

public final class RuntimeProfiles {

    public static final String PROFILE_DEFAULT = "default";
    public static final String PROFILE_MICRO_REST = "micro-rest";
    public static final String PROFILE_MICRO_REST_PLUS = "micro-rest-plus";
    public static final String PROFILE_MICRO_DUBBO = "micro-dubbo";
    public static final String PROFILE_FAST_START = "fast-start";
    public static final String PROFILE_READY_LOW_LATENCY = "ready-low-latency";
    public static final String PROFILE_LOW_RSS = "low-rss";
    public static final String PROFILE_BALANCED_DUBBO = "balanced-dubbo";
    public static final String PROFILE_THROUGHPUT = "throughput";

    private RuntimeProfiles() {}

    public static void apply() {
        String profile = PropertiesLoader.get("reactor.runtime.profile", PROFILE_DEFAULT)
                .trim()
                .toLowerCase(Locale.ROOT);
        switch (profile) {
            case PROFILE_DEFAULT -> {
                return;
            }
            case PROFILE_MICRO_REST -> applyMicroRest();
            case PROFILE_MICRO_REST_PLUS -> applyMicroRestPlus();
            case PROFILE_MICRO_DUBBO -> applyMicroDubbo();
            case PROFILE_FAST_START -> applyFastStart();
            case PROFILE_READY_LOW_LATENCY -> applyReadyLowLatency();
            case PROFILE_LOW_RSS -> applyLowRss();
            case PROFILE_BALANCED_DUBBO -> applyBalancedDubbo();
            case PROFILE_THROUGHPUT -> applyThroughput();
            default -> throw new IllegalArgumentException("reactor.runtime.profile must be default, micro-rest, "
                    + "micro-rest-plus, micro-dubbo, low-rss, fast-start, ready-low-latency, balanced-dubbo, or throughput");
        }
        FrameworkLogger.info("[JAVA] Runtime profile applied: " + profile);
    }

    private static void applyMicroRest() {
        set("reactor.dubbo.enabled", "false");
        set("reactor.runtime.low-rss-gate.mode", "observe");
        set("reactor.startup.component-index.enabled", "true");
        set("reactor.websocket.enabled", "false");
        set("reactor.static-files.enabled", "false");
        set("reactor.rust.jni.workers", "1");
        set("reactor.rust.jni.queue-capacity", "128");
        set("reactor.rust.http.max-connections", "512");
        set("reactor.rust.http.max-inflight-body-bytes", "4194304");
        set("reactor.rust.http.max-inflight-response-bytes", "8388608");
        set("reactor.rust.http.http1-only-enabled", "true");
        set("reactor.rust.runtime.worker-threads", "1");
        set("reactor.rust.runtime.max-blocking-threads", "1");
        set("reactor.rust.runtime.thread-stack-bytes", "262144");
        set("reactor.rust.file-stream.chunk-bytes", "32768");
        set("reactor.rust.static-file.inline-max-bytes", "0");
        set("reactor.rust.static-file.max-concurrent-streams", "32");
        set("reactor.rust.websocket.max-frame-bytes", "262144");
        set("reactor.rust.websocket.outbound-queue-capacity", "16");
        set("reactor.rust.websocket.send-timeout-ms", "1000");
        set("reactor.rust.response-pool.small-capacity", "8");
        set("reactor.rust.response-pool.medium-capacity", "2");
        set("reactor.rust.response-pool.large-capacity", "1");
        set("reactor.rust.response-pool.huge-capacity", "1");
        set("reactor.rust.native-cache.max-entries", "0");
        set("reactor.rust.native-cache.max-bytes", "0");
        set("reactor.rust.native-cache.ttl-ms", "60000");
        set("reactor.rust.json.writer-retain-max-bytes", "32768");
        set("reactor.rust.async.max-inflight", "64");
        set("reactor.rust.async.response-timeout-ms", "1500");
    }

    private static void applyMicroRestPlus() {
        applyMicroRest();
        set("reactor.rust.route-budget.heavy-json-direct.route-admission.max-concurrent", "80");
        set("reactor.rust.route-budget.heavy-json-direct.route-admission.queue-timeout-ms", "150");
        set("reactor.rust.route-budget.heavy-json-producer.route-admission.max-concurrent", "96");
        set("reactor.rust.route-budget.heavy-json-producer.route-admission.queue-timeout-ms", "125");
        set("reactor.rust.route-budget.heavy-json-legacy.route-admission.max-concurrent", "48");
        set("reactor.rust.route-budget.heavy-json-legacy.route-admission.queue-timeout-ms", "100");
    }

    private static void applyMicroDubbo() {
        applyMicroRest();
        set("reactor.dubbo.enabled", "true");
        set("reactor.dubbo.transport", "native");
        set("reactor.dubbo.runtime-profile", "micro-dubbo");
        set("reactor.dubbo.retries", "0");
        set("reactor.dubbo.check", "false");
        set("reactor.dubbo.registry-check", "false");
        set("reactor.dubbo.connections", "1");
        set("reactor.dubbo.share-connections", "1");
        set("reactor.dubbo.refer-thread-num", "1");
        set("reactor.dubbo.max-inflight", "32");
        set("reactor.dubbo.native-connections-per-endpoint", "1");
        set("reactor.dubbo.native-async-workers", "1");
        set("reactor.dubbo.native-async-queue-capacity", "32");
        set("reactor.dubbo.catalog.adaptive-enabled", "true");
        set("reactor.dubbo.catalog.min-inflight", "1");
        set("reactor.dubbo.catalog.initial-inflight", "2");
        set("reactor.dubbo.catalog.max-inflight", "2");
        set("reactor.dubbo.catalog.response-timeout-ms", "800");
        set("reactor.dubbo.catalog.target-latency-ms", "75");
        set("reactor.dubbo.catalog.high-latency-ms", "250");
        set("reactor.dubbo.catalog.adaptive-sample-size", "64");
        set("reactor.dubbo.catalog.adaptive-increase-step", "1");
        set("reactor.dubbo.catalog.adaptive-decrease-percent", "75");
        set("reactor.dubbo.catalog.rpc-workers", "0");
        set("reactor.dubbo.catalog.rpc-queue-capacity", "0");
    }

    private static void applyFastStart() {
        applyLowRss();
        set("reactor.startup.prewarm.enabled", "false");
        set("reactor.startup.component-index.enabled", "true");
        set("reactor.startup.scan.fallback-enabled", "true");
        set("reactor.native.extract.cache.enabled", "true");
        set("reactor.rust.static-file.inline-max-bytes", "0");
        set("reactor.rust.static-file.max-concurrent-streams", "64");
    }

    private static void applyReadyLowLatency() {
        applyLowRss();
        set("reactor.startup.prewarm.enabled", "true");
        set("reactor.startup.prewarm.json", "true");
        set("reactor.startup.component-index.enabled", "true");
        set("reactor.startup.scan.fallback-enabled", "true");
        set("reactor.native.extract.cache.enabled", "true");
        set("reactor.rust.static-file.inline-max-bytes", "524288");
    }

    private static void applyLowRss() {
        set("reactor.runtime.low-rss-gate.mode", "observe");
        set("reactor.startup.component-index.enabled", "true");
        set("reactor.rust.jni.workers", "2");
        set("reactor.rust.jni.queue-capacity", "512");
        set("reactor.rust.http.max-connections", "1024");
        set("reactor.rust.http.max-inflight-body-bytes", "16777216");
        set("reactor.rust.http.max-inflight-response-bytes", "16777216");
        set("reactor.rust.http.http1-only-enabled", "true");
        set("reactor.rust.runtime.worker-threads", "2");
        set("reactor.rust.runtime.max-blocking-threads", "4");
        set("reactor.rust.runtime.thread-stack-bytes", "262144");
        set("reactor.rust.file-stream.chunk-bytes", "65536");
        set("reactor.rust.static-file.inline-max-bytes", "524288");
        set("reactor.rust.static-file.max-concurrent-streams", "128");
        set("reactor.rust.response-pool.small-capacity", "64");
        set("reactor.rust.response-pool.medium-capacity", "64");
        set("reactor.rust.response-pool.large-capacity", "2");
        set("reactor.rust.response-pool.huge-capacity", "1");
        set("reactor.rust.native-cache.max-entries", "256");
        set("reactor.rust.native-cache.max-bytes", "4194304");
        set("reactor.rust.json.writer-retain-max-bytes", "65536");
        set("reactor.rust.async.max-inflight", "128");
        set("reactor.rust.async.response-timeout-ms", "2000");
        set("reactor.dubbo.transport", "native");
        set("reactor.dubbo.native-connections-per-endpoint", "2");
        set("reactor.dubbo.native-async-workers", "2");
        set("reactor.dubbo.native-async-queue-capacity", "128");
        set("reactor.dubbo.max-inflight", "64");
        set("reactor.dubbo.catalog.adaptive-enabled", "true");
        set("reactor.dubbo.catalog.min-inflight", "2");
        set("reactor.dubbo.catalog.initial-inflight", "4");
        set("reactor.dubbo.catalog.max-inflight", "4");
        set("reactor.dubbo.catalog.response-timeout-ms", "800");
        set("reactor.dubbo.catalog.target-latency-ms", "75");
        set("reactor.dubbo.catalog.high-latency-ms", "250");
        set("reactor.dubbo.catalog.adaptive-sample-size", "128");
        set("reactor.dubbo.catalog.adaptive-increase-step", "1");
        set("reactor.dubbo.catalog.adaptive-decrease-percent", "75");
        set("reactor.dubbo.catalog.rpc-workers", "1");
        set("reactor.dubbo.catalog.rpc-queue-capacity", "0");
    }

    private static void applyBalancedDubbo() {
        set("reactor.rust.jni.workers", "16");
        set("reactor.rust.jni.queue-capacity", "1024");
        set("reactor.rust.http.max-connections", "1200");
        set("reactor.rust.http.max-inflight-body-bytes", "33554432");
        set("reactor.rust.http.max-inflight-response-bytes", "67108864");
        set("reactor.rust.http.http1-only-enabled", "true");
        set("reactor.rust.runtime.worker-threads", "2");
        set("reactor.rust.runtime.max-blocking-threads", "4");
        set("reactor.rust.runtime.thread-stack-bytes", "262144");
        set("reactor.rust.file-stream.chunk-bytes", "65536");
        set("reactor.rust.static-file.inline-max-bytes", "1048576");
        set("reactor.rust.static-file.max-concurrent-streams", "256");
        set("reactor.rust.response-pool.small-capacity", "192");
        set("reactor.rust.response-pool.medium-capacity", "192");
        set("reactor.rust.response-pool.large-capacity", "8");
        set("reactor.rust.response-pool.huge-capacity", "1");
        set("reactor.rust.native-cache.max-entries", "512");
        set("reactor.rust.native-cache.max-bytes", "8388608");
        set("reactor.rust.async.max-inflight", "1024");
        set("reactor.rust.async.response-timeout-ms", "2000");
        set("reactor.dubbo.transport", "native");
        set("reactor.dubbo.native-connections-per-endpoint", "16");
        set("reactor.dubbo.native-async-workers", "8");
        set("reactor.dubbo.native-async-queue-capacity", "1024");
        set("reactor.dubbo.max-inflight", "512");
        set("reactor.dubbo.catalog.adaptive-enabled", "true");
        set("reactor.dubbo.catalog.min-inflight", "16");
        set("reactor.dubbo.catalog.initial-inflight", "64");
        set("reactor.dubbo.catalog.max-inflight", "64");
        set("reactor.dubbo.catalog.response-timeout-ms", "1200");
        set("reactor.dubbo.catalog.target-latency-ms", "150");
        set("reactor.dubbo.catalog.high-latency-ms", "500");
        set("reactor.dubbo.catalog.adaptive-sample-size", "128");
        set("reactor.dubbo.catalog.adaptive-increase-step", "1");
        set("reactor.dubbo.catalog.adaptive-decrease-percent", "75");
        set("reactor.dubbo.catalog.rpc-workers", "1");
        set("reactor.dubbo.catalog.rpc-queue-capacity", "0");
    }

    private static void applyThroughput() {
        set("reactor.rust.jni.workers", "0");
        set("reactor.rust.jni.queue-capacity", "4096");
        set("reactor.rust.http.max-connections", "4096");
        set("reactor.rust.http.max-inflight-body-bytes", "134217728");
        set("reactor.rust.http.max-inflight-response-bytes", "268435456");
        set("reactor.rust.http.http1-only-enabled", "false");
        set("reactor.rust.runtime.worker-threads", "0");
        set("reactor.rust.runtime.max-blocking-threads", "0");
        set("reactor.rust.runtime.thread-stack-bytes", "0");
        set("reactor.rust.file-stream.chunk-bytes", "131072");
        set("reactor.rust.static-file.inline-max-bytes", "2097152");
        set("reactor.rust.static-file.max-concurrent-streams", "1024");
        set("reactor.rust.response-pool.small-capacity", "512");
        set("reactor.rust.response-pool.medium-capacity", "512");
        set("reactor.rust.response-pool.large-capacity", "32");
        set("reactor.rust.response-pool.huge-capacity", "4");
        set("reactor.rust.native-cache.max-entries", "2048");
        set("reactor.rust.native-cache.max-bytes", "33554432");
        set("reactor.rust.async.max-inflight", "4096");
        set("reactor.rust.async.response-timeout-ms", "3000");
        set("reactor.dubbo.transport", "native");
        set("reactor.dubbo.native-connections-per-endpoint", "32");
        set("reactor.dubbo.native-async-workers", "16");
        set("reactor.dubbo.native-async-queue-capacity", "4096");
        set("reactor.dubbo.max-inflight", "1024");
        set("reactor.dubbo.catalog.adaptive-enabled", "true");
        set("reactor.dubbo.catalog.min-inflight", "16");
        set("reactor.dubbo.catalog.initial-inflight", "128");
        set("reactor.dubbo.catalog.max-inflight", "256");
        set("reactor.dubbo.catalog.response-timeout-ms", "2000");
        set("reactor.dubbo.catalog.target-latency-ms", "250");
        set("reactor.dubbo.catalog.high-latency-ms", "750");
        set("reactor.dubbo.catalog.adaptive-sample-size", "256");
        set("reactor.dubbo.catalog.adaptive-increase-step", "2");
        set("reactor.dubbo.catalog.adaptive-decrease-percent", "80");
        set("reactor.dubbo.catalog.rpc-workers", "1");
        set("reactor.dubbo.catalog.rpc-queue-capacity", "0");
    }

    private static void set(String key, String value) {
        PropertiesLoader.setProfileValue(key, value);
    }
}

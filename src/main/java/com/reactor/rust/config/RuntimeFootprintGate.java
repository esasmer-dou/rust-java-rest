package com.reactor.rust.config;

import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.startup.StartupIndex;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Production footprint guard for memory-first runtime profiles.
 *
 * <p>The default mode is {@code observe}: it reports risky low-RSS configuration without breaking
 * existing applications. Set {@code reactor.runtime.low-rss-gate.mode=enforce} in production when a
 * pod must fail fast instead of silently loading optional runtime surface.</p>
 */
public final class RuntimeFootprintGate {

    private static volatile Report lastReport = Report.empty();

    private RuntimeFootprintGate() {
    }

    public static void validate() {
        String mode = PropertiesLoader.get("reactor.runtime.low-rss-gate.mode", "observe")
                .trim()
                .toLowerCase(Locale.ROOT);
        if ("off".equals(mode)) {
            lastReport = Report.empty();
            return;
        }

        String profile = PropertiesLoader.get("reactor.runtime.profile", RuntimeProfiles.PROFILE_DEFAULT)
                .trim()
                .toLowerCase(Locale.ROOT);
        boolean memoryProfile = isMemoryProfile(profile);
        boolean componentIndexEnabled = PropertiesLoader.getBoolean("reactor.startup.component-index.enabled", true);
        boolean componentIndexPresent = componentIndexEnabled && StartupIndex.componentClasses("").present();
        boolean scanFallbackEnabled = PropertiesLoader.getBoolean("reactor.startup.scan.fallback-enabled", true);
        boolean nativeTrimEnabled = PropertiesLoader.getBoolean("reactor.rust.native-trim.enabled", false);
        List<String> warnings = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        if (memoryProfile) {
            checkBoolean("reactor.startup.component-index.enabled", true,
                    "component index is disabled; startup may scan classpath", warnings, violations);
            if (scanFallbackEnabled && !componentIndexPresent) {
                add("classpath scan fallback is enabled and no component index is present; strict low-RSS should "
                                + "ship " + StartupIndex.COMPONENTS_RESOURCE,
                        "reactor.startup.scan.fallback-enabled", warnings, violations);
            }
            checkMaxLong("reactor.rust.native-cache.max-bytes", 0,
                    "native cache retains response bytes; keep it capped or disabled for micro profiles",
                    warnings, violations);
            checkMaxInt("reactor.rust.response-pool.medium-capacity", 8,
                    "medium response pool can retain memory after bursts", warnings, violations);
            checkMaxInt("reactor.rust.response-pool.large-capacity", 1,
                    "large response pool can retain memory after bursts", warnings, violations);
            checkMaxInt("reactor.rust.response-pool.huge-capacity", 0,
                    "huge response pool can retain 1 MiB buffers after bursts", warnings, violations);
            checkMaxInt("reactor.rust.async.frame-pool-capacity", 4,
                    "async frame pool retains reusable completion buffers process-wide", warnings, violations);
            checkMaxInt("reactor.rust.async.frame-initial-bytes", 8_192,
                    "async frame initial allocation is large for a memory-first profile", warnings, violations);
            checkMaxInt("reactor.rust.async.frame-retain-max-bytes", 65_536,
                    "async frame pool can retain large completion buffers after bursts", warnings, violations);

            if (PropertiesLoader.getBoolean("reactor.websocket.enabled", true)) {
                warnings.add("reactor.websocket.enabled=true; WebSocket registry/callback surface may load");
            }
            if (PropertiesLoader.getBoolean("reactor.static-files.enabled", true)) {
                warnings.add("reactor.static-files.enabled=true; static file scanner may run");
            }
            if (!hasJvmArgument("-XX:-TransparentHugePage")) {
                warnings.add("-XX:-TransparentHugePage is not set; Linux transparent huge pages can inflate "
                        + "anonymous RSS for memory-first profiles");
            }
            if (!hasJvmArgument("-XX:ActiveProcessorCount=1")
                    && ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors() > 1) {
                warnings.add("-XX:ActiveProcessorCount=1 is not set; OpenJ9 may size internal runtime work "
                        + "for more CPU than the small-pod memory budget expects");
            }
            if (!hasJvmArgument("-Xgc:threads=1")) {
                warnings.add("-Xgc:threads=1 is not set; GC helper threads can increase native/thread RSS");
            }
            if (!hasJvmArgumentPrefix("-Xss")) {
                warnings.add("-Xss is not set; default Java thread stacks can inflate anonymous RSS");
            }
        }

        if (nativeTrimEnabled) {
            add("native idle trim is enabled; this is an explicit low-traffic/idle-service RSS policy, "
                            + "not a default " + profile + " behavior. Run endpoint p99/503 gate before production",
                    "reactor.rust.native-trim.enabled", warnings, violations);
            checkMinLong("reactor.rust.native-trim.initial-delay-ms", 30_000,
                    "native trim initial delay is below the conservative production recipe",
                    warnings, violations);
            checkMinLong("reactor.rust.native-trim.interval-ms", 60_000,
                    "native trim interval is below the conservative production recipe",
                    warnings, violations);
            checkMinLong("reactor.rust.native-trim.min-idle-ms", 10_000,
                    "native trim minimum idle window is below the conservative production recipe",
                    warnings, violations);
            if (!memoryProfile) {
                add("native idle trim is enabled outside a memory-first profile; high-throughput pods need "
                                + "route-level p99/503 evidence before enabling allocator trim",
                        "reactor.rust.native-trim.enabled", warnings, violations);
            }
        }

        if (RuntimeProfiles.PROFILE_MICRO_DUBBO.equals(profile)) {
            if (!PropertiesLoader.getBoolean("reactor.dubbo.enabled", false)) {
                warnings.add("micro-dubbo profile selected but reactor.dubbo.enabled=false");
            }
            String transport = PropertiesLoader.get("reactor.dubbo.transport", "native")
                    .trim()
                    .toLowerCase(Locale.ROOT);
            if (!"native".equals(transport)) {
                violations.add("micro-dubbo requires reactor.dubbo.transport=native");
            }
            boolean staticProviders = !PropertiesLoader.get("reactor.dubbo.providers", "").trim().isEmpty();
            boolean allowZookeeper = PropertiesLoader.getBoolean(
                    "reactor.runtime.low-rss-gate.allow-zookeeper",
                    false
            );
            if (!staticProviders && !allowZookeeper) {
                warnings.add("reactor.dubbo.providers is empty; Java ZooKeeper discovery may load");
            }
        }

        Report report = new Report(
                mode,
                profile,
                memoryProfile,
                componentIndexPresent,
                scanFallbackEnabled,
                warnings,
                violations
        );
        lastReport = report;
        if (!warnings.isEmpty()) {
            FrameworkLogger.warn("[RuntimeFootprintGate] " + warnings.size()
                    + " low-RSS warning(s): " + String.join("; ", warnings));
        }
        if (!violations.isEmpty()) {
            String message = "[RuntimeFootprintGate] " + violations.size()
                    + " low-RSS violation(s): " + String.join("; ", violations);
            if ("enforce".equals(mode)) {
                throw new IllegalStateException(message);
            }
            FrameworkLogger.warn(message);
        }
    }

    public static String lastReportJson() {
        return lastReport.toJson();
    }

    private static boolean isMemoryProfile(String profile) {
        return RuntimeProfiles.PROFILE_MICRO_REST.equals(profile)
                || RuntimeProfiles.PROFILE_MICRO_DUBBO.equals(profile)
                || RuntimeProfiles.PROFILE_LOW_RSS.equals(profile);
    }

    private static void checkBoolean(
            String key,
            boolean expected,
            String message,
            List<String> warnings,
            List<String> violations
    ) {
        boolean actual = PropertiesLoader.getBoolean(key, !expected);
        if (actual != expected) {
            add(message + " (" + key + "=" + actual + ")", key, warnings, violations);
        }
    }

    private static void checkMaxInt(
            String key,
            int max,
            String message,
            List<String> warnings,
            List<String> violations
    ) {
        int actual = PropertiesLoader.getInt(key, 0);
        if (actual > max) {
            add(message + " (" + key + "=" + actual + ", max=" + max + ")", key, warnings, violations);
        }
    }

    private static void checkMaxLong(
            String key,
            long max,
            String message,
            List<String> warnings,
            List<String> violations
    ) {
        long actual = PropertiesLoader.getLong(key, 0);
        if (actual > max) {
            add(message + " (" + key + "=" + actual + ", max=" + max + ")", key, warnings, violations);
        }
    }

    private static void checkMinLong(
            String key,
            long min,
            String message,
            List<String> warnings,
            List<String> violations
    ) {
        long actual = PropertiesLoader.getLong(key, Long.MAX_VALUE);
        if (actual < min) {
            add(message + " (" + key + "=" + actual + ", min=" + min + ")", key, warnings, violations);
        }
    }

    private static void add(String message, String key, List<String> warnings, List<String> violations) {
        if (PropertiesLoader.getBoolean("reactor.runtime.low-rss-gate.strict." + key, false)) {
            violations.add(message);
        } else {
            warnings.add(message);
        }
    }

    private static boolean hasJvmArgument(String expected) {
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (expected.equals(argument)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasJvmArgumentPrefix(String expectedPrefix) {
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith(expectedPrefix)) {
                return true;
            }
        }
        return false;
    }

    private record Report(
            String mode,
            String profile,
            boolean memoryProfile,
            boolean componentIndexPresent,
            boolean scanFallbackEnabled,
            List<String> warnings,
            List<String> violations
    ) {
        static Report empty() {
            return new Report("off", "unknown", false, false, false, List.of(), List.of());
        }

        String toJson() {
            return new StringBuilder(512)
                    .append('{')
                    .append("\"mode\":").append(json(mode)).append(',')
                    .append("\"profile\":").append(json(profile)).append(',')
                    .append("\"memory_profile\":").append(memoryProfile).append(',')
                    .append("\"component_index_present\":").append(componentIndexPresent).append(',')
                    .append("\"scan_fallback_enabled\":").append(scanFallbackEnabled).append(',')
                    .append("\"loaded_class_count\":")
                    .append(ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()).append(',')
                    .append("\"thread_count\":")
                    .append(ManagementFactory.getThreadMXBean().getThreadCount()).append(',')
                    .append("\"warnings\":").append(jsonArray(warnings)).append(',')
                    .append("\"violations\":").append(jsonArray(violations))
                    .append('}')
                    .toString();
        }
    }

    private static String jsonArray(List<String> values) {
        StringBuilder json = new StringBuilder(values.size() * 64 + 2);
        json.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(json(values.get(i)));
        }
        json.append(']');
        return json.toString();
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
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
                        escaped.append("\\u");
                        String hex = Integer.toHexString(ch);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            escaped.append('0');
                        }
                        escaped.append(hex);
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        escaped.append('"');
        return escaped.toString();
    }
}

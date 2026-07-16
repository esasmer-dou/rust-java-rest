package com.reactor.rust.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeProfilesTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetProperties() throws Exception {
        properties().clear();
        explicitKeys().clear();
        System.clearProperty("reactor.config.file");
        System.clearProperty("reactor.runtime.profile");
        System.clearProperty("reactor.websocket.enabled");
        System.clearProperty("reactor.rust.native-trim.enabled");
        System.clearProperty("reactor.rust.native-trim.initial-delay-ms");
        System.clearProperty("reactor.rust.native-trim.interval-ms");
        System.clearProperty("reactor.rust.native-trim.min-idle-ms");
        System.clearProperty("reactor.rust.route-budget.heavy-json-direct.route-admission.max-concurrent");
        System.clearProperty("reactor.rust.route-budget.heavy-json-direct.route-admission.queue-timeout-ms");
        System.clearProperty("reactor.runtime.low-rss-gate.mode");
        System.clearProperty("reactor.runtime.low-rss-gate.strict.reactor.rust.native-trim.enabled");
    }

    @Test
    void profileAppliesDefaultWhenKeyIsNotExplicitlyConfigured() throws Exception {
        properties().setProperty("reactor.runtime.profile", RuntimeProfiles.PROFILE_MICRO_REST);
        explicitKeys().add("reactor.runtime.profile");

        RuntimeProfiles.apply();

        assertFalse(PropertiesLoader.getBoolean("reactor.websocket.enabled", true));
    }

    @Test
    void explicitPropertiesOverrideProfileDefaults() throws Exception {
        properties().setProperty("reactor.runtime.profile", RuntimeProfiles.PROFILE_MICRO_REST);
        properties().setProperty("reactor.websocket.enabled", "true");
        explicitKeys().add("reactor.runtime.profile");
        explicitKeys().add("reactor.websocket.enabled");

        RuntimeProfiles.apply();

        assertTrue(PropertiesLoader.getBoolean("reactor.websocket.enabled", false));
    }

    @Test
    void bundledClasspathDefaultsDoNotBlockProfileDefaults() {
        PropertiesLoader.load();

        RuntimeProfiles.apply();

        assertFalse(PropertiesLoader.getBoolean("reactor.websocket.enabled", true));
        assertFalse(PropertiesLoader.getBoolean("reactor.static-files.enabled", true));
        assertEquals(1, PropertiesLoader.getInt("reactor.rust.jni.workers", 0));
        assertEquals(128, PropertiesLoader.getInt("reactor.rust.jni.queue-capacity", 0));
        assertEquals(2, PropertiesLoader.getInt("reactor.rust.response-pool.medium-capacity", 0));
        assertEquals(0, PropertiesLoader.getInt("reactor.rust.response-pool.large-capacity", -1));
        assertEquals(0, PropertiesLoader.getInt("reactor.rust.response-pool.huge-capacity", -1));
        assertEquals(0, PropertiesLoader.getInt("reactor.rust.native-cache.max-bytes", -1));
        assertEquals(8192, PropertiesLoader.getInt("reactor.rust.async.frame-initial-bytes", -1));
    }

    @Test
    void configuredOverlayIsExplicitAndNotOverwrittenByRuntimeProfile() throws Exception {
        Path overlay = tempDir.resolve("production.properties");
        Files.writeString(overlay, String.join(System.lineSeparator(),
                "reactor.runtime.profile=micro-rest",
                "reactor.websocket.enabled=true",
                "reactor.rust.jni.queue-capacity=777"));
        System.setProperty("reactor.config.file", overlay.toString());

        PropertiesLoader.load();
        RuntimeProfiles.apply();

        assertTrue(PropertiesLoader.getBoolean("reactor.websocket.enabled", false));
        assertEquals(777, PropertiesLoader.getInt("reactor.rust.jni.queue-capacity", 0));
        assertTrue(explicitKeys().contains("reactor.websocket.enabled"));
        assertTrue(explicitKeys().contains("reactor.rust.jni.queue-capacity"));
    }

    @Test
    void nativeTrimEnabledIsVisibleInRuntimeFootprintGate() throws Exception {
        properties().setProperty("reactor.runtime.profile", RuntimeProfiles.PROFILE_MICRO_REST);
        properties().setProperty("reactor.rust.native-trim.enabled", "true");
        properties().setProperty("reactor.rust.native-trim.initial-delay-ms", "1000");
        properties().setProperty("reactor.rust.native-trim.interval-ms", "1000");
        properties().setProperty("reactor.rust.native-trim.min-idle-ms", "1000");

        RuntimeFootprintGate.validate();

        String report = RuntimeFootprintGate.lastReportJson();
        assertTrue(report.contains("native idle trim is enabled"));
        assertTrue(report.contains("p99/503 gate"));
    }

    @Test
    void microRestPlusAppliesRouteBudgetDefaults() throws Exception {
        properties().setProperty("reactor.runtime.profile", RuntimeProfiles.PROFILE_MICRO_REST_PLUS);

        RuntimeProfiles.apply();

        assertEquals(80, PropertiesLoader.getInt(
                "reactor.rust.route-budget.heavy-json-direct.route-admission.max-concurrent",
                0
        ));
        assertEquals(150, PropertiesLoader.getInt(
                "reactor.rust.route-budget.heavy-json-direct.route-admission.queue-timeout-ms",
                0
        ));
        assertEquals(96, PropertiesLoader.getInt(
                "reactor.rust.route-budget.heavy-json-producer.route-admission.max-concurrent",
                0
        ));
        assertEquals(125, PropertiesLoader.getInt(
                "reactor.rust.route-budget.heavy-json-producer.route-admission.queue-timeout-ms",
                0
        ));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> explicitKeys() throws Exception {
        Field field = PropertiesLoader.class.getDeclaredField("explicitPropertyKeys");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    private static Properties properties() throws Exception {
        Field field = PropertiesLoader.class.getDeclaredField("properties");
        field.setAccessible(true);
        return (Properties) field.get(null);
    }
}

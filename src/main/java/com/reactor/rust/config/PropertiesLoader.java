package com.reactor.rust.config;

import com.reactor.rust.logging.FrameworkLogger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Simple Properties Loader - No Spring
 * Constraint #8: Properties-based configuration
 */
public final class PropertiesLoader {

    private static final Properties properties = new Properties();
    private static final Set<String> explicitPropertyKeys = new HashSet<>();
    private static final String CONFIG_FILE = "rust-spring.properties";
    private static final String[] SEARCH_PATHS = {
            CONFIG_FILE,
            "config/" + CONFIG_FILE,
            "../config/" + CONFIG_FILE,
            "src/main/resources/" + CONFIG_FILE
    };

    private PropertiesLoader() {}

    /**
     * Load properties from file
     */
    public static void load() {
        properties.clear();
        explicitPropertyKeys.clear();

        // Try classpath first. The framework's own bundled properties are defaults, not user
        // overrides; otherwise runtime profiles such as micro-rest cannot narrow pool/cache values.
        URL classpathResource = PropertiesLoader.class.getClassLoader().getResource(CONFIG_FILE);
        if (classpathResource != null) {
            try (InputStream is = classpathResource.openStream()) {
                properties.load(is);
                if (isExternalClasspathConfig(classpathResource)) {
                    recordExplicitPropertyKeys();
                    FrameworkLogger.info("[JAVA] Properties loaded from classpath: " + CONFIG_FILE);
                } else {
                    FrameworkLogger.info("[JAVA] Framework default properties loaded from classpath: "
                            + CONFIG_FILE);
                }
                return;
            } catch (IOException ignored) {
            }
        }

        // Try file system paths
        for (String path : SEARCH_PATHS) {
            Path filePath = Paths.get(path);
            if (Files.exists(filePath)) {
                try (InputStream is = Files.newInputStream(filePath)) {
                    properties.load(is);
                    recordExplicitPropertyKeys();
                    FrameworkLogger.info("[JAVA] Properties loaded from file: " + filePath.toAbsolutePath());
                    return;
                } catch (IOException ignored) {}
            }
        }

        // Load defaults
        loadDefaults();
        FrameworkLogger.info("[JAVA] Using default properties");
    }

    /**
     * Load default properties
     */
    private static void loadDefaults() {
        properties.setProperty("server.port", "8080");
        properties.setProperty("server.host", "0.0.0.0");
        properties.setProperty("reactor.runtime.profile", "micro-rest");
        properties.setProperty("native.library.path", "native/rust_hyper.dll");
        properties.setProperty("reactor.native.load.java-library-path-first", "true");
        properties.setProperty("reactor.native.extract.cache.enabled", "true");
        properties.setProperty("reactor.native.extract.cache-dir", "");
        properties.setProperty("reactor.startup.component-index.enabled", "true");
        properties.setProperty("reactor.startup.component-index.required", "false");
        properties.setProperty("reactor.startup.route-index.validate", "false");
        properties.setProperty("reactor.startup.route-index.required", "false");
        properties.setProperty("reactor.startup.scan.fallback-enabled", "true");
        properties.setProperty("reactor.startup.prewarm.enabled", "false");
        properties.setProperty("reactor.startup.prewarm.json", "true");
        properties.setProperty("reactor.runtime.low-rss-gate.mode", "observe");
        properties.setProperty("reactor.runtime.low-rss-gate.allow-zookeeper", "false");
        properties.setProperty("reactor.websocket.enabled", "true");
        properties.setProperty("reactor.static-files.enabled", "true");
        properties.setProperty("reactor.instanton.checkpoint.enabled", "false");
        properties.setProperty("reactor.instanton.checkpoint.dir", "/checkpoint");
        properties.setProperty("reactor.instanton.checkpoint.fail-on-unavailable", "true");
        properties.setProperty("reactor.instanton.checkpoint.leave-running", "false");
        properties.setProperty("reactor.instanton.checkpoint.shell-job", "true");
        properties.setProperty("reactor.instanton.checkpoint.file-locks", "true");
        properties.setProperty("reactor.instanton.checkpoint.auto-dedup", "false");
        properties.setProperty("reactor.instanton.checkpoint.tcp-close", "true");
        properties.setProperty("reactor.instanton.checkpoint.log-file", "checkpoint.log");
        properties.setProperty("reactor.instanton.checkpoint.log-level", "4");
        properties.setProperty("reactor.rust.http.max-request-body-bytes", "1048576");
        properties.setProperty("reactor.rust.http.max-response-body-bytes", "8388608");
        properties.setProperty("reactor.rust.http.max-inflight-body-bytes", "33554432");
        properties.setProperty("reactor.rust.http.max-inflight-response-bytes", "67108864");
        properties.setProperty("reactor.rust.http.max-connections", "1024");
        properties.setProperty("reactor.rust.http.max-request-header-bytes", "16384");
        properties.setProperty("reactor.rust.http.max-request-headers", "64");
        properties.setProperty("reactor.rust.http.header-read-timeout-ms", "5000");
        properties.setProperty("reactor.rust.http.request-body-timeout-ms", "10000");
        properties.setProperty("reactor.rust.http.idle-timeout-ms", "30000");
        properties.setProperty("reactor.rust.http.http1-only-enabled", "false");
        properties.setProperty("reactor.rust.http.keep-alive-enabled", "true");
        properties.setProperty("reactor.rust.log.level", "error");
        properties.setProperty("reactor.rust.java.log.level", "warn");
        properties.setProperty("reactor.rust.jni.workers", "0");
        properties.setProperty("reactor.rust.jni.queue-capacity", "1024");
        properties.setProperty("reactor.rust.response-pool.small-capacity", "256");
        properties.setProperty("reactor.rust.response-pool.medium-capacity", "384");
        properties.setProperty("reactor.rust.response-pool.large-capacity", "16");
        properties.setProperty("reactor.rust.response-pool.huge-capacity", "2");
        properties.setProperty("reactor.rust.native-cache.max-entries", "1024");
        properties.setProperty("reactor.rust.native-cache.max-bytes", "16777216");
        properties.setProperty("reactor.rust.native-cache.ttl-ms", "300000");
        properties.setProperty("reactor.rust.native-trim.enabled", "false");
        properties.setProperty("reactor.rust.native-trim.initial-delay-ms", "30000");
        properties.setProperty("reactor.rust.native-trim.interval-ms", "60000");
        properties.setProperty("reactor.rust.native-trim.min-idle-ms", "10000");
        properties.setProperty("reactor.rust.native-trim.max-active-connections", "0");
        properties.setProperty("reactor.rust.native-trim.max-active-requests", "0");
        properties.setProperty("reactor.rust.native-trim.retain-small", "16");
        properties.setProperty("reactor.rust.native-trim.retain-medium", "0");
        properties.setProperty("reactor.rust.native-trim.retain-large", "0");
        properties.setProperty("reactor.rust.native-trim.retain-huge", "0");
        properties.setProperty("reactor.rust.native-trim.allocator-trim-enabled", "true");
        properties.setProperty("reactor.rust.runtime.worker-threads", "0");
        properties.setProperty("reactor.rust.runtime.max-blocking-threads", "0");
        properties.setProperty("reactor.rust.runtime.thread-stack-bytes", "0");
        properties.setProperty("reactor.rust.file-stream.chunk-bytes", "65536");
        properties.setProperty("reactor.rust.static-file.inline-max-bytes", "524288");
        properties.setProperty("reactor.rust.static-file.max-concurrent-streams", "128");
        properties.setProperty("reactor.rust.json.writer-initial-bytes", "4096");
        properties.setProperty("reactor.rust.json.writer-retain-max-bytes", "262144");
        properties.setProperty("reactor.rust.async.max-inflight", "1024");
        properties.setProperty("reactor.rust.async.response-timeout-ms", "2000");
        properties.setProperty("reactor.rust.route-admission.enabled", "true");
        properties.setProperty("reactor.rust.route-admission.default-max-concurrent", "0");
        properties.setProperty("reactor.rust.route-admission.default-queue-timeout-ms", "0");
        properties.setProperty("reactor.rust.jni-admission.enabled", "true");
        properties.setProperty("reactor.rust.jni-admission.default-max-pending", "0");
        properties.setProperty("reactor.rust.jni-admission.default-queue-timeout-ms", "0");
        properties.setProperty("reactor.dubbo.enabled", "false");
        properties.setProperty("reactor.dubbo.application-name", "rust-java-rest-consumer");
        properties.setProperty("reactor.dubbo.registry-address", "zookeeper://127.0.0.1:2181");
        properties.setProperty("reactor.dubbo.registry-root", "dubbo");
        properties.setProperty("reactor.dubbo.providers", "");
        properties.setProperty("reactor.dubbo.timeout-ms", "800");
        properties.setProperty("reactor.dubbo.retries", "0");
        properties.setProperty("reactor.dubbo.check", "false");
        properties.setProperty("reactor.dubbo.registry-check", "false");
        properties.setProperty("reactor.dubbo.connections", "1");
        properties.setProperty("reactor.dubbo.share-connections", "1");
        properties.setProperty("reactor.dubbo.refer-thread-num", "1");
        properties.setProperty("reactor.dubbo.max-inflight", "32");
        properties.setProperty("reactor.dubbo.max-response-bytes", "8388608");
        properties.setProperty("reactor.dubbo.native-connections-per-endpoint", "1");
        properties.setProperty("reactor.dubbo.native-async-workers", "1");
        properties.setProperty("reactor.dubbo.native-async-queue-capacity", "32");
        properties.setProperty("reactor.dubbo.runtime-profile", "micro-dubbo");
        properties.setProperty("reactor.dubbo.transport", "native");
        properties.setProperty("reactor.dubbo.cluster", "failfast");
        properties.setProperty("reactor.dubbo.loadbalance", "random");
        properties.setProperty("reactor.dubbo.serialization", "hessian2");
        properties.setProperty("reactor.optimizer.mode", "observe");
        properties.setProperty("reactor.optimizer.report.enabled", "true");
        properties.setProperty("reactor.optimizer.report.verbose", "true");
        properties.setProperty("reactor.optimizer.runtime-metrics-enabled", "false");
        properties.setProperty("reactor.optimizer.fail-on-fallback", "false");
        properties.setProperty("reactor.optimizer.fail-on-legacy", "false");
        properties.setProperty("reactor.optimizer.fail-on-implicit-raw-request-data", "false");
        properties.setProperty("reactor.optimizer.fail-on-heavy-json-object-graph", "false");
        properties.setProperty("reactor.optimizer.fail-on-benchmark-only-routes", "false");
        properties.setProperty("reactor.optimizer.required-fast-routes", "");
    }

    public static boolean hasExternalOverride(String key) {
        return System.getProperty(key) != null || System.getenv(toEnvKey(key)) != null;
    }

    public static void setProfileValue(String key, String value) {
        if (!hasExternalOverride(key) && !explicitPropertyKeys.contains(key)) {
            properties.setProperty(key, value);
        }
    }

    public static void setDefault(String key, String value) {
        if (get(key) == null) {
            properties.setProperty(key, value);
        }
    }

    /**
     * Get string property
     */
    public static String get(String key) {
        // Check system property first (highest priority)
        String value = System.getProperty(key);
        if (value != null) {
            return value;
        }
        // Check environment variable
        value = System.getenv(toEnvKey(key));
        if (value != null) {
            return value;
        }
        // Check loaded properties
        return properties.getProperty(key);
    }

    /**
     * Get string property with default
     */
    public static String get(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Get int property
     */
    public static int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get boolean property
     */
    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * Get all properties
     */
    public static Properties getAll() {
        return new Properties(properties);
    }

    /**
     * Get long property
     */
    public static long getLong(String key, long defaultValue) {
        String value = get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get double property
     */
    public static double getDouble(String key, double defaultValue) {
        String value = get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String toEnvKey(String key) {
        return key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static boolean isExternalClasspathConfig(URL resource) {
        URL codeSource = PropertiesLoader.class.getProtectionDomain().getCodeSource() == null
                ? null
                : PropertiesLoader.class.getProtectionDomain().getCodeSource().getLocation();
        if (codeSource == null) {
            return true;
        }
        String resourceUrl = resource.toExternalForm();
        String codeSourceUrl = codeSource.toExternalForm();
        return !(resourceUrl.startsWith(codeSourceUrl)
                || resourceUrl.startsWith("jar:" + codeSourceUrl + "!/"));
    }

    private static void recordExplicitPropertyKeys() {
        explicitPropertyKeys.addAll(properties.stringPropertyNames());
    }
}

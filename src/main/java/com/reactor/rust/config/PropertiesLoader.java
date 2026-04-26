package com.reactor.rust.config;

import com.reactor.rust.logging.FrameworkLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Simple Properties Loader - No Spring
 * Constraint #8: Properties-based configuration
 */
public final class PropertiesLoader {

    private static final Properties properties = new Properties();
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
        // Try classpath first
        try (InputStream is = PropertiesLoader.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                properties.load(is);
                FrameworkLogger.info("[JAVA] Properties loaded from classpath: " + CONFIG_FILE);
                return;
            }
        } catch (IOException ignored) {}

        // Try file system paths
        for (String path : SEARCH_PATHS) {
            Path filePath = Paths.get(path);
            if (Files.exists(filePath)) {
                try (InputStream is = Files.newInputStream(filePath)) {
                    properties.load(is);
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
        properties.setProperty("native.library.path", "native/rust_hyper.dll");
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
        properties.setProperty("reactor.rust.runtime.worker-threads", "0");
        properties.setProperty("reactor.rust.runtime.max-blocking-threads", "0");
        properties.setProperty("reactor.rust.runtime.thread-stack-bytes", "0");
        properties.setProperty("reactor.rust.json.writer-initial-bytes", "4096");
        properties.setProperty("reactor.rust.json.writer-retain-max-bytes", "262144");
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
        value = System.getenv(key.replace('.', '_').toUpperCase());
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
}

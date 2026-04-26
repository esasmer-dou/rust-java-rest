package com.reactor.rust.logging;

import com.reactor.rust.config.PropertiesLoader;

import java.util.Locale;

/**
 * Minimal logger for framework startup/runtime diagnostics.
 *
 * <p>Intentionally avoids external logging frameworks to keep RSS and startup
 * overhead low. Hot paths should not log except behind debug checks.</p>
 */
public final class FrameworkLogger {

    private static final int OFF = 0;
    private static final int ERROR = 1;
    private static final int WARN = 2;
    private static final int INFO = 3;
    private static final int DEBUG = 4;

    private FrameworkLogger() {}

    public static boolean isDebugEnabled() {
        return level() >= DEBUG;
    }

    public static void error(String message) {
        if (level() >= ERROR) {
            System.err.println(message);
        }
    }

    public static void warn(String message) {
        if (level() >= WARN) {
            System.err.println(message);
        }
    }

    public static void info(String message) {
        if (level() >= INFO) {
            System.out.println(message);
        }
    }

    public static void debug(String message) {
        if (level() >= DEBUG) {
            System.out.println(message);
        }
    }

    public static void debugError(String message) {
        if (level() >= DEBUG) {
            System.err.println(message);
        }
    }

    private static int level() {
        String configured = PropertiesLoader.get("reactor.rust.java.log.level", "warn");
        return switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case "off", "none" -> OFF;
            case "error" -> ERROR;
            case "warn", "warning" -> WARN;
            case "info" -> INFO;
            case "debug", "trace" -> DEBUG;
            default -> WARN;
        };
    }
}

package com.reactor.rust.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Direct startup-time conversions used by generated configuration factories. */
public final class ConfigurationBinder {

    private ConfigurationBinder() {}

    public static String string(String key, String defaultValue, boolean required) {
        String value = PropertiesLoader.get(key);
        if (value == null) {
            value = defaultValue;
        }
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException("Required configuration property is missing: " + key);
            }
            return null;
        }
        return value;
    }

    public static Optional<String> optionalString(String key) {
        return Optional.ofNullable(PropertiesLoader.get(key));
    }

    public static int integer(String key, String defaultValue) {
        return parse(key, value(key, defaultValue), Integer::parseInt, "integer");
    }

    public static long longValue(String key, String defaultValue) {
        return parse(key, value(key, defaultValue), Long::parseLong, "long");
    }

    public static short shortValue(String key, String defaultValue) {
        return parse(key, value(key, defaultValue), Short::parseShort, "short");
    }

    public static double doubleValue(String key, String defaultValue) {
        return parse(key, value(key, defaultValue), Double::parseDouble, "double");
    }

    public static boolean booleanValue(String key, String defaultValue) {
        String value = value(key, defaultValue).trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw invalid(key, value, "boolean", null);
        };
    }

    public static Duration duration(String key, String defaultValue) {
        String value = value(key, defaultValue).trim().toLowerCase(Locale.ROOT);
        try {
            if (value.startsWith("p")) {
                return Duration.parse(value.toUpperCase(Locale.ROOT));
            }
            if (value.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2).trim()));
            }
            if (value.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1).trim()));
            }
            if (value.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1).trim()));
            }
            if (value.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1).trim()));
            }
            return Duration.ofMillis(Long.parseLong(value));
        } catch (RuntimeException failure) {
            throw invalid(key, value, "duration such as 250ms, 5s, 2m or ISO-8601", failure);
        }
    }

    public static long dataSizeBytes(String key, String defaultValue) {
        String value = value(key, defaultValue).trim().toLowerCase(Locale.ROOT);
        long multiplier = 1L;
        String number = value;
        if (value.endsWith("kib")) {
            multiplier = 1024L;
            number = value.substring(0, value.length() - 3);
        } else if (value.endsWith("kb")) {
            multiplier = 1_000L;
            number = value.substring(0, value.length() - 2);
        } else if (value.endsWith("mib")) {
            multiplier = 1024L * 1024L;
            number = value.substring(0, value.length() - 3);
        } else if (value.endsWith("mb")) {
            multiplier = 1_000_000L;
            number = value.substring(0, value.length() - 2);
        } else if (value.endsWith("gib")) {
            multiplier = 1024L * 1024L * 1024L;
            number = value.substring(0, value.length() - 3);
        } else if (value.endsWith("gb")) {
            multiplier = 1_000_000_000L;
            number = value.substring(0, value.length() - 2);
        } else if (value.endsWith("b")) {
            number = value.substring(0, value.length() - 1);
        }
        try {
            return Math.multiplyExact(Long.parseLong(number.trim()), multiplier);
        } catch (RuntimeException failure) {
            throw invalid(key, value, "data size such as 64KiB or 8MiB", failure);
        }
    }

    public static <E extends Enum<E>> E enumValue(
            String key,
            String defaultValue,
            Class<E> enumType) {
        String value = value(key, defaultValue);
        for (E constant : enumType.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value.trim())) {
                return constant;
            }
        }
        throw invalid(key, value, enumType.getSimpleName(), null);
    }

    public static List<String> stringList(String key, String defaultValue) {
        String value = PropertiesLoader.get(key);
        if (value == null) {
            value = defaultValue;
        }
        if (value == null || value.isBlank()) {
            return List.of();
        }
        ArrayList<String> values = new ArrayList<>();
        for (String item : value.split(",")) {
            String normalized = item.trim();
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return Collections.unmodifiableList(values);
    }

    public static boolean matches(String key, String expected, boolean matchIfMissing) {
        String value = PropertiesLoader.get(key);
        if (value == null) {
            return matchIfMissing;
        }
        return expected == null || expected.isBlank() || expected.equalsIgnoreCase(value.trim());
    }

    public static boolean profileMatches(String... profiles) {
        String active = PropertiesLoader.get("reactor.runtime.profile", "micro-rest");
        for (String profile : profiles) {
            if (profile != null && profile.equalsIgnoreCase(active)) {
                return true;
            }
        }
        return false;
    }

    private static String value(String key, String defaultValue) {
        String value = PropertiesLoader.get(key);
        if (value == null) {
            value = defaultValue;
        }
        if (value == null) {
            throw new IllegalArgumentException("Required configuration property is missing: " + key);
        }
        return value;
    }

    private static <T> T parse(String key, String value, Parser<T> parser, String expected) {
        try {
            return parser.parse(value.trim());
        } catch (RuntimeException failure) {
            throw invalid(key, value, expected, failure);
        }
    }

    private static IllegalArgumentException invalid(
            String key,
            String value,
            String expected,
            Throwable cause) {
        return new IllegalArgumentException(
                "Invalid configuration property " + key + "='" + value + "'; expected " + expected,
                cause);
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(String value);
    }
}

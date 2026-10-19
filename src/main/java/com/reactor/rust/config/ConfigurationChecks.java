package com.reactor.rust.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Explicit startup checks. Reports keys, never values or credentials. No runtime polling. */
public final class ConfigurationChecks {
    private ConfigurationChecks() {}

    public static List<String> unknownKeys(Collection<String> supplied, Set<String> known, String ownedPrefix) {
        if (ownedPrefix == null || ownedPrefix.isBlank()) throw new IllegalArgumentException("An owned key prefix is required");
        return supplied.stream().filter(key -> key.startsWith(ownedPrefix) && !known.contains(key))
                .sorted().map(key -> "Unknown property: " + key).toList();
    }

    /** Both keys must use the same unit. Missing optional values are not errors. */
    public static List<String> lessThan(Function<String, String> values, String smaller, String larger) {
        var issues = new ArrayList<String>(2);
        Long a = positive(values.apply(smaller), smaller, issues);
        Long b = positive(values.apply(larger), larger, issues);
        if (a != null && b != null && a >= b) issues.add(smaller + " must be less than " + larger);
        return List.copyOf(issues);
    }

    public static void requireValid(Collection<String> issues) {
        if (!issues.isEmpty()) throw new IllegalArgumentException(String.join("; ", issues));
    }

    private static Long positive(String value, String key, List<String> issues) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) { }
        issues.add(key + " must be a positive integer");
        return null;
    }
}

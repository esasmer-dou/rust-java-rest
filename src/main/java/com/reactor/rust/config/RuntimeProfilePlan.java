package com.reactor.rust.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, startup-validated set of profile defaults.
 *
 * <p>Applying a plan never replaces an explicit JVM, environment or external-file override.</p>
 */
public final class RuntimeProfilePlan {

    private final String name;
    private final Map<String, String> values;

    private RuntimeProfilePlan(Builder builder) {
        this.name = builder.name;
        this.values = Map.copyOf(builder.values);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public Map<String, String> values() {
        return values;
    }

    public void apply() {
        values.forEach(PropertiesLoader::setProfileValue);
    }

    public static final class Builder {

        private final String name;
        private final Map<String, String> values = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = requireText(name, "name");
        }

        public Builder value(String key, String value) {
            String normalizedKey = requireText(key, "key");
            String normalizedValue = requireText(value, "value");
            String previous = values.putIfAbsent(normalizedKey, normalizedValue);
            if (previous != null && !previous.equals(normalizedValue)) {
                throw new IllegalArgumentException("Conflicting profile value for " + normalizedKey);
            }
            return this;
        }

        public Builder positiveInt(String key, int value) {
            if (value < 1) {
                throw new IllegalArgumentException(key + " must be positive");
            }
            return value(key, Integer.toString(value));
        }

        public Builder nonNegativeInt(String key, int value) {
            if (value < 0) {
                throw new IllegalArgumentException(key + " must not be negative");
            }
            return value(key, Integer.toString(value));
        }

        public Builder positiveLong(String key, long value) {
            if (value < 1) {
                throw new IllegalArgumentException(key + " must be positive");
            }
            return value(key, Long.toString(value));
        }

        public Builder oneOf(String key, String value, String... allowedValues) {
            String normalized = requireText(value, "value").toLowerCase(Locale.ROOT);
            Set<String> allowed = Set.of(allowedValues);
            if (!allowed.contains(normalized)) {
                throw new IllegalArgumentException(key + " must be one of " + allowed);
            }
            return value(key, normalized);
        }

        public Builder routeBudget(String budget, int maxConcurrent, int queueTimeoutMillis) {
            String normalized = requireText(budget, "budget");
            positiveInt("reactor.rust.route-budget." + normalized
                    + ".route-admission.max-concurrent", maxConcurrent);
            nonNegativeInt("reactor.rust.route-budget." + normalized
                    + ".route-admission.queue-timeout-ms", queueTimeoutMillis);
            return this;
        }

        public RuntimeProfilePlan build() {
            if (values.isEmpty()) {
                throw new IllegalStateException("Runtime profile plan must contain at least one value");
            }
            return new RuntimeProfilePlan(this);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}

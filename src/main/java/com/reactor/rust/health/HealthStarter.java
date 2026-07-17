package com.reactor.rust.health;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Declarative builder for liveness and dependency-aware readiness routes. */
public final class HealthStarter {

    private HealthStarter() {}

    public static Builder application(String applicationName) {
        return new Builder(applicationName);
    }

    public static final class Builder {

        private final String applicationName;
        private final List<HealthEndpoint.Dependency> dependencies = new ArrayList<>();

        private Builder(String applicationName) {
            this.applicationName = requireText(applicationName, "applicationName");
        }

        public Builder required(String name, long timeoutMillis, DependencyProbe probe) {
            return dependency(name, true, timeoutMillis, probe);
        }

        public Builder optional(String name, long timeoutMillis, DependencyProbe probe) {
            return dependency(name, false, timeoutMillis, probe);
        }

        public Builder dependency(
                String name,
                boolean required,
                long timeoutMillis,
                DependencyProbe probe) {
            if (timeoutMillis < 1) {
                throw new IllegalArgumentException("timeoutMillis must be positive");
            }
            String normalized = requireText(name, "name");
            String metricName = HealthEndpoint.metricName(normalized);
            if (metricName.isEmpty()) {
                throw new IllegalArgumentException("name must contain a letter or digit");
            }
            if (dependencies.stream().anyMatch(value ->
                    HealthEndpoint.metricName(value.name()).equals(metricName))) {
                throw new IllegalArgumentException("Dependency already declared: " + normalized);
            }
            dependencies.add(new HealthEndpoint.Dependency(
                    normalized,
                    required,
                    timeoutMillis,
                    Objects.requireNonNull(probe, "probe")));
            return this;
        }

        public HealthEndpoint build() {
            return new HealthEndpoint(applicationName, dependencies);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}

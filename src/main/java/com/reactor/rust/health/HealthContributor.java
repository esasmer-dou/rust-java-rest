package com.reactor.rust.health;

/** Declarative dependency readiness contract discovered from generated application beans. */
public interface HealthContributor extends DependencyProbe {
    String name();

    default boolean required() {
        return true;
    }

    default long timeoutMillis() {
        return 500;
    }
}

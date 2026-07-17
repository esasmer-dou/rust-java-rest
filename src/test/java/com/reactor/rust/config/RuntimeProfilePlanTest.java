package com.reactor.rust.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeProfilePlanTest {

    private static final String KEY = "test.runtime.plan.workers";

    @AfterEach
    void clearOverride() {
        System.clearProperty(KEY);
    }

    @Test
    void validatesAndAppliesDefaultsWithoutReplacingExplicitOverride() {
        PropertiesLoader.load();
        RuntimeProfilePlan plan = RuntimeProfilePlan.named("test")
                .positiveInt(KEY, 2)
                .build();
        plan.apply();
        assertEquals("2", PropertiesLoader.get(KEY));

        System.setProperty(KEY, "7");
        plan.apply();
        assertEquals("7", PropertiesLoader.get(KEY));
    }

    @Test
    void rejectsInvalidCapacityAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> RuntimeProfilePlan.named("test")
                .positiveInt(KEY, 0));
    }
}

package com.reactor.rust.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeProfilesTest {

    @AfterEach
    void resetProperties() throws Exception {
        properties().clear();
        explicitKeys().clear();
        System.clearProperty("reactor.runtime.profile");
        System.clearProperty("reactor.websocket.enabled");
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

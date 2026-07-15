package com.reactor.rust.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesLoaderValidationTest {

    @Test
    void rejectsMalformedNumericOverridesInsteadOfSilentlyUsingDefaults() {
        assertInvalid("test.invalid.int", "many", () -> PropertiesLoader.getInt("test.invalid.int", 8));
        assertInvalid("test.invalid.long", "large", () -> PropertiesLoader.getLong("test.invalid.long", 8L));
        assertInvalid("test.invalid.double", "fast", () -> PropertiesLoader.getDouble("test.invalid.double", 1.0));
    }

    @Test
    void parsesExplicitBooleanFormsAndRejectsTypos() {
        withProperty("test.boolean.true", "yes", () -> assertTrue(
                PropertiesLoader.getBoolean("test.boolean.true", false)
        ));
        withProperty("test.boolean.false", "off", () -> assertFalse(
                PropertiesLoader.getBoolean("test.boolean.false", true)
        ));
        assertInvalid(
                "test.boolean.invalid",
                "treu",
                () -> PropertiesLoader.getBoolean("test.boolean.invalid", false)
        );
    }

    @Test
    void trimsValidNumericOverrides() {
        withProperty("test.valid.int", " 16 ", () -> assertEquals(
                16,
                PropertiesLoader.getInt("test.valid.int", 1)
        ));
    }

    @Test
    void getAllReturnsMaterializedValuesInsteadOfAHiddenDefaultsChain() {
        PropertiesLoader.load();

        Properties values = PropertiesLoader.getAll();

        assertTrue(values.containsKey("server.port"));
        assertEquals(PropertiesLoader.get("server.port"), values.getProperty("server.port"));
    }

    @Test
    void requiredAccessorsRejectMissingValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PropertiesLoader.requireInt("test.required.missing"));
    }

    private static void assertInvalid(String key, String value, Runnable action) {
        withProperty(key, value, () -> {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action::run);
            assertTrue(error.getMessage().contains(key));
            assertTrue(error.getMessage().contains(value));
        });
    }

    private static void withProperty(String key, String value, Runnable action) {
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, value);
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }
}

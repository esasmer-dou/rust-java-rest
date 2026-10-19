package com.reactor.rust.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationChecksTest {
    @Test void checksOnlyOwnedKeysWithoutPrintingValues() {
        assertEquals(List.of("Unknown property: app.timeot"), ConfigurationChecks.unknownKeys(
                List.of("app.timeout", "app.timeot", "other.timeout"), Set.of("app.timeout"), "app."));
        var issues = ConfigurationChecks.lessThan(Map.of("queue", "secret", "rpc", "3")::get, "queue", "rpc");
        assertFalse(issues.toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> ConfigurationChecks.requireValid(issues));
    }
    @Test void respectsOptionalAndOrderedBudgets() {
        assertTrue(ConfigurationChecks.lessThan(Map.of("rpc", "500")::get, "queue", "rpc").isEmpty());
        assertTrue(ConfigurationChecks.lessThan(Map.of("queue", "100", "rpc", "500")::get, "queue", "rpc").isEmpty());
        assertFalse(ConfigurationChecks.lessThan(Map.of("queue", "500", "rpc", "500")::get, "queue", "rpc").isEmpty());
    }
}

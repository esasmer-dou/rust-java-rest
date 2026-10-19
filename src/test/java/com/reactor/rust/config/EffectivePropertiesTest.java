package com.reactor.rust.config;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EffectivePropertiesTest {
    @Test void explicitOverrideWinsAndSecretsCannotBeUnmasked() {
        String key = "test.report.workers";
        String secret = "test.report.password";
        try {
            PropertiesLoader.setProfileValue(key, "2");
            assertEquals("runtime-profile", PropertiesLoader.effective(Set.of(key), Set.of(key)).getFirst().source());
            System.setProperty(key, "4");
            System.setProperty(secret, "sensitive");
            var rows = PropertiesLoader.effective(Set.of(key, secret), Set.of(key, secret));
            assertEquals("<redacted>", rows.getFirst().value());
            assertEquals("4", rows.getLast().value());
            assertEquals("system-property", rows.getLast().source());
            assertEquals("<redacted>", PropertiesLoader.effective(Set.of(key), Set.of()).getFirst().value());
        } finally { System.clearProperty(key); System.clearProperty(secret); }
    }
}

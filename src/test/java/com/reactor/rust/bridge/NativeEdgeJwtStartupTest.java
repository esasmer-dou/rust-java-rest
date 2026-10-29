package com.reactor.rust.bridge;

import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeEdgeJwtStartupTest {
    @Test void aMissingSecuritySpiFailsBeforeNativeActivation() {
        var previous = new HashMap<String, String>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try {
            for (var entry : java.util.Map.of("reactor.security.enabled", "true",
                    "reactor.security.jwt.hmac-secret", "test-only-012345678901234567890123456789",
                    "reactor.security.jwt.subject-claim", "sub",
                    "reactor.rust.errors.format", "problem-details").entrySet()) {
                previous.put(entry.getKey(), System.getProperty(entry.getKey()));
                System.setProperty(entry.getKey(), entry.getValue());
            }
            Thread.currentThread().setContextClassLoader(new ClassLoader(loader) {
                @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
                    return name.equals("META-INF/services/" + RequestGuardFactory.class.getName())
                            ? Collections.emptyEnumeration() : super.getResources(name);
                }
            });
            var failure = assertThrows(IllegalStateException.class,
                    () -> NativeEdgeJwt.registerFromProperties("GET", "/account", "reactor.security.jwt"));
            assertTrue(failure.getMessage().contains("SecurityRequestGuardFactory"));
        } finally {
            Thread.currentThread().setContextClassLoader(loader);
            previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        }
    }
}

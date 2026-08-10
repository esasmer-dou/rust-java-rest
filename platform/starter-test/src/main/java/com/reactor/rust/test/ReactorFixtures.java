package com.reactor.rust.test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Classpath fixture helpers that keep test setup explicit and dependency-free. */
public final class ReactorFixtures {
    private ReactorFixtures() {}

    public static String utf8(String resource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = ReactorFixtures.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(stripSlash(resource))) {
            if (input == null) throw new IllegalArgumentException("Fixture not found: " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read fixture: " + resource, error);
        }
    }

    private static String stripSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }
}

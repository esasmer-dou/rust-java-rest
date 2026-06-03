package com.reactor.rust.startup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void filtersComponentIndexByPackage() throws Exception {
        writeIndex(StartupIndex.COMPONENTS_RESOURCE, List.of(
                "# comment",
                "com.acme.web.OrderHandler",
                "com.acme.service.OrderService",
                "com.other.Ignored"
        ));

        StartupIndex.IndexResult result = withTempClassLoader(
                () -> StartupIndex.componentClasses("com.acme")
        );

        assertTrue(result.present());
        assertEquals(List.of("com.acme.web.OrderHandler", "com.acme.service.OrderService"), result.entries());
    }

    @Test
    void parsesRouteIndexAsMethodAndPathOnly() throws Exception {
        writeIndex(StartupIndex.ROUTES_RESOURCE, List.of(
                "get /api/orders com.acme.OrderHandler#list",
                "POST /api/orders",
                ""
        ));

        StartupIndex.IndexResult result = withTempClassLoader(StartupIndex::routeKeys);

        assertTrue(result.present());
        assertEquals(List.of("GET /api/orders", "POST /api/orders"), result.entries());
    }

    @Test
    void returnsMissingWhenIndexResourceDoesNotExist() throws Exception {
        StartupIndex.IndexResult result = withTempClassLoader(
                () -> StartupIndex.componentClasses("com.acme")
        );

        assertFalse(result.present());
        assertTrue(result.entries().isEmpty());
    }

    private void writeIndex(String resourceName, List<String> lines) throws Exception {
        Path file = tempDir.resolve(resourceName);
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private StartupIndex.IndexResult withTempClassLoader(IndexCall call) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader classLoader = new URLClassLoader(
                new java.net.URL[]{tempDir.toUri().toURL()},
                null
        )) {
            Thread.currentThread().setContextClassLoader(classLoader);
            return call.get();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @FunctionalInterface
    private interface IndexCall {
        StartupIndex.IndexResult get() throws Exception;
    }
}

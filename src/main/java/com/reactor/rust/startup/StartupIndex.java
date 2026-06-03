package com.reactor.rust.startup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Optional build-time startup indexes.
 *
 * <p>Applications can ship {@code META-INF/reactor/components.idx} to avoid classpath scanning for
 * DI components. Each non-comment line contains a fully-qualified class name.</p>
 *
 * <p>{@code META-INF/reactor/routes.idx} is a lightweight production gate input. Each line starts
 * with {@code METHOD /path}; extra columns are ignored.</p>
 */
public final class StartupIndex {

    public static final String COMPONENTS_RESOURCE = "META-INF/reactor/components.idx";
    public static final String ROUTES_RESOURCE = "META-INF/reactor/routes.idx";

    private StartupIndex() {
    }

    public static IndexResult componentClasses(String packageName) {
        List<String> lines = loadLines(COMPONENTS_RESOURCE);
        if (lines == null) {
            return IndexResult.missing();
        }
        String prefix = packageName == null || packageName.isBlank() ? "" : packageName + ".";
        List<String> classNames = new ArrayList<>();
        for (String line : lines) {
            if (prefix.isEmpty() || line.equals(packageName) || line.startsWith(prefix)) {
                classNames.add(line);
            }
        }
        return IndexResult.present(classNames);
    }

    public static IndexResult routeKeys() {
        List<String> lines = loadLines(ROUTES_RESOURCE);
        if (lines == null) {
            return IndexResult.missing();
        }
        List<String> routes = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split("\\s+", 3);
            if (parts.length >= 2) {
                routes.add(parts[0].toUpperCase(java.util.Locale.ROOT) + " " + parts[1]);
            }
        }
        return IndexResult.present(routes);
    }

    private static List<String> loadLines(String resourceName) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = StartupIndex.class.getClassLoader();
        }
        try {
            Enumeration<java.net.URL> resources = classLoader.getResources(resourceName);
            if (!resources.hasMoreElements()) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            while (resources.hasMoreElements()) {
                java.net.URL url = resources.nextElement();
                try (InputStream in = url.openStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                            lines.add(trimmed);
                        }
                    }
                }
            }
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load startup index " + resourceName, e);
        }
    }

    public record IndexResult(boolean present, List<String> entries) {
        static IndexResult missing() {
            return new IndexResult(false, Collections.emptyList());
        }

        static IndexResult present(List<String> entries) {
            return new IndexResult(true, Collections.unmodifiableList(new ArrayList<>(entries)));
        }
    }
}

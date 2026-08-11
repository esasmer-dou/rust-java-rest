package com.reactor.rust.startup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Build-image utility that loads application classes without initializing them.
 *
 * <p>OpenJ9 records the resulting ROM classes in its shared class cache. The utility is never
 * used by request handling and deliberately does not execute application startup code.</p>
 */
public final class OpenJ9SharedClassCachePreloader {

    private OpenJ9SharedClassCachePreloader() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("artifact path is required");
        }
        Path artifact = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalArgumentException("artifact does not exist: " + artifact);
        }
        List<String> prefixes = args.length > 1 ? prefixes(args[1]) : List.of();
        Result result = preload(artifact, prefixes, OpenJ9SharedClassCachePreloader.class.getClassLoader());
        if (result.loaded() == 0) {
            throw new IllegalStateException("shared-class preloader did not load any classes from " + artifact);
        }
        System.out.println("[OpenJ9SCC] loaded=" + result.loaded() + " skipped=" + result.skipped());
    }

    static Result preload(Path artifact, List<String> prefixes, ClassLoader classLoader) throws IOException {
        int loaded = 0;
        int skipped = 0;
        try (JarFile jar = new JarFile(artifact.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String className = className(entries.nextElement());
                if (className == null || !matchesPrefix(className, prefixes)) {
                    continue;
                }
                try {
                    Class.forName(className, false, classLoader);
                    loaded++;
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // Optional integrations can be absent from a deliberately minimal runtime image.
                    skipped++;
                }
            }
        }
        return new Result(loaded, skipped);
    }

    static String className(JarEntry entry) {
        if (entry.isDirectory()) {
            return null;
        }
        String name = entry.getName();
        if (!name.endsWith(".class")
                || name.startsWith("META-INF/versions/")
                || name.endsWith("module-info.class")
                || name.endsWith("package-info.class")) {
            return null;
        }
        return name.substring(0, name.length() - ".class".length()).replace('/', '.');
    }

    static List<String> prefixes(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        ArrayList<String> result = new ArrayList<>();
        for (String token : value.split(",")) {
            String prefix = token.trim();
            if (!prefix.isEmpty() && !result.contains(prefix)) {
                result.add(prefix);
            }
        }
        return List.copyOf(result);
    }

    private static boolean matchesPrefix(String className, List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return true;
        }
        for (String prefix : prefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    record Result(int loaded, int skipped) {
    }
}

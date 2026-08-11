package com.reactor.rust.startup;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.jar.JarEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenJ9SharedClassCachePreloaderTest {

    @Test
    void convertsOnlyLoadableJarClassEntries() {
        assertEquals(
                "com.reactor.Sample",
                OpenJ9SharedClassCachePreloader.className(new JarEntry("com/reactor/Sample.class")));
        assertNull(OpenJ9SharedClassCachePreloader.className(new JarEntry("module-info.class")));
        assertNull(OpenJ9SharedClassCachePreloader.className(
                new JarEntry("META-INF/versions/21/com/reactor/Sample.class")));
        assertNull(OpenJ9SharedClassCachePreloader.className(new JarEntry("com/reactor/package-info.class")));
    }

    @Test
    void normalizesAndDeduplicatesPackagePrefixes() {
        assertEquals(
                List.of("com.reactor.", "com.example."),
                OpenJ9SharedClassCachePreloader.prefixes(" com.reactor.,com.example.,com.reactor. "));
    }
}

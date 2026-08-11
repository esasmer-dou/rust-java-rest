package com.reactor.rust.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeImageMojoTest {
    @Test
    void tokenizesJvmArgumentsWithoutCollapsingExecForm() throws Exception {
        assertEquals(
                List.of("-Xss256k", "-Dmessage=hello world", "-Dpath=C:\\runtime"),
                RuntimeImageMojo.tokenize("-Xss256k \"-Dmessage=hello world\" -Dpath=C:\\\\runtime"));
    }

    @Test
    void rejectsUnclosedQuote() {
        assertThrows(MojoExecutionException.class, () -> RuntimeImageMojo.tokenize("-Dvalue=\"broken"));
    }

    @Test
    void emitsValidJsonArray() {
        assertEquals(
                "[\"java\", \"-Dvalue=hello world\", \"app.Main\"]",
                RuntimeImageMojo.jsonArray(List.of("java", "-Dvalue=hello world", "app.Main")));
    }

    @Test
    void validatesOpenJ9SharedClassCacheOptions() throws Exception {
        assertEquals("reactor_rom", RuntimeImageMojo.validateCacheName(" reactor_rom "));
        assertEquals("8m", RuntimeImageMojo.validateMemorySize("8M"));
        assertEquals(
                "com.reactor.,com.example.",
                RuntimeImageMojo.validateClassPrefixes("com.reactor.,com.example."));
    }

    @Test
    void rejectsUnsafeOpenJ9SharedClassCacheOptions() {
        assertThrows(MojoExecutionException.class, () -> RuntimeImageMojo.validateCacheName("reactor;rm"));
        assertThrows(MojoExecutionException.class, () -> RuntimeImageMojo.validateMemorySize("8mb"));
        assertThrows(
                MojoExecutionException.class,
                () -> RuntimeImageMojo.validateClassPrefixes("com.reactor. && echo broken"));
    }

    @Test
    void validatesAllocatorImageOptions() throws Exception {
        assertEquals(1, RuntimeImageMojo.validateMallocArenaMax(1));
        assertEquals(2, RuntimeImageMojo.validateMallocArenaMax(2));
        assertEquals(131072, RuntimeImageMojo.validateMallocTrimThreshold(131072));
    }

    @Test
    void rejectsUnsafeAllocatorImageOptions() {
        assertThrows(MojoExecutionException.class, () -> RuntimeImageMojo.validateMallocArenaMax(0));
        assertThrows(MojoExecutionException.class, () -> RuntimeImageMojo.validateMallocArenaMax(65));
        assertThrows(MojoExecutionException.class, () -> RuntimeImageMojo.validateMallocTrimThreshold(-1));
    }

    @Test
    void rendersRomOnlySharedCacheAndBoundedAllocatorRecipe() throws Exception {
        RuntimeImageMojo mojo = new RuntimeImageMojo();
        MavenProject project = new MavenProject();
        Build build = new Build();
        build.setFinalName("example-service");
        project.setBuild(build);
        setField(mojo, "project", project);
        setField(mojo, "mainClass", "example.Main");
        setField(mojo, "jvmArgs", "-Xss256k");
        setField(mojo, "mallocArenaMax", 2);
        setField(mojo, "mallocTrimThreshold", 131072);
        setField(mojo, "openJ9SharedClassCacheEnabled", true);
        setField(mojo, "openJ9SharedClassCacheName", "example_rom");
        setField(mojo, "openJ9SharedClassCacheSize", "8m");
        setField(mojo, "openJ9SharedClassCachePrefixes", "example.");

        String dockerfile = mojo.dockerfile("java.base,java.management");

        assertTrue(dockerfile.contains(
                "RUN apt-get update \\\n"
                        + "    && apt-get install --yes --no-install-recommends binutils \\\n"
                        + "    && rm -rf /var/lib/apt/lists/*"));
        assertTrue(dockerfile.contains("MALLOC_ARENA_MAX=2"));
        assertTrue(dockerfile.contains("MALLOC_TRIM_THRESHOLD_=131072"));
        assertTrue(dockerfile.contains(
                "ENV HOME=/app \\\n"
                        + "    PATH=/opt/java/bin:$PATH \\\n"
                        + "    LANG=C.UTF-8 \\\n"
                        + "    LC_ALL=C.UTF-8 \\\n"
                        + "    MALLOC_ARENA_MAX=2 \\\n"
                        + "    MALLOC_TRIM_THRESHOLD_=131072"));
        assertTrue(dockerfile.contains(
                "RUN mkdir -p /app/.reactor/native /app/work \\\n"
                        + "    && chown -R 10001:0 /app/.reactor /app/work"));
        assertTrue(dockerfile.contains("WORKDIR /app/work"));
        assertTrue(dockerfile.contains("cacheDir=/opt/reactor-scc,noaot"));
        assertTrue(dockerfile.contains("cacheDir=/opt/reactor-scc,readonly,fatal"));
        assertTrue(dockerfile.contains(
                "RUN cp /opt/java/openjdk/lib/default/libj9shr*.so /opt/reactor-jre/lib/default/"));
        assertTrue(dockerfile.contains("OpenJ9SharedClassCachePreloader /app/app.jar example."));
        assertTrue(dockerfile.contains("\"-Duser.home=/app\""));
        assertFalse(dockerfile.contains("IdleTuningGcOnIdle"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

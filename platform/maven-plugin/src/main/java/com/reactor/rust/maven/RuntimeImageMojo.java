package com.reactor.rust.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Generates a deterministic minimal-runtime container recipe; it never starts Docker. */
@Mojo(name = "runtime-image", threadSafe = true)
public final class RuntimeImageMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/reactor-runtime", required = true)
    private Path outputDirectory;

    @Parameter(property = "reactor.runtime.modules", defaultValue = "java.base,java.management,java.naming,java.net.http,jdk.crypto.ec,jdk.unsupported")
    private String modules;

    @Parameter(property = "reactor.runtime.mainClass", required = true)
    private String mainClass;

    @Parameter(property = "reactor.runtime.jvmArgs", defaultValue = "-Xss256k")
    private String jvmArgs;

    @Parameter(property = "reactor.runtime.image.malloc.arena-max", defaultValue = "2")
    private int mallocArenaMax;

    @Parameter(property = "reactor.runtime.image.malloc.trim-threshold", defaultValue = "131072")
    private int mallocTrimThreshold;

    @Parameter(property = "reactor.runtime.openj9.scc.enabled", defaultValue = "false")
    private boolean openJ9SharedClassCacheEnabled;

    @Parameter(property = "reactor.runtime.openj9.scc.name", defaultValue = "reactor_rom")
    private String openJ9SharedClassCacheName;

    @Parameter(property = "reactor.runtime.openj9.scc.size", defaultValue = "8m")
    private String openJ9SharedClassCacheSize;

    @Parameter(property = "reactor.runtime.openj9.scc.class-prefixes", defaultValue = "")
    private String openJ9SharedClassCachePrefixes;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            Files.createDirectories(outputDirectory);
            String normalizedModules = normalizeModules(modules);
            Files.writeString(outputDirectory.resolve("jlink-modules.txt"), normalizedModules + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.writeString(outputDirectory.resolve("Dockerfile"), dockerfile(normalizedModules), StandardCharsets.UTF_8);
            getLog().info("Generated minimal runtime recipe in " + outputDirectory);
        } catch (IOException error) {
            throw new MojoExecutionException("Cannot generate runtime image recipe", error);
        }
    }

    private static String normalizeModules(String value) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String token : value.split(",")) {
            String module = token.trim();
            if (!module.isEmpty()) ordered.add(module);
        }
        return String.join(",", ordered);
    }

    String dockerfile(String normalizedModules) throws MojoExecutionException {
        String jar = project.getBuild().getFinalName() + ".jar";
        int arenaMax = validateMallocArenaMax(mallocArenaMax);
        int trimThreshold = validateMallocTrimThreshold(mallocTrimThreshold);
        List<String> entrypoint = new ArrayList<>();
        entrypoint.add("/opt/java/bin/java");
        entrypoint.add("-Duser.home=/app");
        entrypoint.addAll(tokenize(jvmArgs));
        String sccBuild = "";
        String sccCopy = "";
        if (openJ9SharedClassCacheEnabled) {
            String cacheName = validateCacheName(openJ9SharedClassCacheName);
            String cacheSize = validateMemorySize(openJ9SharedClassCacheSize);
            String prefixes = validateClassPrefixes(openJ9SharedClassCachePrefixes);
            sccBuild = "RUN cp /opt/java/openjdk/lib/default/libj9shr*.so /opt/reactor-jre/lib/default/\n"
                    + "COPY target/" + jar + " /app/app.jar\n"
                    + "RUN mkdir -p /opt/reactor-scc && java "
                    + "-Xshareclasses:name=" + cacheName + ",cacheDir=/opt/reactor-scc,noaot "
                    + "-Xscmx" + cacheSize + " -cp /app/app.jar "
                    + "com.reactor.rust.startup.OpenJ9SharedClassCachePreloader /app/app.jar"
                    + (prefixes.isEmpty() ? "" : " " + prefixes) + "\n";
            sccCopy = "COPY --from=runtime /opt/reactor-scc /opt/reactor-scc\n";
            entrypoint.add("-Xshareclasses:name=" + cacheName
                    + ",cacheDir=/opt/reactor-scc,readonly,fatal");
        }
        entrypoint.add("-cp");
        entrypoint.add("/app/app.jar");
        entrypoint.add(mainClass);
        return """
                FROM ibm-semeru-runtimes:open-21-jdk AS runtime
                RUN apt-get update \\
                    && apt-get install --yes --no-install-recommends binutils \\
                    && rm -rf /var/lib/apt/lists/*
                RUN jlink --add-modules %s --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output /opt/reactor-jre
                %sFROM ubuntu:24.04
                COPY --from=runtime /opt/reactor-jre /opt/java
                %sCOPY target/%s /app/app.jar
                RUN mkdir -p /app/.reactor/native /app/work \\
                    && chown -R 10001:0 /app/.reactor /app/work
                ENV HOME=/app \\
                    PATH=/opt/java/bin:$PATH \\
                    LANG=C.UTF-8 \\
                    LC_ALL=C.UTF-8 \\
                    MALLOC_ARENA_MAX=%d \\
                    MALLOC_TRIM_THRESHOLD_=%d
                WORKDIR /app/work
                USER 10001
                ENTRYPOINT %s
                """.formatted(
                        normalizedModules,
                        sccBuild,
                        sccCopy,
                        jar,
                        arenaMax,
                        trimThreshold,
                        jsonArray(entrypoint));
    }

    static int validateMallocArenaMax(int value) throws MojoExecutionException {
        if (value < 1 || value > 64) {
            throw new MojoExecutionException(
                    "reactor.runtime.image.malloc.arena-max must be between 1 and 64");
        }
        return value;
    }

    static int validateMallocTrimThreshold(int value) throws MojoExecutionException {
        if (value < 0) {
            throw new MojoExecutionException(
                    "reactor.runtime.image.malloc.trim-threshold must be zero or greater");
        }
        return value;
    }

    static String validateCacheName(String value) throws MojoExecutionException {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z0-9_.-]+")) {
            throw new MojoExecutionException("reactor.runtime.openj9.scc.name contains unsafe characters");
        }
        return normalized;
    }

    static String validateMemorySize(String value) throws MojoExecutionException {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[1-9][0-9]*[kKmMgG]")) {
            throw new MojoExecutionException("reactor.runtime.openj9.scc.size must look like 8m or 64m");
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    static String validateClassPrefixes(String value) throws MojoExecutionException {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z0-9_.$,-]*")) {
            throw new MojoExecutionException(
                    "reactor.runtime.openj9.scc.class-prefixes must be comma-separated Java package prefixes");
        }
        return normalized;
    }

    static List<String> tokenize(String value) throws MojoExecutionException {
        List<String> tokens = new ArrayList<>();
        if (value == null || value.isBlank()) return tokens;

        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (escaped) current.append('\\');
        if (quoted) {
            throw new MojoExecutionException("reactor.runtime.jvmArgs contains an unclosed quote");
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return List.copyOf(tokens);
    }

    static String jsonArray(List<String> values) {
        return values.stream()
                .map(RuntimeImageMojo::jsonString)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }
}

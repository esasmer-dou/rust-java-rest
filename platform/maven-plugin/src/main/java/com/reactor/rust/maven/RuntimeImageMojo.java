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

    @Parameter(property = "reactor.runtime.jvmArgs", defaultValue = "-XX:+IdleTuningGcOnIdle -Xss256k")
    private String jvmArgs;

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

    private String dockerfile(String normalizedModules) throws MojoExecutionException {
        String jar = project.getBuild().getFinalName() + ".jar";
        List<String> entrypoint = new ArrayList<>();
        entrypoint.add("/opt/java/bin/java");
        entrypoint.addAll(tokenize(jvmArgs));
        entrypoint.add("-cp");
        entrypoint.add("/app/app.jar");
        entrypoint.add(mainClass);
        return """
                FROM ibm-semeru-runtimes:open-21-jdk AS runtime
                RUN jlink --add-modules %s --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output /opt/reactor-jre
                FROM ubuntu:24.04
                COPY --from=runtime /opt/reactor-jre /opt/java
                COPY target/%s /app/app.jar
                ENV PATH=/opt/java/bin:$PATH
                USER 10001
                ENTRYPOINT %s
                """.formatted(normalizedModules, jar, jsonArray(entrypoint));
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

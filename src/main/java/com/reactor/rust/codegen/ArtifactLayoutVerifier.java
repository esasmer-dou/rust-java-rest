package com.reactor.rust.codegen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Maven verify-phase guard that keeps build-time code out of the production artifact. */
public final class ArtifactLayoutVerifier {

    private static final String PROCESSOR_SERVICE = "META-INF/services/javax.annotation.processing.Processor";

    private ArtifactLayoutVerifier() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected: <runtime-jar> <codegen-jar> <class-prefix>");
        }
        Path runtime = Path.of(args[0]);
        Path codegen = Path.of(args[1]);
        String prefix = args[2];
        if (contains(runtime, prefix)) {
            throw new IllegalStateException("Runtime artifact contains build-time classes: " + prefix);
        }
        if (!contains(codegen, prefix)) {
            throw new IllegalStateException("Codegen artifact is missing build-time classes: " + prefix);
        }
        verifyProcessorService(runtime, codegen);
    }

    private static void verifyProcessorService(Path runtime, Path codegen) throws IOException {
        if (containsExact(runtime, PROCESSOR_SERVICE)) {
            throw new IllegalStateException("Runtime artifact contains annotation processor metadata");
        }
        if (!containsExact(codegen, PROCESSOR_SERVICE)) {
            throw new IllegalStateException("Codegen artifact is missing annotation processor metadata");
        }
    }

    private static boolean contains(Path jar, String prefix) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.stream().anyMatch(entry -> entry.getName().startsWith(prefix));
        }
    }

    private static boolean containsExact(Path jar, String name) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getJarEntry(name) != null;
        }
    }
}

package com.reactor.rust.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Verifies that compile-time application metadata exists before packaging. */
@Mojo(name = "verify-aot", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public final class VerifyAotMojo extends AbstractMojo {

    private static final List<String> REQUIRED = List.of(
            "META-INF/reactor/components.idx",
            "META-INF/services/com.reactor.rust.startup.ApplicationDescriptor");

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "reactor.aot.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "reactor.aot.requireRoutes", defaultValue = "false")
    private boolean requireRoutes;

    @Parameter(property = "reactor.aot.requireOpenApi", defaultValue = "false")
    private boolean requireOpenApi;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip || "pom".equals(project.getPackaging()) || !hasRestRuntime()) return;
        Path classes = outputDirectory.toPath();
        for (String resource : REQUIRED) {
            Path file = classes.resolve(resource);
            if (!Files.isRegularFile(file)) {
                throw new MojoExecutionException(
                        "Missing generated AOT metadata " + resource
                                + ". Add the Rust-Java codegen annotation processor and rebuild cleanly.");
            }
        }
        if (requireRoutes) require(classes, "META-INF/reactor/routes.idx");
        if (requireOpenApi) require(classes, "META-INF/reactor/openapi.json");
        getLog().info("Rust-Java AOT metadata verified in " + classes);
    }

    private boolean hasRestRuntime() {
        for (Artifact artifact : project.getArtifacts()) {
            if ("com.reactor".equals(artifact.getGroupId())
                    && "rust-java-rest".equals(artifact.getArtifactId())
                    && artifact.getClassifier() == null) return true;
        }
        return false;
    }

    private static void require(Path classes, String resource) throws MojoExecutionException {
        if (!Files.isRegularFile(classes.resolve(resource))) {
            throw new MojoExecutionException("Missing generated AOT metadata " + resource);
        }
    }
}

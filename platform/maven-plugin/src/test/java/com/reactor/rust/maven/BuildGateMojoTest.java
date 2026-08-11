package com.reactor.rust.maven;

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuildGateMojoTest {

    @TempDir
    Path tempDir;

    @Test
    void verifyAotRequiresGeneratedMetadata() throws Exception {
        VerifyAotMojo mojo = new VerifyAotMojo();
        field(mojo, "outputDirectory", tempDir.toFile());
        field(mojo, "project", jarProject());

        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    @Test
    void verifyAotAcceptsCompleteStrictArtifact() throws Exception {
        write("META-INF/reactor/components.idx");
        write("META-INF/reactor/routes.idx");
        write("META-INF/reactor/openapi.json");
        write("META-INF/services/com.reactor.rust.startup.ApplicationDescriptor");
        VerifyAotMojo mojo = new VerifyAotMojo();
        field(mojo, "outputDirectory", tempDir.toFile());
        field(mojo, "project", jarProject());
        field(mojo, "requireRoutes", true);
        field(mojo, "requireOpenApi", true);

        assertDoesNotThrow(mojo::execute);
    }

    @Test
    void doctorRejectsAotBuildWithoutCodegenProcessorPath() throws Exception {
        ReactorDoctorMojo mojo = doctor(false);

        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    @Test
    void doctorAcceptsInheritedCodegenProcessorPath() throws Exception {
        ReactorDoctorMojo mojo = doctor(true);

        assertDoesNotThrow(mojo::execute);
    }

    private ReactorDoctorMojo doctor(boolean withProcessor) throws Exception {
        Model model = new Model();
        Build build = new Build();
        model.setBuild(build);
        MavenProject project = new MavenProject(model);
        project.setArtifacts(Set.of(new DefaultArtifact(
                "com.reactor",
                "rust-java-rest",
                "4.3.0",
                "compile",
                "jar",
                null,
                new DefaultArtifactHandler("jar"))));
        if (withProcessor) build.addPlugin(compilerPlugin());

        ReactorDoctorMojo mojo = new ReactorDoctorMojo();
        field(mojo, "project", project);
        field(mojo, "startupMode", "aot");
        return mojo;
    }

    private static MavenProject jarProject() {
        Model model = new Model();
        model.setPackaging("jar");
        MavenProject project = new MavenProject(model);
        project.setArtifacts(Set.of(new DefaultArtifact(
                "com.reactor",
                "rust-java-rest",
                "4.3.0",
                "compile",
                "jar",
                null,
                new DefaultArtifactHandler("jar"))));
        return project;
    }

    private static Plugin compilerPlugin() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-compiler-plugin");
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom paths = child(configuration, "annotationProcessorPaths", null);
        Xpp3Dom path = child(paths, "path", null);
        child(path, "groupId", "com.reactor");
        child(path, "artifactId", "rust-java-rest");
        child(path, "classifier", "codegen");
        plugin.setConfiguration(configuration);
        return plugin;
    }

    private static Xpp3Dom child(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
        return child;
    }

    private void write(String relative) throws Exception {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "generated");
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

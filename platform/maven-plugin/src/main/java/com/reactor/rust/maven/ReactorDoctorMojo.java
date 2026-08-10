package com.reactor.rust.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.model.Plugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.Set;

/** Fails the build early when the selected runtime and AOT dependencies are inconsistent. */
@Mojo(name = "doctor", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public final class ReactorDoctorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "reactor.startup.mode", defaultValue = "aot")
    private String startupMode;

    @Override
    public void execute() throws MojoExecutionException {
        if ("pom".equals(project.getPackaging())) return;
        int feature = Runtime.version().feature();
        if (feature < 21) {
            throw new MojoExecutionException("Rust-Java requires Maven to run with Java 21 or newer; current=" + feature);
        }

        Set<Artifact> artifacts = project.getArtifacts();
        boolean core = contains(artifacts, "com.reactor", "rust-java-rest", null);
        boolean dubbo = contains(artifacts, "com.reactor", "java-rust-dubbo", null);
        boolean cache = contains(artifacts, "com.reactor", "java-rust-cache", null);
        if (!core && !dubbo && !cache) {
            throw new MojoExecutionException(
                    "No Rust-Java runtime found. Add the REST, Dubbo, or cache starter required by this module.");
        }
        if (core && "aot".equalsIgnoreCase(startupMode)
                && !contains(artifacts, "com.reactor", "rust-java-rest", "codegen")
                && !hasCodegenProcessorConfiguration()) {
            throw new MojoExecutionException(
                    "AOT mode requires com.reactor:rust-java-rest:jar:codegen on annotationProcessorPaths.");
        }
        if (contains(artifacts, "com.reactor", "rust-java-rest-compat", null)
                && "aot".equalsIgnoreCase(startupMode)) {
            getLog().warn("rust-java-rest-compat is present but startup mode is AOT; remove it from the production classpath.");
        }
        getLog().info("Rust-Java doctor passed: Java " + feature + ", startup=" + startupMode
                + ", rest=" + core + ", dubbo=" + dubbo + ", cache=" + cache);
    }

    private boolean hasCodegenProcessorConfiguration() {
        for (Plugin plugin : project.getBuildPlugins()) {
            if (!"org.apache.maven.plugins".equals(plugin.getGroupId())
                    || !"maven-compiler-plugin".equals(plugin.getArtifactId())
                    || !(plugin.getConfiguration() instanceof Xpp3Dom configuration)) continue;
            Xpp3Dom paths = configuration.getChild("annotationProcessorPaths");
            if (paths == null) continue;
            for (Xpp3Dom path : paths.getChildren("path")) {
                if ("com.reactor".equals(childValue(path, "groupId"))
                        && "rust-java-rest".equals(childValue(path, "artifactId"))
                        && "codegen".equals(childValue(path, "classifier"))) return true;
            }
        }
        return false;
    }

    private static String childValue(Xpp3Dom parent, String name) {
        Xpp3Dom child = parent.getChild(name);
        return child == null ? null : child.getValue();
    }

    private static boolean contains(Set<Artifact> artifacts, String group, String id, String classifier) {
        for (Artifact artifact : artifacts) {
            if (group.equals(artifact.getGroupId())
                    && id.equals(artifact.getArtifactId())
                    && (classifier == null || classifier.equals(artifact.getClassifier()))) {
                return true;
            }
        }
        return false;
    }
}

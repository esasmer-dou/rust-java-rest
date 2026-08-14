package com.reactor.rust.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;

/** Checks packaged native provenance without loading JNI or starting an application. */
@Mojo(name = "verify-native-abi", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public final class VerifyNativeAbiMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "reactor.native.restAbi", defaultValue = "29")
    private int expectedRestAbi;

    @Parameter(property = "reactor.native.dubboAbi", defaultValue = "7")
    private int expectedDubboAbi;

    @Parameter(property = "reactor.native.redisAbi", defaultValue = "6")
    private int expectedRedisAbi;

    @Parameter(property = "reactor.native.glowrootAbi", defaultValue = "3")
    private int expectedGlowrootAbi;

    @Override
    public void execute() throws MojoExecutionException {
        if ("pom".equals(project.getPackaging())) return;
        Set<String> nativeArtifacts = Set.of("rust-java-rest", "java-rust-cache");
        java.util.List<Artifact> runtimes = project.getArtifacts().stream()
                .filter(artifact -> "com.reactor".equals(artifact.getGroupId()))
                .filter(artifact -> nativeArtifacts.contains(artifact.getArtifactId()))
                .filter(artifact -> artifact.getClassifier() == null)
                .toList();
        if (runtimes.isEmpty()) return;
        for (Artifact runtime : runtimes) {
            verify(runtime);
        }
        getLog().info("Native ABI verified for " + runtimes.size() + " runtime artifact(s): REST="
                + expectedRestAbi + ", Dubbo=" + expectedDubboAbi + ", Redis=" + expectedRedisAbi
                + ", Glowroot=" + expectedGlowrootAbi);
    }

    private void verify(Artifact runtime) throws MojoExecutionException {
        if (runtime.getFile() == null || !Files.isRegularFile(runtime.getFile().toPath())) {
            throw new MojoExecutionException("Resolved native artifact has no readable JAR: " + runtime.getFile());
        }

        Properties provenance = new Properties();
        try (JarFile jar = new JarFile(runtime.getFile())) {
            var entry = jar.getJarEntry("native/native-provenance.properties");
            if (entry == null) {
                throw new MojoExecutionException(runtime.getArtifactId() + " JAR has no native provenance manifest");
            }
            try (InputStream input = jar.getInputStream(entry)) {
                provenance.load(input);
            }
        } catch (IOException error) {
            throw new MojoExecutionException("Cannot inspect native provenance", error);
        }

        assertAbi(provenance, "rest.abi", expectedRestAbi);
        assertAbi(provenance, "dubbo.abi", expectedDubboAbi);
        assertAbi(provenance, "redis.abi", expectedRedisAbi);
        assertAbi(provenance, "glowroot.abi", expectedGlowrootAbi);
    }

    private static void assertAbi(Properties values, String key, int expected) throws MojoExecutionException {
        String actual = values.getProperty(key);
        if (!Integer.toString(expected).equals(actual)) {
            throw new MojoExecutionException(
                    "Native ABI mismatch for " + key + ": expected=" + expected + ", packaged=" + actual);
        }
    }
}

package com.reactor.rust.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesAllSupportedProjectShapes() throws Exception {
        for (ProjectGenerator.Mode mode : ProjectGenerator.Mode.values()) {
            Path output = tempDir.resolve(mode.name().toLowerCase());
            ProjectGenerator.generate(new ProjectGenerator.Options(
                    output,
                    "com.example",
                    "sample-" + mode.name().toLowerCase(),
                    "com.example.generated",
                    mode,
                    8080));
            assertTrue(Files.isRegularFile(output.resolve("pom.xml")));
            assertTrue(Files.isRegularFile(output.resolve(
                    "src/main/java/com/example/generated/Application.java")));
            String application = Files.readString(output.resolve(
                    "src/main/java/com/example/generated/Application.java"));
            if (mode != ProjectGenerator.Mode.CACHE_WRITER) {
                assertTrue(application.contains("@ReactorApplication"));
                assertTrue(application.contains("RestApplication.run(Application.class, args)"));
            }
            assertTrue(!application.contains("context ->"));
        }
    }

    @Test
    void generatedRuntimeProjectsUseDeclarativeBindings() throws Exception {
        Path rest = generate(ProjectGenerator.Mode.REST, "declarative-rest");
        assertTrue(Files.readString(rest.resolve(
                "src/main/java/com/example/generated/HelloHandler.java"))
                .contains("@RestController(\"/api/v1\")"));

        Path cache = generate(ProjectGenerator.Mode.CACHE_READER, "declarative-cache");
        assertTrue(Files.readString(cache.resolve(
                "src/main/java/com/example/generated/CacheReads.java"))
                .contains("@GenerateProjectionReader"));
        String cachePom = Files.readString(cache.resolve("pom.xml"));
        assertTrue(cachePom.contains("<classifier>codegen</classifier>"));
        assertTrue(!cachePom.contains("<annotationProcessors>"));

        Path dubbo = generate(ProjectGenerator.Mode.DUBBO_STATIC, "declarative-dubbo");
        assertTrue(Files.readString(dubbo.resolve(
                "src/main/java/com/example/generated/DubboClients.java"))
                .contains("@EnableNativeDubboClients"));
    }

    @Test
    void refusesToOverwriteExistingProject() throws Exception {
        Path output = tempDir.resolve("existing");
        Files.createDirectories(output);
        Files.writeString(output.resolve("keep.txt"), "keep");
        assertThrows(IllegalArgumentException.class, () -> ProjectGenerator.generate(
                new ProjectGenerator.Options(output, "com.example", "sample", "com.example",
                        ProjectGenerator.Mode.REST, 8080)));
    }

    @Test
    void makesJavaKeywordsSafeInDefaultPackage() {
        ProjectGenerator.Options options = ProjectGenerator.Options.parse(new String[] {
                "--mode", "dubbo-static",
                "--artifact", "generated-dubbo-static",
                "--output", tempDir.resolve("keyword-package").toString(),
                "--group", "com.example"
        });

        assertEquals("com.example.generated.dubbo.static_", options.packageName());
    }

    @Test
    void rejectsKeywordInExplicitPackage() {
        assertThrows(IllegalArgumentException.class, () -> ProjectGenerator.Options.parse(new String[] {
                "--mode", "rest",
                "--artifact", "sample",
                "--output", tempDir.resolve("invalid-package").toString(),
                "--package", "com.example.static"
        }));
    }

    private Path generate(ProjectGenerator.Mode mode, String name) throws Exception {
        Path output = tempDir.resolve(name);
        ProjectGenerator.generate(new ProjectGenerator.Options(
                output, "com.example", name, "com.example.generated", mode, 8080));
        return output;
    }
}

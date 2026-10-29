package com.reactor.rust.maven;

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateAppCdsMojoTest {
    @TempDir
    Path temp;

    @Test
    void disabledDefaultsReturnBeforeJvmProjectFilesystemAndProcessAccess() throws Exception {
        GenerateAppCdsMojo mojo = new GenerateAppCdsMojo(() -> {
            throw new AssertionError("Disabled goal read JVM metadata");
        }, new AppCdsProcessRunner(builder -> {
            throw new AssertionError("Disabled goal started a process");
        }));
        field(mojo, "project", new MavenProject() {
            @Override
            public File getBasedir() { throw new AssertionError("Disabled goal read MavenProject"); }
        });
        Path nonexistent = temp.resolve("must not exist");
        field(mojo, "buildDirectory", nonexistent.toFile());
        field(mojo, "timeoutSeconds", -1);

        assertDoesNotThrow(mojo::execute);
        assertDoesNotThrow(new GenerateAppCdsMojo()::execute);
        assertFalse(Files.exists(nonexistent));
    }

    @Test
    void descriptorDeclaresOptionalPackageGoalWithRuntimeResolution() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var input = GenerateAppCdsMojo.class.getResourceAsStream("/META-INF/maven/plugin.xml")) {
            assertNotNull(input, "Maven must generate the plugin descriptor before tests");
            var mojos = factory.newDocumentBuilder().parse(input).getElementsByTagName("mojo");
            Element goal = null;
            for (int index = 0; index < mojos.getLength(); index++) {
                Element candidate = (Element) mojos.item(index);
                if ("generate-appcds".equals(text(candidate, "goal"))) goal = candidate;
            }
            assertNotNull(goal);
            assertEquals("package", text(goal, "phase"));
            assertEquals("runtime", text(goal, "requiresDependencyResolution"));
            Element enabled = (Element) goal.getElementsByTagName("configuration").item(0);
            enabled = (Element) enabled.getElementsByTagName("enabled").item(0);
            assertEquals("false", enabled.getAttribute("default-value"));
            assertEquals("${reactor.appcds.enabled}", enabled.getTextContent());
            Element config = (Element) goal.getElementsByTagName("configuration").item(0);
            assertEquals("dynamic", ((Element) config.getElementsByTagName("strategy").item(0)).getAttribute("default-value"));
        }
    }

    @Test
    void rejectsOpenJ9BeforeProjectOrFilesystemAccessAndPointsToScc() throws Exception {
        for (String name : List.of("Eclipse OpenJ9 VM", "IBM J9 VM", "openj9")) {
            GenerateAppCdsMojo mojo = new GenerateAppCdsMojo(() -> jvm(name, 21), forbiddenRunner());
            field(mojo, "enabled", true);
            field(mojo, "project", new MavenProject() {
                @Override
                public File getBasedir() { throw new AssertionError("Unsupported JVM read project"); }
            });
            MojoExecutionException failure = assertThrows(MojoExecutionException.class, mojo::execute);
            assertTrue(failure.getMessage().contains("OpenJ9"));
            assertTrue(failure.getMessage().contains("-Xshareclasses"));
            assertTrue(failure.getMessage().contains("runtime-image"));
        }
    }

    @Test
    void rejectsUnvalidatedVmFamiliesAndJavaVersions() throws Exception {
        for (var runtime : List.of(jvm("Other VM", 21), jvm("OpenJDK 64-Bit Server VM", 17),
                jvm("OpenJDK 64-Bit Server VM", 25))) {
            GenerateAppCdsMojo mojo = new GenerateAppCdsMojo(() -> runtime, forbiddenRunner());
            field(mojo, "enabled", true);
            assertTrue(assertThrows(MojoExecutionException.class, mojo::execute).getMessage().contains("Java 21 only"));
        }
    }

    @Test
    void requiresExplicitMainAndArgsInsteadOfInspectingManifestOrLaunchingBusinessMain() throws Exception {
        Fixture fixture = fixture(new RecordingRunner());
        for (String main : new String[]{null, "", "bad main", "-jar", "@arguments", "app/Main", "Train.java", "a".repeat(513)}) {
            field(fixture.mojo, "trainingMainClass", main);
            assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        }
        field(fixture.mojo, "trainingMainClass", "example.ArchiveTraining");
        List<List<String>> invalidArgs = new ArrayList<>();
        invalidArgs.add(null);
        invalidArgs.addAll(List.of(List.of(), Collections.nCopies(65, "--offline"),
                List.of("@arguments"), List.of("bad\nvalue"), List.of("bad\0value"), List.of("x".repeat(4097)),
                Collections.singletonList(null)));
        for (List<String> args : invalidArgs) {
            field(fixture.mojo, "trainingArgs", args);
            assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        }
        assertFalse(Files.exists(fixture.output));
    }

    @Test
    void rejectsUnboundedTimeoutsAndHeapSettingsBeforeAnyProcess() throws Exception {
        Fixture fixture = fixture(new RecordingRunner());
        for (int timeout : new int[]{-1, 0, 301}) {
            field(fixture.mojo, "timeoutSeconds", timeout);
            assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        }
        field(fixture.mojo, "timeoutSeconds", 60);
        for (int heap : new int[]{-1, 31, 1025}) {
            field(fixture.mojo, "maxHeapMb", heap);
            assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        }
        assertFalse(Files.exists(fixture.output));
    }

    @Test
    void buildsDeterministicTokenizedCommandsWithSpacesAndOrderedJarOnlyClasspath() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        Fixture fixture = fixture(runner);
        fixture.mojo.execute();

        assertEquals(2, runner.commands.size());
        String classpath = fixture.jar.toRealPath() + File.pathSeparator + fixture.second.toRealPath()
                + File.pathSeparator + fixture.first.toRealPath();
        List<String> common = List.of(jvm("OpenJDK 64-Bit Server VM", 21).javaHome().resolve("bin/java.exe").toString(),
                "-Xms32m", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-XX:ParallelGCThreads=1", "-XX:ConcGCThreads=1");
        List<String> training = new ArrayList<>(common);
        training.addAll(List.of("-Xshare:off", "-XX:DumpLoadedClassList=" + fixture.output.resolve("app-cds.classlist"),
                "-cp", classpath, "example.ArchiveTraining", "--offline", "--input", "directory with spaces/input.json"));
        List<String> dump = new ArrayList<>(common);
        dump.addAll(List.of("-Xshare:dump", "-XX:SharedClassListFile=" + fixture.output.resolve("app-cds.classlist"),
                "-XX:SharedArchiveFile=" + fixture.output.resolve("app-cds.jsa"), "-cp", classpath));
        assertEquals(training, runner.commands.get(0));
        assertEquals(dump, runner.commands.get(1));
        assertFalse(dump.contains("example.ArchiveTraining"));
        assertEquals(List.of(60, 60), runner.timeouts);
        assertEquals(List.of(fixture.output.resolve("training.log"), fixture.output.resolve("dump.log")), runner.logs);
        assertEquals(List.of(fixture.output, fixture.output), runner.directories);
        assertFalse(Files.exists(fixture.output.resolve(".generation.lock")));

        String originalMetadata = Files.readString(fixture.output.resolve("compatible-jvm.properties"));
        Properties metadata = new Properties();
        try (var reader = Files.newBufferedReader(fixture.output.resolve("compatible-jvm.properties"))) {
            metadata.load(reader);
        }
        assertEquals("false", metadata.getProperty("portable"));
        assertEquals("false", metadata.getProperty("deploymentVerified"));
        assertEquals("21.0.2+13-LTS", metadata.getProperty("java.runtime.version"));
        assertEquals("21.0.2+13-LTS", metadata.getProperty("java.vm.version"));
        assertEquals("Windows 11", metadata.getProperty("os.name"));
        assertEquals("amd64", metadata.getProperty("os.arch"));
        assertEquals(fixture.jar.toRealPath().toString(), metadata.getProperty("classpath.0.path"));
        assertTrue(metadata.getProperty("classpath.0.sha256").matches("[0-9a-f]{64}"));
        assertTrue(metadata.getProperty("archive.sha256").matches("[0-9a-f]{64}"));
        assertFalse(originalMetadata.contains("directory with spaces/input.json"), "Do not persist training arguments");
        fixture.mojo.execute();
        assertEquals(originalMetadata, Files.readString(fixture.output.resolve("compatible-jvm.properties")));
        assertEquals(runner.commands.subList(0, 2), runner.commands.subList(2, 4));
    }

    @Test
    void requiresExistingPackagedArtifactAndJarDependenciesNotClassesOrWildcards() throws Exception {
        Fixture fixture = fixture(forbiddenRunner());
        fixture.project.getArtifact().setFile(null);
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        fixture.project.getArtifact().setFile(fixture.jar.toFile());
        Files.delete(fixture.first);
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        Files.createDirectories(fixture.first);
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        assertFalse(Files.exists(fixture.output));
    }

    @Test
    void replacesItsOwnReadOnlyArchiveWithoutChangingInputJars() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        Fixture fixture = fixture(runner);
        fixture.mojo.execute();
        Path archive = fixture.output.resolve("app-cds.jsa");
        DosFileAttributeView dos = Files.getFileAttributeView(archive, DosFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(dos != null, "DOS read-only attributes unavailable");
        byte[] jar = Files.readAllBytes(fixture.jar);
        dos.setReadOnly(true);
        try {
            assertTrue(dos.readAttributes().isReadOnly());
            assertDoesNotThrow(fixture.mojo::execute);
            assertEquals(4, runner.commands.size());
            org.junit.jupiter.api.Assertions.assertArrayEquals(jar, Files.readAllBytes(fixture.jar));
            assertFalse(Files.exists(fixture.output.resolve(".generation.lock")));
            assertTrue(Files.exists(fixture.output.resolve("compatible-jvm.properties")));
        } finally {
            if (Files.exists(archive)) Files.getFileAttributeView(archive, DosFileAttributeView.class).setReadOnly(false);
        }
    }

    @Test
    void failureRemovesReadOnlyPartialArchiveAndDoesNotPublishMetadata() throws Exception {
        Fixture fixture = fixture(new AppCdsProcessRunner() {
            @Override
            void run(List<String> command, Path directory, Path log, int timeout) throws MojoExecutionException {
                try {
                    Path archive = directory.resolve("app-cds.jsa");
                    Files.writeString(archive, "partial archive");
                    DosFileAttributeView dos = Files.getFileAttributeView(archive, DosFileAttributeView.class);
                    if (dos != null) dos.setReadOnly(true);
                } catch (IOException error) {
                    throw new MojoExecutionException("Cannot create read-only test archive", error);
                }
                throw new MojoExecutionException("dump failed after creating a read-only archive");
            }
        });
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        assertNoPublishedArchive(fixture);
        assertFalse(Files.exists(fixture.output.resolve(".generation.lock")));
    }

    @Test
    void rejectsBuildDirectoryOutsideProjectAndConcurrentGeneration() throws Exception {
        Fixture fixture = fixture(forbiddenRunner());
        field(fixture.mojo, "buildDirectory", temp.resolve("outside project").toFile());
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        assertFalse(Files.exists(temp.resolve("outside project")));
        field(fixture.mojo, "buildDirectory", fixture.output.getParent().toFile());
        Files.createDirectories(fixture.output);
        Files.writeString(fixture.output.resolve(".generation.lock"), "owned by another build");
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        assertEquals("owned by another build", Files.readString(fixture.output.resolve(".generation.lock")));
    }

    @Test
    void rejectsOversizedCommandsBeforeLaunching() throws Exception {
        Fixture fixture = fixture(forbiddenRunner());
        field(fixture.mojo, "trainingArgs", Collections.nCopies(8, "x".repeat(4000)));
        assertTrue(assertThrows(MojoExecutionException.class, fixture.mojo::execute).getMessage().contains("30000"));
        assertFalse(Files.exists(fixture.output));
    }

    @Test
    void dynamicUsesDefaultJdkBaseAndValidatesWithoutReplayingTraining() throws Exception {
        RecordingRunner runner = new DynamicRunner();
        Fixture fixture = dynamicFixture(runner);
        fixture.mojo.execute();
        assertEquals(2, runner.commands.size());
        assertTrue(runner.commands.getFirst().contains("-Xshare:on"));
        assertTrue(runner.commands.getFirst().contains("-XX:ArchiveClassesAtExit=" + fixture.output.resolve("app-cds.jsa")));
        assertTrue(runner.commands.get(1).contains("-XX:+PrintSharedArchiveAndExit"));
        assertTrue(runner.commands.get(1).contains("-XX:+VerifySharedSpaces"));
        assertFalse(runner.commands.get(1).contains("example.ArchiveTraining"));
        assertFalse(runner.commands.get(1).contains("--offline"));
        assertFalse(runner.commands.stream().anyMatch(command -> command.contains("-Xshare:off") || command.contains("-Xshare:dump")));
        Properties metadata = new Properties();
        try (var reader = Files.newBufferedReader(fixture.output.resolve("compatible-jvm.properties"))) { metadata.load(reader); }
        assertEquals("dynamic", metadata.getProperty("strategy"));
        assertEquals("true", metadata.getProperty("archive.validated"));
        assertTrue(metadata.getProperty("base.archive.sha256").matches("[0-9a-f]{64}"));
        assertTrue(metadata.getProperty("runtime.sharedArchiveFile").endsWith(File.pathSeparator + fixture.output.resolve("app-cds.jsa")));
    }

    @Test
    void dynamicRejectsMissingDefaultBaseBeforeLaunching() throws Exception {
        Fixture fixture = fixture(forbiddenRunner());
        field(fixture.mojo, "strategy", "dynamic");
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        assertFalse(Files.exists(fixture.output));
    }

    @Test
    void rejectsUnknownStrategyWithoutStartingAProcess() throws Exception {
        Fixture fixture = fixture(forbiddenRunner());
        field(fixture.mojo, "strategy", "automatic");
        assertTrue(assertThrows(MojoExecutionException.class, fixture.mojo::execute).getMessage().contains("strategy"));
    }

    @Test
    void dynamicDiscardsTopIfBaseChangesDuringTrainingOrValidation() throws Exception {
        Fixture fixture = dynamicFixture(new DynamicRunner() {
            @Override void run(List<String> command, Path directory, Path log, int timeout) throws MojoExecutionException {
                super.run(command, directory, log, timeout);
                if (command.contains("-XX:+PrintSharedArchiveAndExit")) {
                    try { Files.writeString(jvm("OpenJDK 64-Bit Server VM", 21).javaHome().resolve("bin/server/classes.jsa"), "changed"); }
                    catch (IOException error) { throw new MojoExecutionException("fixture", error); }
                }
            }
        });
        assertTrue(assertThrows(MojoExecutionException.class, fixture.mojo::execute).getMessage().contains("base archive changed"));
        assertNoPublishedArchive(fixture);
    }

    @Test
    void dynamicRejectsBaseOnlyValidationEvenWhenChildExitsSuccessfully() throws Exception {
        Fixture fixture = dynamicFixture(new DynamicRunner() {
            @Override void run(List<String> command, Path directory, Path log, int timeout) throws MojoExecutionException {
                super.run(command, directory, log, timeout);
                if (command.contains("-XX:+PrintSharedArchiveAndExit")) {
                    try { Files.writeString(log, "Base archive name: classes.jsa\n"); }
                    catch (IOException error) { throw new MojoExecutionException("fixture", error); }
                }
            }
        });
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        assertNoPublishedArchive(fixture);
    }

    @Test
    void unconfirmedTerminationRetainsLockWithoutPublishingSuccess() throws Exception {
        Fixture fixture = fixture(new AppCdsProcessRunner() {
            @Override
            void run(List<String> command, Path directory, Path log, int timeout) throws MojoExecutionException {
                throw new TerminationUnconfirmedException("writer still alive", null);
            }
        });
        assertThrows(AppCdsProcessRunner.TerminationUnconfirmedException.class, fixture.mojo::execute);
        assertTrue(Files.isRegularFile(fixture.output.resolve(".generation.lock")));
        assertFalse(Files.exists(fixture.output.resolve("compatible-jvm.properties")));
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
    }

    @Test
    void rejectsNonRegularLogWithoutStartingOrDeletingAnything() throws Exception {
        Fixture fixture = fixture(forbiddenRunner());
        Path log = Files.createDirectories(fixture.output.resolve("training.log"));
        Path marker = Files.writeString(log.resolve("preserve.txt"), "not ours");
        assertTrue(assertThrows(MojoExecutionException.class, fixture.mojo::execute)
                .getMessage().contains("non-regular"));
        assertEquals("not ours", Files.readString(marker));
        assertFalse(Files.exists(fixture.output.resolve(".generation.lock")));
    }

    @Test
    void rejectsBuildSymlinkAliasingTheProjectRootBeforeCreatingOutput() throws Exception {
        Fixture fixture = fixture(forbiddenRunner());
        Path base = fixture.jar.getParent();
        Files.createSymbolicLink(fixture.output.getParent(), base);
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        assertFalse(Files.exists(base.resolve("reactor-appcds")));
    }

    @Test
    void refusesSymlinkedLogWithoutChangingItsExternalTarget() throws Exception {
        Fixture fixture = fixture(forbiddenRunner());
        Files.createDirectories(fixture.output);
        Path external = Files.writeString(temp.resolve("external.log"), "preserve");
        Files.createSymbolicLink(fixture.output.resolve("training.log"), external);
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        assertEquals("preserve", Files.readString(external));
        assertFalse(Files.exists(fixture.output.resolve(".generation.lock")));
    }

    @Test
    void metadataRoundTripsLeadingWhitespaceAndPropertiesEscapeCharacters() throws Exception {
        var runtime = jvm("OpenJDK 64-Bit Server VM", 21);
        String vendor = " \tVendor\fwith\\escape";
        Fixture fixture = fixture(new RecordingRunner(), new GenerateAppCdsMojo.JvmInfo(runtime.javaHome(),
                runtime.vmName(), runtime.vmVersion(), runtime.runtimeVersion(), vendor, runtime.osName(),
                runtime.osArch(), runtime.feature()));
        fixture.mojo.execute();
        Properties metadata = new Properties();
        try (var reader = Files.newBufferedReader(fixture.output.resolve("compatible-jvm.properties"))) {
            metadata.load(reader);
        }
        assertEquals(vendor, metadata.getProperty("java.vendor"));
    }

    @Test
    void successfulDumpWithoutAnArchiveCannotPublishMetadata() throws Exception {
        Fixture fixture = fixture(new RecordingRunner() {
            @Override
            void run(List<String> command, Path directory, Path log, int timeout) throws MojoExecutionException {
                if (!command.contains("-Xshare:dump")) super.run(command, directory, log, timeout);
            }
        });
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        assertNoPublishedArchive(fixture);
    }

    @Test
    void failedTrainingStopsBeforeDumpAndRemovesStaleSuccessArtifacts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture(new AppCdsProcessRunner() {
            @Override
            void run(List<String> command, Path directory, Path log, int timeout) throws MojoExecutionException {
                calls.incrementAndGet();
                throw new MojoExecutionException("training failed");
            }
        });
        Files.createDirectories(fixture.output);
        Files.writeString(fixture.output.resolve("app-cds.jsa"), "stale");
        Files.writeString(fixture.output.resolve("compatible-jvm.properties"), "stale");
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        assertEquals(1, calls.get());
        assertNoPublishedArchive(fixture);
    }

    @Test
    void rejectsSuccessfulExitWithoutAClassList() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture(new AppCdsProcessRunner() {
            @Override
            void run(List<String> command, Path directory, Path log, int timeout) { calls.incrementAndGet(); }
        });
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        assertEquals(1, calls.get());
        assertNoPublishedArchive(fixture);
    }

    @Test
    void failedDumpDiscardsPartialArchiveAndKeepsDiagnosticClassList() throws Exception {
        Fixture fixture = fixture(new RecordingRunner() {
            @Override
            void run(List<String> command, Path directory, Path log, int timeout) throws MojoExecutionException {
                super.run(command, directory, log, timeout);
                if (command.contains("-Xshare:dump")) throw new MojoExecutionException("dump failed");
            }
        });
        assertThrows(MojoExecutionException.class, fixture.mojo::execute);
        assertTrue(Files.isRegularFile(fixture.output.resolve("app-cds.classlist")));
        assertNoPublishedArchive(fixture);
    }

    @Test
    void rejectsClasspathMutationDuringTrainingOrDump() throws Exception {
        RecordingRunner runner = new RecordingRunner() {
            @Override
            void run(List<String> command, Path directory, Path log, int timeout) throws MojoExecutionException {
                super.run(command, directory, log, timeout);
                if (command.contains("-Xshare:dump")) {
                    try {
                        Files.writeString(temp.resolve("project with spaces/second dependency.jar"), "changed");
                    } catch (IOException error) { throw new MojoExecutionException("test fixture", error); }
                }
            }
        };
        Fixture fixture = fixture(runner);
        assertTrue(assertThrows(MojoExecutionException.class, fixture.mojo::execute).getMessage().contains("classpath changed"));
        assertNoPublishedArchive(fixture);
    }

    private Fixture fixture(AppCdsProcessRunner runner) throws Exception {
        return fixture(runner, jvm("OpenJDK 64-Bit Server VM", 21));
    }

    private Fixture fixture(AppCdsProcessRunner runner, GenerateAppCdsMojo.JvmInfo runtime) throws Exception {
        Path base = Files.createDirectories(temp.resolve("project with spaces"));
        Path build = base.resolve("target with spaces");
        Path jar = Files.writeString(base.resolve("application with spaces.jar"), "fixture, never executed");
        Path first = Files.writeString(base.resolve("first dependency.jar"), "first");
        Path second = Files.writeString(base.resolve("second dependency.jar"), "second");
        Path java = jvm("OpenJDK 64-Bit Server VM", 21).javaHome().resolve("bin/java.exe");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "fixture, never executed");
        MavenProject project = new MavenProject() {
            @Override
            public List<String> getRuntimeClasspathElements() {
                return List.of(build.resolve("classes").toString(), second.toString(), first.toString(), second.toString());
            }
        };
        project.setFile(base.resolve("pom.xml").toFile());
        Build model = new Build();
        model.setDirectory(build.toString());
        model.setOutputDirectory(build.resolve("classes").toString());
        project.setBuild(model);
        var artifact = new DefaultArtifact("example", "training", "1", "compile", "jar", null, new DefaultArtifactHandler("jar"));
        artifact.setFile(jar.toFile());
        project.setArtifact(artifact);
        GenerateAppCdsMojo mojo = new GenerateAppCdsMojo(() -> runtime, runner);
        field(mojo, "strategy", "static");
        field(mojo, "enabled", true);
        field(mojo, "project", project);
        field(mojo, "buildDirectory", build.toFile());
        field(mojo, "trainingMainClass", "example.ArchiveTraining");
        field(mojo, "trainingArgs", List.of("--offline", "--input", "directory with spaces/input.json"));
        return new Fixture(mojo, project, build.resolve("reactor-appcds"), jar, first, second);
    }

    private Fixture dynamicFixture(AppCdsProcessRunner runner) throws Exception {
        Fixture fixture = fixture(runner);
        Path base = jvm("OpenJDK 64-Bit Server VM", 21).javaHome().resolve("bin/server/classes.jsa");
        Files.createDirectories(base.getParent());
        Files.writeString(base, "JDK base fixture; never executed");
        field(fixture.mojo, "strategy", "dynamic");
        return fixture;
    }

    private static class DynamicRunner extends RecordingRunner {
        @Override void run(List<String> command, Path directory, Path log, int timeout) throws MojoExecutionException {
            commands.add(List.copyOf(command));
            try {
                if (command.contains("-XX:+PrintSharedArchiveAndExit")) {
                    Files.writeString(log, "Dynamic archive name: " + directory.resolve("app-cds.jsa") + "\nexample.ArchiveTraining app_loader\n");
                } else {
                    Files.writeString(directory.resolve("app-cds.classlist"), "example/ArchiveTraining");
                    Files.writeString(directory.resolve("app-cds.jsa"), "dynamic top");
                }
            } catch (IOException error) { throw new MojoExecutionException("fixture", error); }
        }
    }

    private GenerateAppCdsMojo.JvmInfo jvm(String name, int version) {
        return new GenerateAppCdsMojo.JvmInfo(temp.resolve("JDK with spaces"), name, "21.0.2+13-LTS",
                "21.0.2+13-LTS", "Test Vendor", "Windows 11", "amd64", version);
    }

    private static AppCdsProcessRunner forbiddenRunner() {
        return new AppCdsProcessRunner(builder -> { throw new AssertionError("No process allowed"); });
    }

    private static void assertNoPublishedArchive(Fixture fixture) {
        assertFalse(Files.exists(fixture.output.resolve("app-cds.jsa")));
        assertFalse(Files.exists(fixture.output.resolve("compatible-jvm.properties")));
        assertFalse(Files.exists(fixture.output.resolve(".generation.lock")));
    }

    private static String text(Element element, String name) {
        return element.getElementsByTagName(name).item(0).getTextContent();
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(GenerateAppCdsMojo mojo, MavenProject project, Path output, Path jar, Path first, Path second) {}

    private static class RecordingRunner extends AppCdsProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        final List<Path> logs = new ArrayList<>();
        final List<Path> directories = new ArrayList<>();
        final List<Integer> timeouts = new ArrayList<>();

        @Override
        void run(List<String> command, Path directory, Path log, int timeout) throws MojoExecutionException {
            commands.add(List.copyOf(command));
            logs.add(log);
            directories.add(directory);
            timeouts.add(timeout);
            try {
                Files.writeString(directory.resolve(command.contains("-Xshare:dump") ? "app-cds.jsa" : "app-cds.classlist"), "fake output");
            } catch (IOException error) { throw new MojoExecutionException("test fixture", error); }
        }
    }
}

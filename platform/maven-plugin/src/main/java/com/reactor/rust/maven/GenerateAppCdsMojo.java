package com.reactor.rust.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Explicit, bounded HotSpot 21 AppCDS training. Never infers a main class from a manifest. */
@Mojo(name = "generate-appcds", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.RUNTIME)
public final class GenerateAppCdsMojo extends AbstractMojo {
    @Parameter(property = "reactor.appcds.enabled", defaultValue = "false")
    private boolean enabled;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    @Parameter(property = "reactor.appcds.trainingMainClass")
    private String trainingMainClass;

    /** Explicit application arguments, each an individual XML list element, not a shell command. */
    @Parameter
    private List<String> trainingArgs;

    @Parameter(property = "reactor.appcds.timeoutSeconds", defaultValue = "60")
    private int timeoutSeconds = 60;

    @Parameter(property = "reactor.appcds.maxHeapMb", defaultValue = "256")
    private int maxHeapMb = 256;

    /** Dynamic preserves the JDK's base coverage; static is an explicit legacy alternative. */
    @Parameter(property = "reactor.appcds.strategy", defaultValue = "dynamic")
    private String strategy = "dynamic";

    private final Supplier<JvmInfo> jvm;
    private final AppCdsProcessRunner runner;

    public GenerateAppCdsMojo() {
        this(JvmInfo::current, new AppCdsProcessRunner());
    }

    GenerateAppCdsMojo(Supplier<JvmInfo> jvm, AppCdsProcessRunner runner) {
        this.jvm = jvm;
        this.runner = runner;
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (!enabled) return;
        JvmInfo runtime = jvm.get();
        runtime.requireHotSpot21();
        validateTraining();
        try {
            Plan plan = plan(runtime);
            Files.createDirectories(plan.buildDirectory());
            Path realBase = project.getBasedir().toPath().toRealPath();
            Path realBuild = plan.buildDirectory().toRealPath();
            if (!realBuild.startsWith(realBase) || realBuild.equals(realBase)) {
                throw new MojoExecutionException("AppCDS build directory resolves outside the project directory");
            }
            Files.createDirectories(plan.directory());
            Path directory = plan.directory().toRealPath();
            if (!directory.startsWith(plan.buildDirectory().toRealPath())) {
                throw new MojoExecutionException("AppCDS output must remain inside the project build directory");
            }
            for (String name : List.of("app-cds.classlist", "app-cds.jsa", "compatible-jvm.properties",
                    "training.log", "dump.log", "validation.log", ".generation.lock")) {
                Path output = directory.resolve(name);
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MojoExecutionException("Refusing non-regular AppCDS output: " + name);
                }
            }
            Path lock = directory.resolve(".generation.lock");
            Files.createFile(lock);
            boolean releaseLock = true;
            try {
                generate(runtime, plan);
            } catch (AppCdsProcessRunner.TerminationUnconfirmedException error) {
                releaseLock = false;
                throw error;
            } finally {
                if (releaseLock) Files.deleteIfExists(lock);
            }
        } catch (IOException | DependencyResolutionRequiredException error) {
            throw new MojoExecutionException("Cannot generate AppCDS archive: " + error.getMessage(), error);
        }
    }

    private void validateTraining() throws MojoExecutionException {
        if (!"dynamic".equals(strategy) && !"static".equals(strategy)) {
            throw new MojoExecutionException("reactor.appcds.strategy must be dynamic or static");
        }
        if (trainingMainClass == null || trainingMainClass.length() > 512 || trainingMainClass.endsWith(".java")
                || !trainingMainClass.matches(
                "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw new MojoExecutionException("Set reactor.appcds.trainingMainClass to an explicit offline training main");
        }
        if (trainingArgs == null || trainingArgs.isEmpty() || trainingArgs.size() > 64) {
            throw new MojoExecutionException("Explicit trainingArgs are required (1..64 arguments); no application defaults are inferred");
        }
        for (String arg : trainingArgs) {
            if (arg == null || arg.length() > 4096 || arg.startsWith("@")
                    || arg.chars().anyMatch(Character::isISOControl)) {
                throw new MojoExecutionException("trainingArgs cannot contain control characters, @argfiles or arguments over 4096 characters");
            }
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new MojoExecutionException("reactor.appcds.timeoutSeconds must be between 1 and 300 per process");
        }
        if (maxHeapMb < 32 || maxHeapMb > 1024) {
            throw new MojoExecutionException("reactor.appcds.maxHeapMb must be between 32 and 1024");
        }
    }

    private Plan plan(JvmInfo runtime) throws IOException, MojoExecutionException,
            DependencyResolutionRequiredException {
        if (project == null || project.getBasedir() == null || buildDirectory == null) {
            throw new MojoExecutionException("AppCDS requires a packaged Maven project and project build directory");
        }
        Path base = project.getBasedir().toPath().toRealPath();
        Path build = buildDirectory.toPath().toAbsolutePath().normalize();
        if (!build.startsWith(base) || build.equals(base)) {
            throw new MojoExecutionException("AppCDS build directory must be a child of the project directory");
        }
        Path ancestor = build;
        while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(base)) {
            throw new MojoExecutionException("AppCDS build directory must not resolve outside the project directory");
        }
        if (Files.exists(build) && build.toRealPath().equals(base)) {
            throw new MojoExecutionException("AppCDS build directory must not resolve to the project directory itself");
        }
        Path java = runtime.javaHome().resolve("bin").resolve(runtime.osName().startsWith("Windows") ? "java.exe" : "java");
        if (!Files.isRegularFile(java)) throw new MojoExecutionException("Missing Java executable: " + java);
        if (project.getArtifact() == null || project.getArtifact().getFile() == null) {
            throw new MojoExecutionException("Package the project JAR before generate-appcds; no manifest or classes-directory fallback is allowed");
        }
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        entries.add(requireJar(project.getArtifact().getFile().toPath()));
        if (project.getBuild().getOutputDirectory() == null) {
            throw new MojoExecutionException("AppCDS requires project.build.outputDirectory");
        }
        Path classes = Path.of(project.getBuild().getOutputDirectory()).toAbsolutePath().normalize();
        for (String entry : project.getRuntimeClasspathElements()) {
            Path path = Path.of(entry).toAbsolutePath().normalize();
            if (!path.equals(classes)) entries.add(requireJar(path));
            if (entries.size() > 4096) throw new MojoExecutionException("AppCDS classpath exceeds 4096 JARs");
        }
        String classpath = String.join(File.pathSeparator, entries.stream().map(Path::toString).toList());
        Path directory = build.resolve("reactor-appcds");
        List<String> common = List.of(java.toAbsolutePath().normalize().toString(), "-Xms32m", "-Xmx" + maxHeapMb + "m",
                "-XX:ActiveProcessorCount=2", "-XX:ParallelGCThreads=1", "-XX:ConcGCThreads=1");
        Path archive = directory.resolve("app-cds.jsa");
        Path baseArchive = null;
        List<String> train = new ArrayList<>(common);
        List<String> dump = new ArrayList<>(common);
        if ("dynamic".equals(strategy)) {
            Path location = runtime.javaHome().resolve(runtime.osName().startsWith("Windows")
                    ? "bin/server/classes.jsa" : "lib/server/classes.jsa");
            requireOutput(location);
            baseArchive = location.toRealPath();
            if (baseArchive.toString().indexOf(File.pathSeparatorChar) >= 0
                    || baseArchive.toString().chars().anyMatch(Character::isISOControl)) {
                throw new MojoExecutionException("Unsafe default JDK archive path: " + baseArchive);
            }
            train.addAll(List.of("-Xshare:on", "-XX:SharedArchiveFile=" + baseArchive,
                    "-XX:ArchiveClassesAtExit=" + archive));
            // This JVM prints archive dictionaries and exits before application main execution.
            dump.addAll(List.of("-Xshare:on", "-XX:SharedArchiveFile=" + baseArchive + File.pathSeparator + archive,
                    "-XX:+VerifySharedSpaces", "-XX:+PrintSharedArchiveAndExit", "-cp", classpath));
        } else {
            train.add("-Xshare:off");
            dump.addAll(List.of("-Xshare:dump", "-XX:SharedClassListFile=" + directory.resolve("app-cds.classlist"),
                    "-XX:SharedArchiveFile=" + archive, "-cp", classpath));
        }
        train.addAll(List.of("-XX:DumpLoadedClassList=" + directory.resolve("app-cds.classlist"),
                "-cp", classpath, trainingMainClass));
        train.addAll(trainingArgs);
        for (List<String> command : List.of(train, dump)) {
            if (command.stream().mapToLong(arg -> arg.length() + 3L).sum() > 30_000L) {
                throw new MojoExecutionException("AppCDS command exceeds the conservative 30000-character limit; shorten classpath/arguments");
            }
        }
        return new Plan(build, directory, List.copyOf(entries), List.copyOf(common.subList(1, common.size())),
                List.copyOf(train), List.copyOf(dump), baseArchive);
    }

    private static Path requireJar(Path path) throws IOException, MojoExecutionException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) throw new MojoExecutionException("Missing classpath JAR: " + path);
        absolute = absolute.toRealPath();
        String value = absolute.toString();
        if (!value.toLowerCase(Locale.ROOT).endsWith(".jar") || !Files.isRegularFile(absolute)
                || value.indexOf(File.pathSeparatorChar) >= 0 || value.indexOf('*') >= 0
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new MojoExecutionException("AppCDS requires existing JAR-only classpath entries without separators/wildcards: " + path);
        }
        return absolute;
    }

    private void generate(JvmInfo runtime, Plan plan) throws IOException, MojoExecutionException {
        Path archive = plan.directory().resolve("app-cds.jsa");
        Path metadata = plan.directory().resolve("compatible-jvm.properties");
        boolean complete = false;
        boolean terminationUnconfirmed = false;
        try {
            Files.deleteIfExists(metadata);
            deleteGeneratedArchive(archive);
            Files.deleteIfExists(plan.directory().resolve("app-cds.classlist"));
            Files.deleteIfExists(plan.directory().resolve("dump.log"));
            Files.deleteIfExists(plan.directory().resolve("validation.log"));
            List<String> hashes = fingerprints(plan.classpath());
            String baseHash = plan.baseArchive() == null ? null : sha256(plan.baseArchive());
            runner.run(plan.training(), plan.directory(), plan.directory().resolve("training.log"), timeoutSeconds);
            requireOutput(plan.directory().resolve("app-cds.classlist"));
            if (plan.baseArchive() != null) requireOutput(archive);
            Path secondLog = plan.directory().resolve(plan.baseArchive() == null ? "dump.log" : "validation.log");
            runner.run(plan.dump(), plan.directory(), secondLog, timeoutSeconds);
            requireOutput(archive);
            if (plan.baseArchive() != null) {
                if (!baseHash.equals(sha256(plan.baseArchive()))) {
                    throw new MojoExecutionException("Default JDK AppCDS base archive changed; archive discarded");
                }
                if (!logContains(secondLog, "Dynamic archive name: " + archive)
                        || !logContains(secondLog, trainingMainClass + " app_loader")) {
                    throw new MojoExecutionException("HotSpot did not validate the dynamic archive and training main; archive discarded");
                }
            }
            if (!hashes.equals(fingerprints(plan.classpath()))) {
                throw new MojoExecutionException("AppCDS classpath changed during training/dump; archive discarded");
            }
            writeMetadata(metadata, runtime, plan, hashes, archive, baseHash);
            complete = true;
            getLog().info("Generated " + archive + "; JVM/build/OS/architecture/classpath-specific, not a portable archive");
        } catch (AppCdsProcessRunner.TerminationUnconfirmedException error) {
            terminationUnconfirmed = true;
            throw error;
        } finally {
            if (!complete) {
                Files.deleteIfExists(metadata);
                if (!terminationUnconfirmed) deleteGeneratedArchive(archive);
            }
        }
    }

    private static void deleteGeneratedArchive(Path archive) throws IOException {
        if (!Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing non-regular generated AppCDS archive: " + archive);
        }
        // HotSpot marks its Windows archive read-only, including successful dumps.
        // Only our validated, generation-locked output is eligible for replacement.
        DosFileAttributeView dos = Files.getFileAttributeView(
                archive, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
        Files.deleteIfExists(archive);
    }

    private static void requireOutput(Path file) throws IOException, MojoExecutionException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) == 0L) {
            throw new MojoExecutionException("AppCDS child exited without nonempty output: " + file);
        }
    }

    private static boolean logContains(Path log, String expected) throws IOException {
        try (var lines = Files.lines(log)) {
            return lines.anyMatch(line -> line.contains(expected));
        }
    }

    private static List<String> fingerprints(List<Path> entries) throws IOException {
        List<String> hashes = new ArrayList<>(entries.size());
        for (Path entry : entries) hashes.add(sha256(entry));
        return hashes;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream input = Files.newInputStream(file)) {
                int length;
                while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void writeMetadata(Path metadata, JvmInfo runtime, Plan plan, List<String> hashes, Path archive, String baseHash)
            throws IOException {
        Map<String, String> values = new TreeMap<>();
        values.put("portable", "false");
        values.put("deploymentVerified", "false");
        values.put("java.home", runtime.javaHome().toAbsolutePath().normalize().toString());
        values.put("java.runtime.version", runtime.runtimeVersion());
        values.put("java.vendor", runtime.vendor());
        values.put("java.vm.name", runtime.vmName());
        values.put("java.vm.version", runtime.vmVersion());
        values.put("os.name", runtime.osName());
        values.put("os.arch", runtime.osArch());
        values.put("training.main", trainingMainClass);
        values.put("archive.sha256", sha256(archive));
        values.put("strategy", strategy);
        values.put("archive.validated", Boolean.toString(plan.baseArchive() != null));
        values.put("runtime.sharedArchiveFile", archive.toString());
        if (plan.baseArchive() != null) {
            values.put("base.archive.path", plan.baseArchive().toString());
            values.put("base.archive.sha256", baseHash);
            values.put("base.archive.size", Long.toString(Files.size(plan.baseArchive())));
            values.put("runtime.sharedArchiveFile", plan.baseArchive() + File.pathSeparator + archive);
            values.put("archive.validation", "HotSpot VerifySharedSpaces and PrintSharedArchiveAndExit; application main not replayed");
        }
        values.put("compatibility", "Same JDK build, OS/architecture, VM options and unchanged ordered JAR classpath required; validate with -Xshare:on in target image");
        for (int index = 0; index < plan.vmArgs().size(); index++) values.put("vm.arg." + index, plan.vmArgs().get(index));
        for (int index = 0; index < plan.classpath().size(); index++) {
            values.put("classpath." + index + ".path", plan.classpath().get(index).toString());
            values.put("classpath." + index + ".sha256", hashes.get(index));
        }
        StringBuilder text = new StringBuilder();
        values.forEach((key, value) -> text.append(key).append('=').append(value.replace("\\", "\\\\")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
                .replace("\f", "\\f").replace(" ", "\\ ")).append('\n'));
        Files.writeString(metadata, text, StandardCharsets.UTF_8);
    }

    private record Plan(Path buildDirectory, Path directory, List<Path> classpath, List<String> vmArgs,
                        List<String> training, List<String> dump, Path baseArchive) {}

    record JvmInfo(Path javaHome, String vmName, String vmVersion, String runtimeVersion, String vendor,
                   String osName, String osArch, int feature) {
        static JvmInfo current() {
            return new JvmInfo(Path.of(System.getProperty("java.home")), System.getProperty("java.vm.name"),
                    System.getProperty("java.vm.version"), System.getProperty("java.runtime.version"),
                    System.getProperty("java.vendor"), System.getProperty("os.name"), System.getProperty("os.arch"),
                    Runtime.version().feature());
        }

        void requireHotSpot21() throws MojoExecutionException {
            String name = vmName.toLowerCase(Locale.ROOT);
            if (name.contains("openj9") || name.contains("j9")) {
                throw new MojoExecutionException("generate-appcds cannot create HotSpot .jsa archives on OpenJ9. Use OpenJ9 SCC (-Xshareclasses) via runtime-image with reactor.runtime.openj9.scc.enabled=true; see docs/startup-tuning.md and docs/production-runtime.md");
            }
            if (feature != 21 || !(name.contains("hotspot") || name.contains("openjdk") && name.contains("server vm"))) {
                throw new MojoExecutionException("generate-appcds supports HotSpot/OpenJDK Server VM Java 21 only; detected " + vmName + " Java " + feature);
            }
        }
    }
}

package com.reactor.rust.maven;

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Standalone, opt-in cluster harness. Does not depend on Maven being installed in the test image. */
public final class AppCdsEmaTechHarness {
    private static final String FIXTURE = AppCdsOfflineFixture.class.getName();
    private static final List<String> VM = List.of("-Xms32m", "-Xmx128m", "-XX:ActiveProcessorCount=2",
            "-XX:ParallelGCThreads=1", "-XX:ConcGCThreads=1");
    private static final String BODY = "{\"item16\":\"appcds\",\"count\":64}";

    private AppCdsEmaTechHarness() {}

    public static void main(String[] args) throws Exception {
        requireEmaTech();
        if (args.length < 2) throw new IllegalArgumentException(
                "hotspot <new-evidence-dir> | openj9 <new-evidence-dir> | compare <hotspot-dir> <new-results-dir> [pairs=3]");
        switch (args[0]) {
            case "hotspot" -> generate(Path.of(args[1]));
            case "openj9" -> rejectOpenJ9(Path.of(args[1]));
            case "compare" -> {
                if (args.length < 3) throw new IllegalArgumentException("compare needs generation and result directories");
                compare(Path.of(args[1]), Path.of(args[2]), args.length == 4 ? Integer.parseInt(args[3]) : 3);
            }
            default -> throw new IllegalArgumentException("Unknown mode: " + args[0]);
        }
    }

    static void requireEmaTech() {
        check("EmaTech".equals(System.getenv("REACTOR_TEST_CLUSTER")),
                "Parent must authorize EmaTech with REACTOR_TEST_CLUSTER=EmaTech; no local tests");
        check("Linux".equals(System.getProperty("os.name")), "Cluster harness requires Linux /proc");
    }

    static Path generate(Path output) throws Exception {
        requireEmaTech();
        GenerateAppCdsMojo.JvmInfo.current().requireHotSpot21();
        output = Files.createDirectory(output.toAbsolutePath().normalize());
        writeRuntime(output);
        Path jar = output.resolve("offline fixture.jar");
        try (var target = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of(FIXTURE, FIXTURE + "$Payload")) {
                String resource = name.replace('.', '/') + ".class";
                try (var source = AppCdsEmaTechHarness.class.getClassLoader().getResourceAsStream(resource)) {
                    check(source != null, "Missing compiled fixture " + resource);
                    target.putNextEntry(new JarEntry(resource));
                    source.transferTo(target);
                    target.closeEntry();
                }
            }
        }
        List<Path> classpath = frameworkJars();
        classpath.add(jar);
        String descriptor = System.getenv().getOrDefault("APPCDS_FRAMEWORK_DESCRIPTOR", "");
        check((classpath.size() == 1) == descriptor.isEmpty(), "Set framework JARs and descriptor together");
        if (!descriptor.isEmpty()) check(descriptor.matches("[A-Za-z_$][A-Za-z0-9_$.]*"), "Invalid descriptor class name");
        List<String> offlineArgs = fixtureArgs(descriptor, List.of("--offline"));
        GenerateAppCdsMojo mojo = mojo(output, classpath, offlineArgs, 30);
        mojo.execute();
        Path generated = output.resolve("target/reactor-appcds");
        Path archive = generated.resolve("app-cds.jsa");
        check(Files.size(archive) > 0, "No real archive");
        Properties archiveMetadata = verifyHashes(output);
        boolean layered = "dynamic".equals(archiveMetadata.getProperty("strategy"));
        String sharedArchives = archiveMetadata.getProperty("runtime.sharedArchiveFile", archive.toString());
        check(Files.readString(generated.resolve("app-cds.classlist")).contains(FIXTURE.replace('.', '/')),
                "Application fixture absent from class list");
        Path loadLog = output.resolve("archive-load.log");
        new AppCdsProcessRunner().run(command(classpath, List.of("-Xshare:on", "-XX:SharedArchiveFile=" + sharedArchives,
                "-Xlog:class+load=info,cds=info"), offlineArgs), output, loadLog, 30);
        List<String> requiredClasses = new ArrayList<>(List.of(FIXTURE));
        if (!descriptor.isEmpty()) requiredClasses.addAll(List.of("com.reactor.rust.json.JsonBufferWriter", descriptor));
        for (String name : requiredClasses) {
            try (var lines = Files.lines(loadLog)) {
                check(lines.anyMatch(line -> line.contains(name + " source: shared objects file" + (layered ? " (top)" : ""))),
                        "-Xshare:on did not load required application/framework class from archive: " + name);
            }
        }
        if (layered) {
            try (var lines = Files.lines(loadLog)) {
                check(lines.anyMatch(line -> line.contains("java.lang.Object source: shared objects file") && !line.contains("(top)")),
                        "JDK base classes were not shared from the preserved base");
            }
        }
        Path corrupt = Files.writeString(output.resolve("corrupt.jsa"), "not a HotSpot archive");
        String basePrefix = layered ? archiveMetadata.getProperty("base.archive.path") + File.pathSeparator : "";
        expectFailure(command(classpath, List.of("-Xshare:on", "-XX:SharedArchiveFile=" + basePrefix + corrupt), offlineArgs),
                output, output.resolve("corrupt-archive.log"));
        Path changedJar = Files.copy(jar, output.resolve("changed fixture.jar"));
        Files.write(changedJar, new byte[]{0}, StandardOpenOption.APPEND);
        List<Path> changedClasspath = new ArrayList<>(classpath);
        changedClasspath.set(changedClasspath.size() - 1, changedJar);
        expectFailure(command(changedClasspath, List.of("-Xshare:on", "-XX:SharedArchiveFile=" + sharedArchives), offlineArgs),
                output, output.resolve("changed-classpath.log"));
        Path bounded = Files.createDirectory(output.resolve("bounded-training"));
        long start = System.nanoTime();
        try {
            mojo(bounded, classpath, List.of("--hang"), 1).execute();
            throw new AssertionError("Nonterminating training unexpectedly succeeded");
        } catch (MojoExecutionException expected) {
            check(expected.getMessage().contains("timed out"), "Expected timeout, not " + expected);
        }
        check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10), "Training cleanup exceeded 10 seconds");
        Path failed = bounded.resolve("target/reactor-appcds");
        check(!Files.exists(failed.resolve("app-cds.jsa")), "Timed-out training left archive");
        check(!Files.exists(failed.resolve("compatible-jvm.properties")), "Timed-out training published metadata");
        check(!Files.exists(failed.resolve(".generation.lock")), "Writer termination not confirmed");
        verifyHashes(output);
        Files.writeString(output.resolve("functional.properties"), "archiveLoadVerified=true\n"
                + "applicationClassShared=true\ncorruptArchiveRejected=true\nchangedClasspathRejected=true\n"
                + "boundedTrainingVerified=true\nperformanceAccepted=false\nframeworkClassesShared=" + !descriptor.isEmpty()
                + "\nframeworkDescriptor=" + descriptor + "\nbaseAndTopShared=" + layered + "\nfinalArchive=" + archive + "\n");
        System.out.println("APPCDS_FUNCTIONAL_OK finalArchive=" + archive);
        return archive;
    }

    static void rejectOpenJ9(Path output) throws Exception {
        requireEmaTech();
        String vm = System.getProperty("java.vm.name").toLowerCase(Locale.ROOT);
        check(Runtime.version().feature() == 21 && (vm.contains("openj9") || vm.contains("j9")),
                "Actual OpenJ9 Java 21 required; property spoofing is not evidence");
        output = Files.createDirectory(output.toAbsolutePath().normalize());
        writeRuntime(output);
        GenerateAppCdsMojo mojo = new GenerateAppCdsMojo(GenerateAppCdsMojo.JvmInfo::current,
                new AppCdsProcessRunner(builder -> { throw new AssertionError("OpenJ9 launched HotSpot training"); }));
        set(mojo, "enabled", true);
        Path untouched = output.resolve("must-not-exist");
        set(mojo, "buildDirectory", untouched.toFile());
        try {
            mojo.execute();
            throw new AssertionError("OpenJ9 was not rejected");
        } catch (MojoExecutionException expected) {
            check(expected.getMessage().contains("OpenJ9") && expected.getMessage().contains("-Xshareclasses"),
                    "Missing actionable SCC guidance");
            Files.writeString(output.resolve("rejection.log"), expected.getMessage());
        }
        new GenerateAppCdsMojo(() -> { throw new AssertionError("Disabled goal inspected JVM"); },
                new AppCdsProcessRunner(builder -> { throw new AssertionError("Disabled goal launched process"); })).execute();
        check(!Files.exists(untouched), "OpenJ9 rejection touched output");
        Files.writeString(output.resolve("functional.properties"), "openj9Rejected=true\ndisabledNoWork=true\n");
        System.out.println("APPCDS_OPENJ9_REJECTION_OK");
    }

    private static GenerateAppCdsMojo mojo(Path base, List<Path> classpath, List<String> args, int timeout) throws Exception {
        MavenProject project = new MavenProject() {
            @Override public List<String> getRuntimeClasspathElements() { return classpath.stream().map(Path::toString).toList(); }
        };
        project.setFile(base.resolve("pom.xml").toFile());
        Build build = new Build();
        build.setOutputDirectory(base.resolve("target/classes").toString());
        project.setBuild(build);
        var artifact = new DefaultArtifact("example", "appcds-offline-fixture", "1", "compile", "jar", null,
                new DefaultArtifactHandler("jar"));
        artifact.setFile(classpath.getFirst().toFile());
        project.setArtifact(artifact);
        GenerateAppCdsMojo mojo = new GenerateAppCdsMojo();
        set(mojo, "enabled", true);
        set(mojo, "project", project);
        set(mojo, "buildDirectory", base.resolve("target").toFile());
        set(mojo, "trainingMainClass", FIXTURE);
        set(mojo, "trainingArgs", args);
        set(mojo, "maxHeapMb", 128);
        set(mojo, "timeoutSeconds", timeout);
        return mojo;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static List<String> command(List<Path> classpath, List<String> options, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
        command.addAll(VM);
        command.addAll(options);
        command.addAll(List.of("-cp", String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList()), FIXTURE));
        command.addAll(args);
        return command;
    }

    private static void expectFailure(List<String> command, Path directory, Path log) throws Exception {
        try {
            new AppCdsProcessRunner().run(command, directory, log, 30);
            throw new AssertionError("Incompatible archive silently accepted: " + log);
        } catch (AppCdsProcessRunner.TerminationUnconfirmedException unsafe) {
            throw unsafe;
        } catch (MojoExecutionException expected) {
            check(expected.getMessage().contains("exit code"), "Expected rejection, not " + expected);
        }
    }

    private static void compare(Path generation, Path output, int pairs) throws Exception {
        GenerateAppCdsMojo.JvmInfo.current().requireHotSpot21();
        check(pairs >= 3 && pairs <= 10, "Require 3..10 fresh pairs");
        generation = generation.toRealPath();
        output = Files.createDirectory(output.toAbsolutePath().normalize());
        writeRuntime(output);
        Path archive = generation.resolve("target/reactor-appcds/app-cds.jsa");
        check(Files.readString(generation.resolve("functional.properties")).contains("applicationClassShared=true"),
                "Generate and verify the final archive first");
        Properties metadata = verifyHashes(generation);
        boolean layered = "dynamic".equals(metadata.getProperty("strategy"));
        String sharedArchives = metadata.getProperty("runtime.sharedArchiveFile", archive.toString());
        List<Path> classpath = metadataClasspath(metadata);
        Properties functional = new Properties();
        try (var reader = Files.newBufferedReader(generation.resolve("functional.properties"))) { functional.load(reader); }
        String descriptor = functional.getProperty("frameworkDescriptor", "");
        for (String key : List.of("java.runtime.version", "java.vm.version", "java.vm.name", "java.vendor", "os.name", "os.arch")) {
            check(System.getProperty(key).equals(metadata.getProperty(key)), "Generation/runtime mismatch: " + key);
        }
        Path csv = output.resolve("measurements.csv");
        Files.writeString(csv, "pair,mode,pid,startup_ns,rss_kib,pss_kib,private_kib,heap_used_bytes,heap_committed_bytes,validated_requests,errors,cgroup_before_bytes,cgroup_steady_bytes,cgroup_increment_bytes\n");
        for (int pair = 1; pair <= pairs; pair++) {
            // Alternate order; default JDK CDS is a necessary control, not an AppCDS result.
            List<String> order = layered
                    ? (pair % 2 == 1 ? List.of("default", "layered") : List.of("layered", "default"))
                    : (pair % 2 == 1 ? List.of("off", "appcds", "default") : List.of("default", "appcds", "off"));
            for (String mode : order) {
                verifyHashes(generation);
                measure(classpath, descriptor, sharedArchives, output, csv, pair, mode);
            }
        }
        Files.writeString(output.resolve("comparison.properties"), "freshPairs=" + pairs + "\n"
                + "fixtureOnly=true\nframeworkClassesIncluded=" + !descriptor.isEmpty()
                + "\nframeworkRealHttp=false\nframeworkJsonResponses=" + !descriptor.isEmpty()
                + "\nstrategy=" + metadata.getProperty("strategy", "static") + "\npageCacheCold=false\nperformanceAccepted=false\n"
                + "startupDefinition=process start to first valid HTTP ready response\n"
                + "cgroupDefinition=memory.current includes harness plus child and charged file cache; before and steady both preserved\n"
                + "memoryDefinition=child proc smaps_rollup after 512 validated sequential HTTP requests; no forced GC\n");
        System.out.println("APPCDS_COMPARISON_RECORDED " + csv);
    }

    private static void measure(List<Path> classpath, String descriptor, String sharedArchives, Path output, Path csv, int pair, String mode) throws Exception {
        String name = "pair-" + pair + "-" + mode;
        Path ready = output.resolve(name + ".ready");
        Path log = output.resolve(name + ".log");
        List<String> options = switch (mode) {
            case "off" -> List.of("-Xshare:off");
            case "default" -> List.of("-Xshare:on");
            case "appcds", "layered" -> List.of("-Xshare:on", "-XX:SharedArchiveFile=" + sharedArchives);
            default -> throw new AssertionError(mode);
        };
        ProcessBuilder builder = new ProcessBuilder(command(classpath, options, fixtureArgs(descriptor, List.of("--serve", ready.toString()))))
                .directory(output.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "IBM_JAVA_OPTIONS",
                "OPENJ9_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
        long cgroupBefore = Long.parseLong(Files.readString(Path.of("/sys/fs/cgroup/memory.current")).trim());
        long start = System.nanoTime();
        Process child = builder.start();
        try {
            child.getOutputStream().close();
            long deadline = start + TimeUnit.SECONDS.toNanos(20);
            int port = 0;
            while (System.nanoTime() < deadline) {
                check(child.isAlive(), "HTTP child exited before readiness: " + log);
                if (Files.isRegularFile(ready)) {
                    String value = Files.readString(ready);
                    if (!value.isBlank()) {
                        port = Integer.parseInt(value);
                        if ("APPCDS_READY".equals(http(port, "/ready"))) break;
                    }
                }
                Thread.sleep(5);
            }
            check(port > 0 && System.nanoTime() < deadline, "Readiness timed out: " + log);
            long startup = System.nanoTime() - start;
            for (int index = 0; index < 512; index++) check(BODY.equals(http(port, "/data")), "Invalid HTTP fixture response");
            Thread.sleep(1000);
            String heap = http(port, "/heap");
            String smaps = Files.readString(Path.of("/proc", Long.toString(child.pid()), "smaps_rollup"));
            String status = Files.readString(Path.of("/proc", Long.toString(child.pid()), "status"));
            long cgroupSteady = Long.parseLong(Files.readString(Path.of("/sys/fs/cgroup/memory.current")).trim());
            Files.writeString(output.resolve(name + ".memory.stat"), Files.readString(Path.of("/sys/fs/cgroup/memory.stat")));
            Files.writeString(output.resolve(name + ".smaps_rollup"), smaps);
            Files.writeString(output.resolve(name + ".status"), status);
            Files.writeString(csv, pair + "," + mode + "," + child.pid() + "," + startup + ","
                    + kib(smaps, "Rss:") + "," + kib(smaps, "Pss:") + ","
                    + (kib(smaps, "Private_Clean:") + kib(smaps, "Private_Dirty:")) + "," + heap + ",512,0,"
                    + cgroupBefore + "," + cgroupSteady + "," + (cgroupSteady - cgroupBefore) + "\n",
                    StandardOpenOption.APPEND);
        } finally {
            child.destroy();
            if (!child.waitFor(2, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                check(child.waitFor(2, TimeUnit.SECONDS), "HTTP child termination unconfirmed: " + child.pid());
            }
        }
    }

    private static String http(int port, String path) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            socket.setSoTimeout(1000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readNBytes(8192), StandardCharsets.US_ASCII);
            check(response.startsWith("HTTP/1.1 200 "), "HTTP status failure: " + response);
            int split = response.indexOf("\r\n\r\n");
            check(split >= 0, "Missing HTTP headers");
            return response.substring(split + 4);
        }
    }

    private static long kib(String smaps, String key) {
        return smaps.lines().filter(line -> line.startsWith(key)).map(line -> line.substring(key.length()).trim().split("\\s+")[0])
                .mapToLong(Long::parseLong).findFirst().orElseThrow(() -> new AssertionError("Missing " + key));
    }

    private static Properties verifyHashes(Path generation) throws Exception {
        Properties metadata = new Properties();
        try (var reader = Files.newBufferedReader(generation.resolve("target/reactor-appcds/compatible-jvm.properties"))) {
            metadata.load(reader);
        }
        check(sha256(generation.resolve("target/reactor-appcds/app-cds.jsa")).equals(metadata.getProperty("archive.sha256")),
                "Archive changed after generation");
        if ("dynamic".equals(metadata.getProperty("strategy"))) {
            check("true".equals(metadata.getProperty("archive.validated")), "Dynamic archive validation missing");
            check(sha256(Path.of(metadata.getProperty("base.archive.path"))).equals(metadata.getProperty("base.archive.sha256")),
                    "Default JDK base changed since generation");
        }
        List<Path> classpath = metadataClasspath(metadata);
        for (int index = 0; index < classpath.size(); index++) {
            check(sha256(classpath.get(index)).equals(metadata.getProperty("classpath." + index + ".sha256")),
                    "JAR changed after generation: " + classpath.get(index));
        }
        return metadata;
    }

    private static List<Path> metadataClasspath(Properties metadata) {
        List<Path> result = new ArrayList<>();
        for (int index = 0; metadata.containsKey("classpath." + index + ".path"); index++) {
            result.add(Path.of(metadata.getProperty("classpath." + index + ".path")));
        }
        check(!result.isEmpty(), "Missing ordered JAR classpath metadata");
        return result;
    }

    private static List<Path> frameworkJars() throws IOException {
        String value = System.getenv().getOrDefault("APPCDS_FRAMEWORK_JARS", "");
        List<Path> result = new ArrayList<>();
        if (value.isEmpty()) return result;
        for (String entry : value.split(Pattern.quote(File.pathSeparator), -1)) {
            Path jar = Path.of(entry);
            check(jar.isAbsolute() && Files.isRegularFile(jar) && entry.endsWith(".jar"),
                    "Supply explicit ordered absolute JAR paths, application JAR first; no wildcards/directories: " + entry);
            jar = jar.toRealPath();
            check(!result.contains(jar), "Duplicate framework JAR: " + jar);
            result.add(jar);
        }
        return result;
    }

    private static List<String> fixtureArgs(String descriptor, List<String> args) {
        if (descriptor.isEmpty()) return args;
        List<String> result = new ArrayList<>(List.of("--framework", descriptor));
        result.addAll(args);
        return result;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeRuntime(Path output) throws IOException {
        Properties values = new Properties();
        for (String key : List.of("java.home", "java.runtime.version", "java.vm.name", "java.vm.version", "java.vendor", "os.name", "os.arch")) {
            values.setProperty(key, System.getProperty(key));
        }
        values.setProperty("cluster", "EmaTech (parent attestation; environment is not cluster identity proof)");
        values.setProperty("pod", System.getenv().getOrDefault("HOSTNAME", "unknown"));
        try (var writer = Files.newBufferedWriter(output.resolve("runtime.properties"))) {
            values.store(writer, "AppCDS EmaTech evidence");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/** Development launcher for trusted plugin metadata; not a deployment portability layer. */
public final class AppCdsRun {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("<compatible-jvm.properties> <main> [args]");
        if (!args[1].matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw new IllegalArgumentException("Expected a binary main-class name");
        }
        List<String> command = command(load(Path.of(args[0])));
        command.add(args[1]);
        command.addAll(List.of(args).subList(2, args.length));
        System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }

    static Properties load(Path file) throws Exception {
        if (Files.size(file) > 1_048_576) throw new IllegalArgumentException("Metadata exceeds 1 MiB");
        Properties metadata = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            metadata.load(reader);
        }
        return metadata;
    }

    static List<String> command(Properties metadata) throws Exception {
        if (!Path.of(required(metadata, "java.home")).toRealPath()
                .equals(Path.of(System.getProperty("java.home")).toRealPath())) {
            throw new IllegalArgumentException("Use the same JDK path as Maven training");
        }
        for (String key : List.of("java.runtime.version", "java.vendor", "java.vm.name",
                "java.vm.version", "os.name", "os.arch")) {
            if (!required(metadata, key).equals(System.getProperty(key))) {
                throw new IllegalArgumentException("JDK/platform mismatch: " + key);
            }
        }
        if (!required(metadata, "java.vm.name").contains("Server VM")
                || Runtime.version().feature() != 21) {
            throw new IllegalArgumentException("HotSpot Server VM Java 21 required, not OpenJ9 SCC");
        }
        String archives = required(metadata, "runtime.sharedArchiveFile");
        String[] archiveFiles = archives.split(java.util.regex.Pattern.quote(File.pathSeparator));
        if (archiveFiles.length < 1 || archiveFiles.length > 2) {
            throw new IllegalArgumentException("Expected one static or two dynamic archives");
        }
        verify(Path.of(archiveFiles[archiveFiles.length - 1]), required(metadata, "archive.sha256"));
        if (archiveFiles.length == 2) verify(Path.of(archiveFiles[0]), required(metadata, "base.archive.sha256"));
        List<String> classpath = new ArrayList<>();
        for (int index = 0; index < 4096 && metadata.containsKey("classpath." + index + ".path"); index++) {
            Path jar = Path.of(required(metadata, "classpath." + index + ".path"));
            verify(jar, required(metadata, "classpath." + index + ".sha256"));
            classpath.add(jar.toString());
        }
        if (classpath.isEmpty() || metadata.containsKey("classpath.4096.path")) {
            throw new IllegalArgumentException("Expected 1..4096 ordered JARs");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(required(metadata, "java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java").toString());
        for (int index = 0; index < 16 && metadata.containsKey("vm.arg." + index); index++) {
            command.add(required(metadata, "vm.arg." + index));
        }
        command.addAll(List.of("-Xshare:on", "-XX:SharedArchiveFile=" + archives,
                "-Xlog:cds=info", "-cp", String.join(File.pathSeparator, classpath)));
        return command;
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void verify(Path file, String expected) throws Exception {
        if (!Files.isRegularFile(file) || !sha256(file).equals(expected)) {
            throw new IllegalArgumentException("Missing/changed training artifact: " + file);
        }
    }

    private static String required(Properties metadata, String key) {
        String value = metadata.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing metadata: " + key);
        return value;
    }
}

import java.io.File;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** Tests command construction and fail-closed checks only; does not launch an application. */
public final class AppCdsRunChecks {
    public static void main(String[] args) throws Exception {
        var metadata = new Properties();
        for (String key : List.of("java.home", "java.runtime.version", "java.vendor", "java.vm.name",
                "java.vm.version", "os.name", "os.arch")) metadata.setProperty(key, System.getProperty(key));
        if (!System.getProperty("java.vm.name").contains("Server VM") || Runtime.version().feature() != 21) {
            try {
                AppCdsRun.command(metadata);
                throw new AssertionError("unsupported VM accepted");
            } catch (IllegalArgumentException expected) {
                require("HotSpot Server VM Java 21 required, not OpenJ9 SCC".equals(expected.getMessage()),
                        "expected VM rejection, not an unrelated metadata failure: " + expected.getMessage());
            }
            System.out.println("AppCDS launcher rejection-only checks passed on "
                    + System.getProperty("java.vm.name") + " Java " + Runtime.version().feature()
                    + "; full command/hash checks NOT run; no real archive/image validation");
            return;
        }
        var work = Files.createTempDirectory("appcds-launch-check-");
        try {
            var archive = Files.writeString(work.resolve("test.jsa"), "archive fixture, not a real archive");
            var first = Files.writeString(work.resolve("first.jar"), "first fixture");
            var second = Files.writeString(work.resolve("second.jar"), "second fixture");
            metadata.setProperty("runtime.sharedArchiveFile", archive.toString());
            metadata.setProperty("archive.sha256", AppCdsRun.sha256(archive));
            metadata.setProperty("classpath.0.path", first.toString());
            metadata.setProperty("classpath.0.sha256", AppCdsRun.sha256(first));
            metadata.setProperty("classpath.1.path", second.toString());
            metadata.setProperty("classpath.1.sha256", AppCdsRun.sha256(second));
            metadata.setProperty("vm.arg.0", "-Xms32m");
            var command = AppCdsRun.command(metadata);
            require(command.get(command.size() - 1).equals(first + File.pathSeparator + second), "ordered classpath");
            require(command.contains("-Xshare:on") && command.contains("-Xms32m"), "strict sharing and VM args");
            var saved = work.resolve("compatible-jvm.properties");
            try (var writer = Files.newBufferedWriter(saved)) { metadata.store(writer, "test"); }
            require(AppCdsRun.command(AppCdsRun.load(saved)).equals(command), "properties escaping");
            metadata.setProperty("base.archive.sha256", AppCdsRun.sha256(first));
            metadata.setProperty("runtime.sharedArchiveFile", first + File.pathSeparator + archive);
            AppCdsRun.command(metadata);
            metadata.setProperty("os.arch", "not-the-training-platform");
            rejects(metadata);
            metadata.setProperty("os.arch", System.getProperty("os.arch"));
            Files.writeString(second, "changed fixture");
            rejects(metadata);
            System.out.println("AppCDS launcher checks passed (not real archive/image acceptance)");
        } finally {
            try (var paths = Files.walk(work)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void rejects(Properties metadata) throws Exception {
        try {
            AppCdsRun.command(metadata);
            throw new AssertionError("incompatible metadata/artifact accepted");
        } catch (IllegalArgumentException expected) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

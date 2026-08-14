import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.telemetry.GlowrootTelemetry;
import com.reactor.rust.telemetry.TelemetryProfile;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Exact-source lifecycle gate for the Glowroot runtime embedded in Rust-Java REST. */
public final class RestProfileSwitchProbe {

    private RestProfileSwitchProbe() {}

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace(System.err);
            System.err.flush();
            Runtime.getRuntime().halt(1);
        });
        if (args.length != 1) throw new IllegalArgumentException("native library path is required");
        System.setProperty("rust.lib.path", Path.of(args[0]).toAbsolutePath().toString());
        System.setProperty("reactor.native.capabilities", "http,glowroot");
        System.setProperty("reactor.websocket.enabled", "false");
        System.setProperty("reactor.static-files.enabled", "false");
        System.setProperty("reactor.glowroot.enabled", "true");
        System.setProperty("reactor.glowroot.profile", "micro");
        System.setProperty("reactor.glowroot.collector.address", "http://127.0.0.1:1");
        System.setProperty("reactor.glowroot.agent.id", "rest-profile-switch::probe");
        System.setProperty("reactor.glowroot.application.name", "rest-profile-switch-probe");
        PropertiesLoader.load();
        NativeBridge.configureRuntimeFromProperties();

        int port = NativeBridge.startHttpServerAndGetPort(0);
        try {
            require(port > 0, "server did not bind to an ephemeral port");
            require(GlowrootTelemetry.configuredProfile() == TelemetryProfile.MICRO,
                    "unexpected configured profile");
            GlowrootTelemetry.SqlStatement statement = GlowrootTelemetry.sql(
                    "customer.find",
                    "select id from customer where id = ?"
            );
            Method captureError = NativeBridge.class.getDeclaredMethod(
                    "captureGlowrootError",
                    Throwable.class
            );
            captureError.setAccessible(true);
            long initialRss = rssKb();
            for (int iteration = 0; iteration < 100; iteration++) {
                GlowrootTelemetry.switchTo(TelemetryProfile.FULL, Duration.ofSeconds(2));
                if (iteration == 0) {
                    String activeDiagnostics = GlowrootTelemetry.diagnosticsJson();
                    require(activeDiagnostics.contains("\"jvm_probe_registered\":true"), activeDiagnostics);
                    require(!activeDiagnostics.contains("\"jvm_probe_owned_global_refs\":0"), activeDiagnostics);
                    captureError.invoke(null, new HostileThrowable());
                }
                long started = statement.start();
                if ((iteration & 15) == 0) {
                    statement.recordFailure(started, new IllegalStateException("probe-" + iteration));
                } else {
                    statement.recordSuccess(started, 1L);
                }
                GlowrootTelemetry.restoreConfiguredProfile();
            }
            GlowrootTelemetry.switchTo(TelemetryProfile.DIAGNOSTIC, Duration.ofSeconds(2));
            String diagnosticProfile = GlowrootTelemetry.diagnosticsJson();
            require(diagnosticProfile.contains("\"jvm_probe_registered\":true"), diagnosticProfile);
            require(!diagnosticProfile.contains("\"jvm_probe_owned_global_refs\":0"), diagnosticProfile);
            Path diagnosticDirectory = Files.createTempDirectory("rust-rest-glowroot-");
            Path threadDump = diagnosticDirectory.resolve("threads.txt");
            long diagnosticId = GlowrootTelemetry.requestDiagnostic(
                    GlowrootTelemetry.DiagnosticOperation.THREAD_DUMP,
                    threadDump
            );
            awaitDiagnostic(diagnosticId, threadDump);
            Files.deleteIfExists(threadDump);
            Files.deleteIfExists(diagnosticDirectory);
            GlowrootTelemetry.restoreConfiguredProfile(Duration.ofSeconds(2));
            System.gc();
            Thread.sleep(500);
            String diagnostics = GlowrootTelemetry.diagnosticsJson();
            require(diagnostics.contains("\"isolated_exporter\":true"), diagnostics);
            require(diagnostics.contains("\"active_profile\":\"micro\""), diagnostics);
            require(diagnostics.contains("\"active_profile_memory_ceiling_bytes\":0"), diagnostics);
            require(diagnostics.contains("\"retired_profile_memory_ceiling_bytes\":0"), diagnostics);
            require(diagnostics.contains("\"profile_release_pending\":false"), diagnostics);
            require(diagnostics.contains("\"jvm_probe_registered\":false"), diagnostics);
            require(diagnostics.contains("\"jvm_probe_owned_global_refs\":0"), diagnostics);
            System.out.printf(
                    "rest_profile_switch port=%d initial_rss_kb=%d final_rss_kb=%d os_threads=%d diagnostics=%s%n",
                    port,
                    initialRss,
                    rssKb(),
                    osThreadCount(),
                    diagnostics
            );
        } finally {
            require(NativeBridge.stopHttpServer(), "native server did not stop cleanly");
        }
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static long rssKb() throws Exception {
        return Files.readAllLines(Path.of("/proc/self/status")).stream()
                .filter(line -> line.startsWith("VmRSS:"))
                .map(line -> line.replaceAll("[^0-9]", ""))
                .filter(value -> !value.isEmpty())
                .mapToLong(Long::parseLong)
                .findFirst()
                .orElse(-1L);
    }

    private static long osThreadCount() throws Exception {
        try (var tasks = Files.list(Path.of("/proc/self/task"))) {
            return tasks.count();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("REST profile gate failed: " + message);
    }

    private static void awaitDiagnostic(long diagnosticId, Path output) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String diagnostics = GlowrootTelemetry.diagnosticsJson();
            if (diagnostics.contains("\"last_diagnostic_id\":" + diagnosticId)) {
                require(Files.isRegularFile(output) && Files.size(output) > 0L, diagnostics);
                return;
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException(
                "REST native diagnostic did not complete: " + GlowrootTelemetry.diagnosticsJson()
        );
    }

    private static final class HostileThrowable extends RuntimeException {
        @Override
        public StackTraceElement[] getStackTrace() {
            throw new IllegalStateException("telemetry must clear this JNI exception");
        }
    }
}

package com.reactor.rust.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Golden bytes captured by compiling the pre-split processor, without loading native code. */
class ReactorStartupProcessorSplitTest {
    @TempDir Path directory;

    private static final Map<String, String> FIXTURES = Map.ofEntries(
            Map.entry("generated/split/Fixture.java", """
                    package generated.split;

                    import com.reactor.rust.annotations.*;
                    import com.reactor.rust.di.annotation.*;
                    import com.reactor.rust.exception.ExceptionHandler;

                    @ReactorApplication(name = "Split fixture", version = "3.2.1", description = "APT byte contract")
                    @Component
                    @RequiresProperty(name = "fixture.enabled", value = "true", matchIfMissing = true)
                    @Profile({"prod", "test"})
                    @RequestMapping("/items")
                    public class Fixture {
                        @RustProperty(value = "fixture.label", defaultValue = "hello")
                        String label;
                        private final Repository repository;
                        public Fixture(Repository repository) { this.repository = repository; }

                        @GetMapping("/{id}")
                        public String get(@PathVariable("id") long id,
                                          @RequestParam(value = "limit", defaultValue = "8") int limit,
                                          @HeaderParam("X-Trace") String trace) { return repository.value(); }

                        @PostMapping("/")
                        public Payload post(@RequestBody Payload body) { return body; }

                        @ExceptionHandler(IllegalArgumentException.class)
                        public String bad(IllegalArgumentException error) { return error.getMessage(); }

                        @PostConstruct public void start() {}
                        @PreDestroy public void stop() {}

                        @RequestMapping("/worker")
                        public static class Worker {
                            @GetMapping("/") public String status() { return "ready"; }
                        }
                    }

                    @Component
                    class Repository {
                        String value() { return "value"; }
                    }

                    @Configuration
                    class Wiring {
                        @Bean public Fixture.Worker worker() { return new Fixture.Worker(); }
                    }

                    @ConfigurationProperties("fixture")
                    record Settings(@ConfigDefault("8") int maxRows,
                                    @ConfigDefault("false") boolean strict,
                                    @ConfigDefault("a,b") java.util.List<String> labels) {}
                    """),
            Map.entry("generated/split/Payload.java", """
                    package generated.split;

                    import com.reactor.rust.annotations.*;

                    @Request
                    @Response
                    public record Payload(@NotBlank @Size(min = 1, max = 32) String name,
                                          @Min(1) @Max(100) int count,
                                          @Field(defaultValue = "ready", pattern = "[a-z]+") String state) {}
                    """),
            Map.entry("generated/split/Catalog.java", """
                    package generated.split;

                    import com.reactor.rust.http.client.*;
                    import com.reactor.rust.annotations.*;

                    @ReactorHttpClient(baseUrlProperty = "catalog.url")
                    @HttpCircuitBreaker(windowSize = 8, minimumCalls = 4)
                    public interface Catalog {
                        @HttpExchange(method = HttpMethod.GET, path = "/items/{id}")
                        java.util.concurrent.CompletionStage<String> get(@PathVariable("id") long id, @RequestParam("q") String query);
                    }
                    """));

    @Test
    void preservesGeneratedSourcesAndContractsForSwitchLegacyAndAuto() throws Exception {
        assertEquals(SWITCH_GOLDEN, compile("switch"));
        assertEquals(LEGACY_GOLDEN, compile("legacy"));
        assertEquals(SWITCH_GOLDEN, compile("auto"));
    }

    @Test
    void keepsOrchestratorSmallAndImplementationHelpersPackagePrivate() throws Exception {
        try (var lines = Files.lines(Path.of(
                "src/main/java/com/reactor/rust/codegen/ReactorStartupProcessor.java"))) {
            assertTrue(lines.count() < 500, "Round orchestrator must remain below 500 lines");
        }
        for (Class<?> helper : List.of(ComponentGraphScanner.class, RouteModelCollector.class,
                SwitchDispatchEmitter.class, ValidationCodeEmitter.class,
                HttpClientStubEmitter.class, PropertyMetadataEmitter.class)) {
            assertFalse(Modifier.isPublic(helper.getModifiers()), helper.getName());
            assertFalse(Processor.class.isAssignableFrom(helper), helper.getName());
        }
    }

    private Map<String, String> compile(String mode) throws Exception {
        Path root = Files.createDirectories(directory.resolve(mode));
        Path sources = Files.createDirectories(root.resolve("input"));
        Path generated = Files.createDirectories(root.resolve("generated"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        List<Path> inputs = new ArrayList<>();
        for (var fixture : new TreeMap<>(FIXTURES).entrySet()) {
            Path source = sources.resolve(fixture.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, fixture.getValue(), StandardCharsets.UTF_8);
            inputs.add(source);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, files, diagnostics, List.of(
                    "--release", "21", "-proc:full",
                    "-classpath", System.getProperty("java.class.path"),
                    "-sourcepath", Path.of("platform/starter-http-client/src/main/java")
                            + java.io.File.pathSeparator + sources,
                    "-Areactor.codegen.dispatch=" + mode,
                    "-d", classes.toString(), "-s", generated.toString()),
                    null, files.getJavaFileObjectsFromPaths(inputs));
            task.setProcessors(List.of(new ReactorStartupProcessor(), new ContractManifestProcessor()));
            assertTrue(task.call(), () -> mode + ": " + diagnostics.getDiagnostics());
        }
        Map<String, String> actual = new TreeMap<>();
        digestTree(actual, "source/", generated);
        digestTree(actual, "resource/META-INF/", classes.resolve("META-INF"));
        assertTrue(actual.containsKey("resource/META-INF/reactor/contracts.tsv"));
        assertTrue(actual.containsKey("source/generated/split/Catalog__ReactorHttpClient.java"));
        assertTrue(actual.containsKey("source/generated/split/Payload__ReactorValidator.java"));
        return actual;
    }

    private void digestTree(Map<String, String> output, String prefix, Path root) throws Exception {
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
                output.put(prefix + relative, HexFormat.of().formatHex(digest));
            }
        }
    }

    private static final Map<String, String> SWITCH_GOLDEN = Map.ofEntries(
            Map.entry("resource/META-INF/reactor/components.idx",
                    "84eadf1eaa61b94e7874227788ef20a3b30aeb8a30f0eb61f208b46b45702ad2"),
            Map.entry("resource/META-INF/reactor/configuration-metadata.json",
                    "0e931486e267a6978bfec0aeaa776dfdc2e18e59ba4997109cf7d01e214b388e"),
            Map.entry("resource/META-INF/reactor/contracts.tsv",
                    "71901355e4ccd630aba7b6d23930fc0599b07003996bfc2f2bab1cbf2244d6ea"),
            Map.entry("resource/META-INF/reactor/openapi.json",
                    "98578e5b543d97af7ff4a1ef4b696475e0e4c468961d3466cae9cd89b3b64963"),
            Map.entry("resource/META-INF/reactor/properties.idx",
                    "669ad6b689390c497aebd550bee11daebbf53711ed0f354a416683c85bd8a584"),
            Map.entry("resource/META-INF/reactor/routes.idx",
                    "0fb4abe7da85aedacdac88f2479066dc292d69d82ea3c819ddba17f090046751"),
            Map.entry("resource/META-INF/services/com.reactor.rust.startup.ApplicationDescriptor",
                    "6ce3b34ceb68983c8594172afb53f2c7306699c731f18ba14d5f4f74c88722cb"),
            Map.entry("source/com/reactor/generated/ReactorApplicationDescriptor__Dispatch.java",
                    "7ca8af9f9b0f68b513f5dc2db5e532d55c803b1f46e9fde66670a436017642b8"),
            Map.entry("source/com/reactor/generated/ReactorApplicationDescriptor.java",
                    "a88e994d8d24a0ff9ad123fd85355e254f8737df39467bce01f6527a9438cadf"),
            Map.entry("source/generated/split/Catalog__ReactorHttpClient.java",
                    "ca48499e6f833738521324631aea87f2ba68d3b9fb9fed710e8b7123b2b27f97"),
            Map.entry("source/generated/split/Fixture__ReactorFactory.java",
                    "cb97be3c5d647a3cc8f95dc608f870baaa61e376bccce41d3508512f878f4b77"),
            Map.entry("source/generated/split/Payload__ReactorValidator.java",
                    "a564c37b978411b4d3ccb834a1d4374257356558e6befdac5b5e15a9a220bd50"),
            Map.entry("source/generated/split/Repository__ReactorFactory.java",
                    "df0861008e13e82122632475d3744ad5c452b885f91274c48f48aed26196c46f"),
            Map.entry("source/generated/split/Settings__ReactorFactory.java",
                    "48efae74aad8aa0d9b4ba2af062f41df88a0eb59f976073499a27b7a59d7aec1"),
            Map.entry("source/generated/split/Wiring__ReactorFactory.java",
                    "cede5b5cdda67599a9d44950a9b4ddd2dd60fec3ebae6cafc1c6ff2a632e1fb5"));

    private static final Map<String, String> LEGACY_GOLDEN = Map.ofEntries(
            Map.entry("resource/META-INF/reactor/components.idx",
                    "84eadf1eaa61b94e7874227788ef20a3b30aeb8a30f0eb61f208b46b45702ad2"),
            Map.entry("resource/META-INF/reactor/configuration-metadata.json",
                    "0e931486e267a6978bfec0aeaa776dfdc2e18e59ba4997109cf7d01e214b388e"),
            Map.entry("resource/META-INF/reactor/contracts.tsv",
                    "71901355e4ccd630aba7b6d23930fc0599b07003996bfc2f2bab1cbf2244d6ea"),
            Map.entry("resource/META-INF/reactor/openapi.json",
                    "98578e5b543d97af7ff4a1ef4b696475e0e4c468961d3466cae9cd89b3b64963"),
            Map.entry("resource/META-INF/reactor/properties.idx",
                    "669ad6b689390c497aebd550bee11daebbf53711ed0f354a416683c85bd8a584"),
            Map.entry("resource/META-INF/reactor/routes.idx",
                    "0fb4abe7da85aedacdac88f2479066dc292d69d82ea3c819ddba17f090046751"),
            Map.entry("resource/META-INF/services/com.reactor.rust.startup.ApplicationDescriptor",
                    "6ce3b34ceb68983c8594172afb53f2c7306699c731f18ba14d5f4f74c88722cb"),
            Map.entry("source/com/reactor/generated/ReactorApplicationDescriptor.java",
                    "a88e994d8d24a0ff9ad123fd85355e254f8737df39467bce01f6527a9438cadf"),
            Map.entry("source/generated/split/Catalog__ReactorHttpClient.java",
                    "ca48499e6f833738521324631aea87f2ba68d3b9fb9fed710e8b7123b2b27f97"),
            Map.entry("source/generated/split/Fixture__ReactorFactory.java",
                    "b78ac03631594b95ef270e3106834280ada2fbe80330e62d888390a09ca3f3ae"),
            Map.entry("source/generated/split/Payload__ReactorValidator.java",
                    "a564c37b978411b4d3ccb834a1d4374257356558e6befdac5b5e15a9a220bd50"),
            Map.entry("source/generated/split/Repository__ReactorFactory.java",
                    "df0861008e13e82122632475d3744ad5c452b885f91274c48f48aed26196c46f"),
            Map.entry("source/generated/split/Settings__ReactorFactory.java",
                    "48efae74aad8aa0d9b4ba2af062f41df88a0eb59f976073499a27b7a59d7aec1"),
            Map.entry("source/generated/split/Wiring__ReactorFactory.java",
                    "790791ea495b81c15c55bf94a6b86a736dd3d2183f6026497c260af894cd3b8a"));
}

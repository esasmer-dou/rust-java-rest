package com.reactor.rust.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.reactor.rust.bridge.GeneratedRouteInvoker;
import com.reactor.rust.bridge.GeneratedRouteInvokers;
import com.reactor.rust.concurrent.LongKeyAdmission;
import com.reactor.rust.di.BeanContainer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Checks generated call sites only; ownership and cancellation belong to runtime tests. */
class DeclarativeRouteCodegenTest {
    @TempDir Path directory;
    private int compilation;

    // The optional Dubbo carrier is deliberately a source fixture: these tests load no native library.
    private static final String CARRIER = """
            package com.reactor.rust.dubbo;
            public record NativeResponseHandle(int nativeId, int statusCode, String contentType, String headers) {
                public static NativeResponseHandle json(int id) {
                    return new NativeResponseHandle(id, 200, "application/json", "");
                }
            }
            """;

    // Compile-only optional adapter: the owning Dubbo module verifies ownership/cancellation at runtime.
    private static final String ADAPTER = """
            package com.reactor.rust.dubbo.http;
            import com.reactor.rust.dubbo.NativeResponseHandle;
            import com.reactor.rust.http.RawResponse;
            import com.reactor.rust.http.ResponseEntity;
            import java.util.concurrent.CompletionStage;
            import java.util.concurrent.CompletableFuture;
            import java.util.function.Function;
            public final class DubboResponses {
                public static ResponseEntity<RawResponse> reply(NativeResponseHandle handle) {
                    return ResponseEntity.ok(null);
                }
                public static ResponseEntity<RawResponse> reply(ResponseEntity<NativeResponseHandle> entity) {
                    return ResponseEntity.ok(null);
                }
                public static <T> CompletableFuture<ResponseEntity<RawResponse>> replyAsync(
                        CompletionStage<T> source, Function<? super T, ResponseEntity<RawResponse>> mapper) {
                    throw new UnsupportedOperationException("compile-only fixture");
                }
            }
            """;

    private static final String HANDLER = """
            package generated.declarative;

            import com.reactor.rust.annotations.*;
            import com.reactor.rust.di.annotation.Component;
            import com.reactor.rust.http.ResponseEntity;
            import com.reactor.rust.dubbo.NativeResponseHandle;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.CompletionStage;

            @Component
            @RestController("/items")
            public class Handler {
                @GetMapping("/{id}")
                @KeyAdmission(bean = "commands", key = "customerId", onRejected = "rejectedAsync")
                public CompletableFuture<String> byId(@PathVariable("id") long customerId) {
                    return CompletableFuture.completedFuture("ready");
                }

                @PostMapping("/name")
                @KeyAdmission(bean = "names", key = "q", onRejected = "rejectedSync")
                public String byName(@RequestParam("q") String name) throws java.io.IOException {
                    return name;
                }

                @DeleteMapping("/{id}")
                @KeyAdmission(bean = "commands", key = "id", onRejected = "rejectedVoid")
                public void remove(@PathVariable("id") long id) {}

                public CompletableFuture<String> rejectedAsync(Throwable error) {
                    return CompletableFuture.completedFuture("async-rejected");
                }

                public String rejectedSync(Throwable error) throws java.io.IOException { return "sync-rejected"; }
                public void rejectedVoid(Throwable error) {}

                @GetMapping("/public/{id}")
                public String publicRead(@PathVariable("id") long id) { return "public"; }

                @GetMapping("/native")
                public NativeResponseHandle direct() { return NativeResponseHandle.json(1); }

                @GetMapping("/native-future")
                public CompletableFuture<NativeResponseHandle> future() {
                    return CompletableFuture.completedFuture(NativeResponseHandle.json(1));
                }

                @GetMapping("/native-stage")
                public CompletionStage<NativeResponseHandle> stage() { return future(); }

                @GetMapping("/native-entity")
                public ResponseEntity<NativeResponseHandle> entity() { return ResponseEntity.ok(direct()); }

                @GetMapping("/native-entity-future")
                public CompletableFuture<ResponseEntity<NativeResponseHandle>> entityFuture() {
                    return CompletableFuture.completedFuture(entity());
                }

                @GetMapping("/native/{id}")
                @KeyAdmission(bean = "commands", key = "id")
                public CompletableFuture<NativeResponseHandle> admittedNative(@PathVariable("id") long id) {
                    return future();
                }

                @GetMapping("/native-sync/{id}")
                @KeyAdmission(bean = "commands", key = "id")
                public NativeResponseHandle admittedSync(@PathVariable("id") long id) { return direct(); }

                @GetMapping("/native-entity/{name}")
                @KeyAdmission(bean = "names", key = "name")
                public ResponseEntity<NativeResponseHandle> admittedEntity(@PathVariable("name") String name) {
                    return entity();
                }
            }
            """;

    @Test
    void emitsBoundedAdmissionAndNativeReturnAdaptersInEveryDispatchMode() throws Exception {
        for (String mode : List.of("switch", "legacy", "auto")) {
            Result result = compile(mode, HANDLER);
            assertTrue(result.success(), () -> mode + ": " + result.diagnostics());
            String generated = result.generated();
            assertTrue(generated.contains("com.reactor.rust.concurrent.LongKeyAdmission.class, \"commands\""));
            assertTrue(generated.contains("com.reactor.rust.concurrent.LongKeyAdmission.class, \"names\""));
            assertTrue(generated.contains("if (container.hasBean(generated.declarative.Handler.class)) {"));
            assertTrue(generated.contains("KeyAdmissionSupport.invoke(keyAdmission,"));
            assertTrue(generated.contains("error -> ((generated.declarative.Handler) bean).rejectedAsync(error)"));
            assertTrue(generated.contains("error -> ((generated.declarative.Handler) bean).rejectedSync(error)"));
            assertTrue(generated.contains("error -> { ((generated.declarative.Handler) bean).rejectedVoid(error); return null; }"));
            assertTrue(generated.contains("() -> { ((generated.declarative.Handler) bean).remove("));
            assertTrue(generated.contains("DubboResponses.reply(((generated.declarative.Handler) bean).direct())"));
            assertTrue(generated.contains("DubboResponses.replyAsync(((generated.declarative.Handler) bean).entityFuture(),"));
            assertTrue(generated.contains("DubboResponses.replyAsync(com.reactor.rust.concurrent.KeyAdmissionSupport.invoke("));
            assertTrue(generated.contains("DubboResponses.reply(com.reactor.rust.concurrent.KeyAdmissionSupport.invoke("));
            assertTrue(generated.contains("com.reactor.rust.dubbo.http.DubboResponses::reply"));
            assertTrue(generated.contains("com.reactor.rust.http.RawResponse.class"));
            assertFalse(generated.contains("com.reactor.rust.dubbo.NativeResponseHandle.class"));
            assertFalse(generated.contains(".thenApply("));
            assertFalse(generated.contains("java.lang.reflect"));
            assertFalse(generated.contains("Proxy.newProxyInstance"));
            if (!mode.equals("legacy")) {
                assertTrue(generated.contains("class Route1LongKeyLong"));
                assertTrue(generated.contains("class Route1KeyString"));
            }
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void generatedInvokersMapRejectionAndHoldPermitsUntilSourceCompletionInBothModes() throws Throwable {
        String source = HANDLER.replace("public class Handler {", """
                public class Handler {
                    public final CompletableFuture<String> pending = new CompletableFuture<>();
                """).replace("return CompletableFuture.completedFuture(\"ready\");", "return pending;");
        for (String mode : List.of("switch", "legacy")) {
            Result result = compile(mode, source);
            assertTrue(result.success(), result.diagnostics());
            try (var loader = new URLClassLoader(new java.net.URL[]{result.classes().toUri().toURL()},
                    getClass().getClassLoader())) {
                Class handlerType = loader.loadClass("generated.declarative.Handler");
                Object handler = handlerType.getConstructor().newInstance();
                var pending = (CompletableFuture<String>) handlerType.getField("pending").get(handler);
                var admission = new LongKeyAdmission(true, 1, 1);
                var container = BeanContainer.create();
                container.registerBean(handlerType, handler, "handler");
                container.registerBean(LongKeyAdmission.class, admission, "commands");
                container.registerBean(LongKeyAdmission.class, admission, "names");
                var register = loader.loadClass("generated.declarative.Handler__ReactorFactory")
                        .getDeclaredMethod("registerRouteInvokers", BeanContainer.class);
                register.setAccessible(true); // Test-only access to the actual generated startup registration.
                register.invoke(null, container);
                var find = GeneratedRouteInvokers.class.getDeclaredMethod("find", java.lang.reflect.Method.class);
                find.setAccessible(true);
                var async = (GeneratedRouteInvoker) find.invoke(null, handlerType.getMethod("byId", long.class));
                var sync = (GeneratedRouteInvoker) find.invoke(null, handlerType.getMethod("byName", String.class));
                var remove = (GeneratedRouteInvoker) find.invoke(null, handlerType.getMethod("remove", long.class));
                var accepted = (CompletableFuture<String>) async.invoke1(handler, 42L);
                assertSame(pending, accepted);
                assertTrue(accepted.thenApply(String::length).cancel(false));
                assertEquals("async-rejected", ((CompletableFuture<?>) async.invoke1(handler, 42L)).join());
                assertEquals("sync-rejected", sync.invoke1(handler, "customer-42"));
                assertNull(remove.invokeLong(handler, 42L));
                assertFalse(pending.isDone());
                assertTrue(admission.metricsJson().contains("\"rejected\":3"));
                pending.complete("ready");
                assertEquals("released", admission.execute(42L,
                        () -> CompletableFuture.completedFuture("released")).join());
            } finally {
                GeneratedRouteInvokers.releaseStartupMetadata();
            }
        }
    }

    @Test
    void rejectsMissingBlankAmbiguousAndUnsupportedKeys() throws Exception {
        reject("@KeyAdmission(bean = \"\", key = \"id\")", "@PathVariable(\"id\") long id",
                "@KeyAdmission bean and key must not be blank");
        reject("@KeyAdmission(bean = \"commands\", key = \"\")", "@PathVariable(\"id\") long id",
                "@KeyAdmission bean and key must not be blank");
        reject("@KeyAdmission(bean = \"commands\", key = \"missing\")", "@PathVariable(\"id\") long id",
                "@KeyAdmission key must name a @PathVariable or @RequestParam binding");
        reject("@KeyAdmission(bean = \"commands\", key = \"id\")", "@HeaderParam(\"id\") long id",
                "@KeyAdmission key must name a @PathVariable or @RequestParam binding");
        reject("@KeyAdmission(bean = \"commands\", key = \"id\")", "@PathVariable(\"id\") int id",
                "@KeyAdmission key must have type long or String");
        reject("@KeyAdmission(bean = \"commands\", key = \"id\")",
                "@PathVariable(\"id\") @HeaderParam(\"header\") long id",
                "@KeyAdmission key requires a direct path/query binding");
        reject("@KeyAdmission(bean = \"commands\", key = \"id\")",
                "java.nio.ByteBuffer buffer, int length, @PathVariable(\"id\") long id",
                "@KeyAdmission does not support runtime-direct borrowed ByteBuffer routes");
        reject("@KeyAdmission(bean = \"commands\", key = \"id\")",
                "@PathVariable(\"id\") long id, @RequestParam(\"id\") String query",
                "@KeyAdmission key must identify exactly one path/query binding");
    }

    @Test
    void rejectsAdmissionOnNonRouteMethods() throws Exception {
        String source = """
                package generated.declarative;
                import com.reactor.rust.annotations.*;
                import com.reactor.rust.di.annotation.Component;
                @Component
                public class Handler {
                    @KeyAdmission(bean = "commands", key = "id")
                    public String notARoute(@PathVariable("id") long id) { return "ready"; }
                }
                """;
        Result result = compile("legacy", source);
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("@KeyAdmission requires an HTTP route mapping"));
    }

    @Test
    void rejectsMissingAmbiguousInaccessibleStaticGenericAndIncompatibleRejectionMappers() throws Exception {
        String unique = "@KeyAdmission onRejected must name exactly one handler method";
        String signature = "@KeyAdmission onRejected requires a public instance method with one Throwable parameter";
        String returns = "@KeyAdmission onRejected return type must be assignable to the route return type";
        Map<String, String> cases = Map.ofEntries(
                Map.entry("", unique),
                Map.entry("public String rejected(Throwable error) { return null; } public String rejected() { return null; }", unique),
                Map.entry("private String rejected(Throwable error) { return null; }", signature),
                Map.entry("public static String rejected(Throwable error) { return null; }", signature),
                Map.entry("public <T> String rejected(Throwable error) { return null; }", signature),
                Map.entry("public String rejected(Exception error) { return null; }", signature),
                Map.entry("public String rejected() { return null; }", signature),
                Map.entry("public String rejected(Throwable error, long id) { return null; }", signature),
                Map.entry("public int rejected(Throwable error) { return 503; }", returns),
                Map.entry("public void rejected(Throwable error) {}", returns));
        for (var invalid : cases.entrySet()) {
            String source = """
                    package generated.declarative;
                    import com.reactor.rust.annotations.*;
                    import com.reactor.rust.di.annotation.Component;
                    @Component @RestController("/items")
                    public class Handler {
                        @GetMapping("/{id}")
                        @KeyAdmission(bean = "commands", key = "id", onRejected = "rejected")
                        public String read(@PathVariable("id") long id) { return "ready"; }
                        %s
                    }
                    """.formatted(invalid.getKey());
            for (String mode : List.of("switch", "legacy")) {
                Result result = compile(mode, source);
                assertFalse(result.success(), mode + ": " + invalid.getKey());
                assertTrue(result.diagnostics().contains(invalid.getValue()), result.diagnostics());
            }
        }
        String asyncMismatch = HANDLER.replace(
                "public CompletableFuture<String> rejectedAsync(Throwable error)",
                "public CompletableFuture<Integer> rejectedAsync(Throwable error)")
                .replace("CompletableFuture.completedFuture(\"async-rejected\")", "CompletableFuture.completedFuture(503)");
        for (String mode : List.of("switch", "legacy")) {
            Result result = compile(mode, asyncMismatch);
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains(returns), result.diagnostics());
        }
    }

    private void reject(String annotation, String parameters, String expected) throws Exception {
        String source = """
                package generated.declarative;
                import com.reactor.rust.annotations.*;
                import com.reactor.rust.di.annotation.Component;
                @Component
                @RestController("/items")
                public class Handler {
                    @GetMapping("/{id}")
                    %s
                    public String read(%s) { return "ready"; }
                }
                """.formatted(annotation, parameters);
        for (String mode : List.of("switch", "legacy", "auto")) {
            Result result = compile(mode, source);
            assertFalse(result.success(), mode);
            assertTrue(result.diagnostics().contains(expected), result.diagnostics());
        }
    }

    private Result compile(String mode, String handler) throws Exception {
        Path root = Files.createDirectories(directory.resolve(mode + "-" + compilation++));
        Path input = Files.createDirectories(root.resolve("input"));
        Path generated = Files.createDirectories(root.resolve("generated"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        List<Path> inputs = new ArrayList<>();
        for (var source : Map.of("generated/declarative/Handler.java", handler,
                "com/reactor/rust/dubbo/NativeResponseHandle.java", CARRIER,
                "com/reactor/rust/dubbo/http/DubboResponses.java", ADAPTER).entrySet()) {
            Path path = input.resolve(source.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, source.getValue(), StandardCharsets.UTF_8);
            inputs.add(path);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        boolean success;
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, files, diagnostics, List.of("--release", "21", "-proc:full",
                    "-classpath", System.getProperty("java.class.path"),
                    "-Areactor.codegen.dispatch=" + mode, "-d", classes.toString(), "-s", generated.toString()),
                    null, files.getJavaFileObjectsFromPaths(inputs));
            task.setProcessors(List.of(new ReactorStartupProcessor()));
            success = task.call();
        }
        StringBuilder text = new StringBuilder();
        try (var files = Files.walk(generated)) {
            for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                text.append(Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return new Result(success, diagnostics.getDiagnostics().stream()
                .map(Diagnostic::toString).collect(java.util.stream.Collectors.joining("\n")), text.toString(), classes);
    }

    private record Result(boolean success, String diagnostics, String generated, Path classes) {}
}

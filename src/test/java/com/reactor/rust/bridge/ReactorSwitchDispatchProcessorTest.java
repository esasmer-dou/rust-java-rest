package com.reactor.rust.bridge;

import com.reactor.rust.codegen.ReactorStartupProcessor;
import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.exception.ExceptionHandlerRegistry;
import com.reactor.rust.startup.ApplicationDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ReactorSwitchDispatchProcessorTest {
    @TempDir Path temporary;

    private static final String GOLDEN = """
            package dispatch.fixture;
            @com.reactor.rust.di.annotation.Component
            public class Golden {
                @com.reactor.rust.annotations.GetMapping("/ping")
                public String ping() { return "pong"; }
                @com.reactor.rust.annotations.GetMapping("/id")
                public String id(@com.reactor.rust.annotations.RequestParam("id") int id) { return "id"; }
                @com.reactor.rust.exception.ExceptionHandler(IllegalStateException.class)
                public String error(IllegalStateException error) { return "error"; }
            }
            """;

    @AfterEach
    void clearStartupMetadata() {
        GeneratedRouteInvokers.releaseStartupMetadata();
        GeneratedPrimitiveBindings.releaseStartupMetadata();
        MethodMetadata.clearCache();
    }

    @Test
    void legacyOutputMatchesPreSwitchGoldenByteForByte() throws Exception {
        var baseline = compile("golden", Map.of("dispatch/fixture/Golden.java", GOLDEN), "legacy", true);
        Map<String, String> fingerprints = fingerprints(baseline.generated());
        assertEquals(Map.of(
                "com/reactor/generated/ReactorApplicationDescriptor.java", "a12cea8c1f12d2d536474f786efd19d43d788baf8ebb4ae677ca63b31cdd03d9",
                "dispatch/fixture/Golden__ReactorFactory.java", "951d0485599f06c08f160b07ee1c05b7b8d60ffd788fa2173628aa1b3b1be022"), fingerprints);
    }

    @Test
    void defaultAndExplicitAutoUseSwitchForSupportedRoutesWithoutChangingResources() throws Exception {
        var files = Map.of("dispatch/fixture/Golden.java", GOLDEN);
        var defaultMode = compile("default", files, null, true);
        var auto = compile("auto", files, "auto", true);
        var strict = compile("strict", files, "switch", true);
        var legacy = compile("legacy", files, "legacy", true);
        assertEquals(fingerprints(defaultMode.generated()), fingerprints(auto.generated()));
        assertEquals(fingerprints(defaultMode.generated()), fingerprints(strict.generated()));
        assertEquals(resources(defaultMode.classes()), resources(legacy.classes()));
        assertTrue(defaultMode.diagnostics().contains("switch routes=2, legacy routes=0"));
        String dispatch = dispatchSource(defaultMode);
        assertNoDispatchForwarders(dispatch);
        assertDirectSwitchCall(adapterSource(dispatch, "Route0"), "invoke0(Object bean)",
                "return ((dispatch.fixture.Golden) bean).ping();");
        assertDirectSwitchCall(adapterSource(dispatch, "ExceptionAdapter"), "invoke(Object bean, Throwable error)",
                "return ((dispatch.fixture.Golden) bean).error((java.lang.IllegalStateException) error);");
    }

    @Test
    void defaultPreservesPackagePrivateGenericAndBeanRegistrationWithDirectCalls() throws Throwable {
        String source = controller("Mixed", """
                static class Hidden {}
                @GetMapping("/visible") public String visible() { return "visible"; }
                @GetMapping("/local") String local() { return "local"; }
                @GetMapping("/generic") public <T> String generic() { return "generic"; }
                @PostMapping("/hidden") public String hidden(@RequestBody Hidden value) { return "hidden"; }
                @GetMapping("/failure") public String failure() { throw new IllegalStateException("failure"); }
                @com.reactor.rust.exception.ExceptionHandler(IllegalStateException.class)
                String recover(IllegalStateException error) { return "recovered"; }
                """) + """
                @com.reactor.rust.di.annotation.Component
                final class LocalOwner {
                    @GetMapping("/owner") public String call() { return "owner"; }
                }
                final class LocalEndpoint {
                    @GetMapping("/bean") String call() { return "bean"; }
                }
                @com.reactor.rust.di.annotation.Configuration
                final class LocalConfiguration {
                    @com.reactor.rust.di.annotation.Bean LocalEndpoint endpoint() { return new LocalEndpoint(); }
                }
                """;
        var files = Map.of("dispatch/fixture/Mixed.java", source);
        var legacy = compile("mixedLegacy", files, "legacy", true);
        var output = compile("mixedAuto", files, null, true);
        assertEquals(resources(legacy.classes()), resources(output.classes()));
        assertTrue(output.diagnostics().contains("no reflection fallback"));
        assertTrue(output.diagnostics().contains("switch routes=2, legacy routes=5"), output.diagnostics());
        String dispatch = Files.readString(output.generated().resolve("com/reactor/generated/ReactorApplicationDescriptor__Dispatch.java"));
        assertFalse(dispatch.contains(".local("));
        assertFalse(dispatch.contains(".generic("));
        assertFalse(dispatch.contains(".hidden("));
        assertFalse(dispatch.contains("ExceptionAdapter"));
        assertFalse(dispatch.contains("java.lang.reflect"));
        try (var fixture = fixture(output, "Mixed")) {
            for (String name : List.of("visible", "local", "generic")) {
                var invoker = GeneratedRouteInvokers.find(fixture.owner().getDeclaredMethod(name));
                assertEquals(name, invoker.invoke0(fixture.bean()));
            }
            Class<?> hidden = fixture.loader().loadClass("dispatch.fixture.Mixed$Hidden");
            assertEquals("hidden", GeneratedRouteInvokers.find(fixture.owner().getDeclaredMethod("hidden", hidden))
                    .invoke1(fixture.bean(), null));
            for (String name : List.of("LocalOwner", "LocalEndpoint")) {
                Class<?> owner = fixture.loader().loadClass("dispatch.fixture." + name);
                assertEquals(name.equals("LocalOwner") ? "owner" : "bean",
                        GeneratedRouteInvokers.find(owner.getDeclaredMethod("call"))
                                .invoke0(fixture.container().getBean(owner)));
            }
            ByteBuffer out = ByteBuffer.allocate(2048);
            fixture.handlers().invokeBuffered(fixture.ids().get("failure"), out, 0, new byte[0], "", "", "");
            assertTrue(frameBody(out).contains("recovered"));
        }
        var strict = compile("mixedStrict", files, "switch", false);
        assertTrue(strict.diagnostics().contains("use -Areactor.codegen.dispatch=legacy"));
    }

    @Test
    void entirelyUnsupportedDefaultRetainsLegacyOutputByteForByte() throws Exception {
        var files = Map.of("dispatch/fixture/Local.java", controller("Local",
                "@GetMapping(\"/local\") String local() { return \"ok\"; }").replace("public class Local", "class Local"));
        var legacy = compile("localLegacy", files, "legacy", true);
        var output = compile("localAuto", files, null, true);
        assertEquals(fingerprints(legacy.generated()), fingerprints(output.generated()));
        assertEquals(resources(legacy.classes()), resources(output.classes()));
        assertTrue(output.diagnostics().contains("switch routes=0, legacy routes=1"));
    }

    @Test
    void tableLimitDefaultUsesBoundedSwitchAndGeneratedLegacyOverflow() throws Throwable {
        StringBuilder methods = new StringBuilder();
        for (int i = 0; i < 257; i++) methods.append("@GetMapping(\"/r").append(i)
                .append("\") public String r").append(i).append("() { return \"r").append(i).append("\"; }\n");
        var files = Map.of("dispatch/fixture/Large.java", controller("Large", methods.toString()));
        var output = compile("overflowAuto", files, null, true);
        var legacy = compile("overflowLegacy", files, "legacy", true);
        assertEquals(resources(legacy.classes()), resources(output.classes()));
        assertTrue(output.diagnostics().contains("switch routes=256, legacy routes=1"), output.diagnostics());
        try (var fixture = fixture(output, "Large")) {
            int shared = 0;
            for (int i = 0; i < 257; i++) {
                var invoker = invoker(fixture, "r" + i);
                assertEquals("r" + i, invoker.invoke0(fixture.bean()));
                if (invoker.getClass().getName().contains("__Dispatch$")) shared++;
            }
            assertEquals(256, shared);
        }
    }

    @Test
    void existingDirectScalarIntSampleSignatureCompilesWithoutChangingItsRuntimeAbi() throws Exception {
        var files = Map.of("dispatch/fixture/Direct.java", controller("Direct", """
                @GetMapping("/rows") @DirectQueryInt(value="count", defaultValue=32, min=0, max=10000)
                public com.reactor.rust.json.JsonBodyProducer rows(int count) {
                    return (out, offset) -> com.reactor.rust.json.JsonBufferWriter.reusable(out, offset).number(count).result();
                }
                """));
        for (String mode : List.of("legacy", "switch", "auto")) {
            var output = compile("direct" + mode, files, mode, true);
            try (var fixture = fixture(output, "Direct")) {
                assertNull(GeneratedPrimitiveBindings.find(fixture.owner().getMethod("rows", int.class)));
                ByteBuffer out = ByteBuffer.allocate(2048);
                fixture.handlers().invokeBufferedQueryInt(fixture.ids().get("rows"), out, 0, 32);
                assertEquals("32", frameBody(out));
            }
        }
    }

    @Test
    void switchCompilesAndInvokesEveryBoxedArityAndPrimitiveAbiLikeLegacy() throws Throwable {
        StringBuilder methods = new StringBuilder();
        for (int arity = 0; arity <= 8; arity++) {
            methods.append("@GetMapping(\"/a").append(arity).append("\") public Object a").append(arity).append('(');
            for (int i = 0; i < arity; i++) {
                if (i > 0) methods.append(',');
                methods.append("@RequestParam(\"p").append(i).append("\") String p").append(i);
            }
            methods.append(") { return TOKEN; }\n");
        }
        for (String primitive : List.of("int", "long", "boolean", "double", "short")) {
            methods.append("@GetMapping(\"/").append(primitive).append("\") public Object ").append(primitive)
                    .append("Value(@RequestParam(\"v\") ").append(primitive).append(" value) { return TOKEN; }\n");
        }
        String source = controller("Arities", "public static final Object TOKEN = new Object();\n" + methods);
        for (String mode : List.of("legacy", "switch")) {
            Compilation output = compile(mode, Map.of("dispatch/fixture/Arities.java", source), mode, true);
            if (mode.equals("switch")) {
                String dispatch = dispatchSource(output);
                assertNoDispatchForwarders(dispatch);
                for (int arity = 0; arity <= 8; arity++) {
                    assertDirectSwitchCall(adapterSource(dispatch, "Route" + arity), "invoke" + arity + "(Object bean",
                            "return ((dispatch.fixture.Arities) bean).a" + arity + "(");
                }
                for (String primitive : List.of("int", "long", "boolean", "double", "short")) {
                    String suffix = Character.toUpperCase(primitive.charAt(0)) + primitive.substring(1);
                    String adapter = adapterSource(dispatch, "Route1" + suffix);
                    assertDirectSwitchCall(adapter, "invoke1(Object bean, Object arg0)",
                            "return ((dispatch.fixture.Arities) bean)." + primitive + "Value(");
                    String body = assertDirectSwitchCall(adapter, "invoke" + suffix + "(Object bean, " + primitive + " value)",
                            "return ((dispatch.fixture.Arities) bean)." + primitive + "Value(value);");
                    assertFalse(body.contains(".valueOf("), body);
                    assertFalse(body.contains("arg0"), body);
                    assertFalse(adapter.contains(").a1("), adapter);
                }
            }
            try (var fixture = fixture(output, "Arities")) {
                Object token = fixture.owner().getField("TOKEN").get(null);
                for (int arity = 0; arity <= 8; arity++) {
                    Class<?>[] types = new Class<?>[arity];
                    java.util.Arrays.fill(types, String.class);
                    var method = fixture.owner().getMethod("a" + arity, types);
                    Object[] values = new Object[arity];
                    java.util.Arrays.fill(values, "value");
                    assertSame(token, invoke(GeneratedRouteInvokers.find(method), fixture.bean(), values));
                }
                assertSame(token, invoker(fixture, "intValue", int.class).invokeInt(fixture.bean(), 1000));
                assertSame(token, invoker(fixture, "longValue", long.class).invokeLong(fixture.bean(), Long.MAX_VALUE));
                assertSame(token, invoker(fixture, "booleanValue", boolean.class).invokeBoolean(fixture.bean(), true));
                assertSame(token, invoker(fixture, "doubleValue", double.class).invokeDouble(fixture.bean(), 1.5));
                assertSame(token, invoker(fixture, "shortValue", short.class).invokeShort(fixture.bean(), Short.MAX_VALUE));
                assertSame(token, invoker(fixture, "intValue", int.class).invoke1(fixture.bean(), 1000));
                assertSame(token, invoker(fixture, "longValue", long.class).invoke1(fixture.bean(), Long.MAX_VALUE));
                assertSame(token, invoker(fixture, "booleanValue", boolean.class).invoke1(fixture.bean(), true));
                assertSame(token, invoker(fixture, "doubleValue", double.class).invoke1(fixture.bean(), 1.5));
                assertSame(token, invoker(fixture, "shortValue", short.class).invoke1(fixture.bean(), Short.MAX_VALUE));
            }
        }
    }

    @Test
    void generatedRouteClassesAreConstantForOneVersusSixtyFourRoutes() throws Exception {
        for (int count : new int[]{1, 64}) {
            StringBuilder methods = new StringBuilder();
            for (int i = 0; i < count; i++) methods.append("@GetMapping(\"/r").append(i)
                    .append("\") public String r").append(i).append("() { return \"ok\"; }\n");
            var sources = Map.of("dispatch/fixture/Many.java", controller("Many", methods.toString()));
            var legacy = compile("legacy" + count, sources, "legacy", true);
            var candidate = compile("switch" + count, sources, "switch", true);
            assertEquals(count + 3, classCount(legacy.classes()));
            assertEquals(5, classCount(candidate.classes()));
            String dispatch = Files.readString(candidate.generated().resolve("com/reactor/generated/ReactorApplicationDescriptor__Dispatch.java"));
            assertNoDispatchForwarders(dispatch);
            String adapterSource = adapterSource(dispatch, "Route0");
            for (int i = 0; i < count; i++) {
                assertDirectSwitchCall(adapterSource, "invoke0(Object bean)",
                        "return ((dispatch.fixture.Many) bean).r" + i + "();");
            }
            assertFalse(dispatch.contains("Object[]"));
            assertFalse(dispatch.contains("java.lang.reflect"));
            assertFalse(dispatch.contains("Map<"));
            byte[] adapter = Files.readAllBytes(candidate.classes().resolve("com/reactor/generated/ReactorApplicationDescriptor__Dispatch$Route0.class"));
            String bytecode = new String(adapter, StandardCharsets.ISO_8859_1);
            assertFalse(bytecode.contains("LambdaMetafactory"));
            assertFalse(bytecode.contains("dispatch0"));
            assertTrue(bytecode.contains("dispatch/fixture/Many"));
            String factory = Files.readString(candidate.generated().resolve("dispatch/fixture/Many__ReactorFactory.java"));
            assertFalse(factory.contains("new com.reactor.rust.bridge.GeneratedRouteInvoker()"));
        }
    }

    @Test
    void adapterConstructorsRejectWrongRouteIdsAndAritiesWithoutStaticForwarders() throws Exception {
        var output = compile("ids", Map.of("dispatch/fixture/Golden.java", GOLDEN), "switch", true);
        try (var fixture = fixture(output, "Golden")) {
            var invoker = invoker(fixture, "id", int.class);
            int id = routeId(invoker);
            Class<?> route0 = fixture.loader().loadClass("com.reactor.generated.ReactorApplicationDescriptor__Dispatch$Route0");
            for (int bad : new int[]{-1, Integer.MAX_VALUE, id}) {
                var failure = assertThrows(InvocationTargetException.class, () -> route0.getConstructor(int.class).newInstance(bad));
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            }
            int pingId = routeId(invoker(fixture, "ping"));
            for (int bad : new int[]{-1, Integer.MAX_VALUE, pingId}) {
                var failure = assertThrows(InvocationTargetException.class,
                        () -> invoker.getClass().getConstructor(int.class).newInstance(bad));
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            }
            Class<?> exceptionAdapter = fixture.loader().loadClass(
                    "com.reactor.generated.ReactorApplicationDescriptor__Dispatch$ExceptionAdapter");
            for (int bad : new int[]{-1, Integer.MAX_VALUE, 1}) {
                var failure = assertThrows(InvocationTargetException.class,
                        () -> exceptionAdapter.getConstructor(int.class).newInstance(bad));
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            }
            Class<?> dispatch = fixture.loader().loadClass("com.reactor.generated.ReactorApplicationDescriptor__Dispatch");
            for (var method : dispatch.getDeclaredMethods()) assertFalse(method.getName().startsWith("dispatch"));
            assertThrows(IllegalStateException.class, () -> invoker.invoke0(fixture.bean()));
            assertThrows(ClassCastException.class, () -> invoker.invoke1(new Object(), 1));
            assertThrows(NullPointerException.class, () -> invoker.invoke1(fixture.bean(), null));
        }
    }

    @Test
    void runtimeFramesBindingGuardsAsyncDirectBuffersAndLifecycleMatchLegacy() throws Exception {
        String source = controller("Cases", """
                public static int started, stopped;
                @com.reactor.rust.di.annotation.PostConstruct public void start() { started++; }
                @com.reactor.rust.di.annotation.PreDestroy public void stop() { stopped++; }
                @GetMapping("/sum/{id}") @RouteAdmission(maxConcurrent=2, queueTimeoutMs=0)
                public long sum(@PathVariable("id") long id, @RequestParam("n") int n) { return id + n; }
                @GetMapping("/defaults") public long defaults(@RequestParam(value="n", defaultValue="17") Long n) { return n; }
                @GetMapping("/primitive") public long primitive(@RequestParam(value="n", defaultValue="17") long n) { return n; }
                @GetMapping("/headers") public String headers(@HeaderParam("X-Tag") String tag, @CookieValue("city") String city) { return tag + city; }
                @PostMapping("/body") public String body(@RequestBody String body) { return body; }
                @PostMapping("/buffer") public String buffer(@RequestBody java.nio.ByteBuffer body) { return "buffer" + body.remaining(); }
                @GetMapping("/wrapped") public com.reactor.rust.http.ResponseEntity<String> wrapped() {
                    return com.reactor.rust.http.ResponseEntity.created("wrapped").header("X-Tag", "ok");
                }
                @GetMapping("/empty") public void empty() {}
                @GetMapping("/async") public java.util.concurrent.CompletionStage<String> async(@RequestParam("n") int n) {
                    return java.util.concurrent.CompletableFuture.completedFuture("async" + n);
                }
                @GetMapping("/failed") public java.util.concurrent.CompletionStage<String> failed() {
                    return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("failed"));
                }
                @GetMapping("/throws") public String checked() throws java.io.IOException { throw new java.io.IOException("checked"); }
                @com.reactor.rust.exception.ExceptionHandler(java.io.IOException.class)
                public String recover(java.io.IOException error) { return "recovered"; }
                @RustRoute(path="/direct", method="GET", requestType=Void.class, responseType=Void.class)
                public int direct(java.nio.ByteBuffer out, int offset,
                        java.nio.ByteBuffer in, int length, String path, String query, String headers) {
                    out.put(offset, (byte) 'Q'); return 1;
                }
                """);
        List<byte[]> baseline = null;
        for (String mode : List.of("legacy", "switch")) {
            Compilation output = compile(mode, Map.of("dispatch/fixture/Cases.java", source), mode, true);
            if (mode.equals("switch")) {
                assertTrue(output.diagnostics().contains("retains the existing runtime direct-buffer ABI"));
                String dispatch = dispatchSource(output);
                assertNoDispatchForwarders(dispatch);
                String route0 = adapterSource(dispatch, "Route0");
                assertDirectSwitchCall(route0, "invoke0(Object bean)", "((dispatch.fixture.Cases) bean).empty(); return null;");
                assertDirectSwitchCall(route0, "invoke0(Object bean)", "return ((dispatch.fixture.Cases) bean).checked();");
                assertDirectSwitchCall(adapterSource(dispatch, "ExceptionAdapter"), "invoke(Object bean, Throwable error)",
                        "return ((dispatch.fixture.Cases) bean).recover((java.io.IOException) error);");
            }
            List<byte[]> frames = new ArrayList<>();
            try (var fixture = fixture(output, "Cases")) {
                assertEquals(1, fixture.owner().getField("started").getInt(null));
                for (var call : List.of(new Call("sum", "id=500", "n=7", "", new byte[0]),
                        new Call("defaults", "", "", "", new byte[0]),
                        new Call("headers", "", "", "X-Tag: trace\nCookie: city=Paris", new byte[0]),
                        new Call("body", "", "", "", "\"payload\"".getBytes(StandardCharsets.UTF_8)),
                        new Call("buffer", "", "", "", new byte[]{1,2,3}),
                        new Call("wrapped", "", "", "", new byte[0]), new Call("empty", "", "", "", new byte[0]),
                        new Call("checked", "", "", "", new byte[0]))) {
                    int id = fixture.ids().get(call.method());
                    ByteBuffer out = ByteBuffer.allocate(2048);
                    int size = fixture.handlers().invokeBuffered(id, out, 0, call.body(), call.path(), call.query(), call.headers());
                    String body = frameBody(out);
                    switch (call.method()) {
                        case "sum" -> assertEquals("507", body);
                        case "defaults" -> assertEquals("17", body);
                        case "headers" -> assertTrue(body.contains("traceParis"), body);
                        case "body" -> assertTrue(body.contains("payload"), body);
                        case "buffer" -> assertTrue(body.contains("buffer3"), body);
                        case "wrapped" -> assertEquals(201, out.getShort(8) & 0xffff);
                        case "checked" -> assertTrue(body.contains("recovered"), body);
                        default -> assertTrue((out.getShort(8) & 0xffff) < 400);
                    }
                    frames.add(java.util.Arrays.copyOf(out.array(), size));
                    ByteBuffer input = ByteBuffer.allocateDirect(call.body().length);
                    input.put(call.body()).flip();
                    size = fixture.handlers().invokeBufferedDirect(id, out, 0, input, input.remaining(), call.path(), call.query(), call.headers());
                    frames.add(java.util.Arrays.copyOf(out.array(), size));
                }
                frames.add(fixture.handlers().invokeAsync(fixture.ids().get("async"), new byte[0], "", "n=9", "").join());
                frames.add(fixture.handlers().invokeAsync(fixture.ids().get("failed"), new byte[0], "", "", "").join());
                ByteBuffer primitiveOut = ByteBuffer.allocate(2048);
                int primitiveSize = fixture.handlers().invokeBufferedQueryLong(fixture.ids().get("primitive"), primitiveOut, 0, 23L);
                assertEquals("23", frameBody(primitiveOut));
                assertEquals("17", GeneratedPrimitiveBindings.find(fixture.owner().getMethod("primitive", long.class)).defaultValue());
                frames.add(java.util.Arrays.copyOf(primitiveOut.array(), primitiveSize));
                ByteBuffer out = ByteBuffer.allocate(32);
                int size = fixture.handlers().invokeBufferedDirect(fixture.ids().get("direct"), out, 0, ByteBuffer.allocateDirect(0), 0, "", "", "");
                assertEquals(1, size);
                assertEquals((byte) 'Q', out.get(0));
                assertEquals(19, fixture.guard().before);
                assertEquals(19, fixture.guard().after);
                assertEquals(2, fixture.owner().getMethod("sum", long.class, int.class)
                        .getAnnotation(com.reactor.rust.annotations.RouteAdmission.class).maxConcurrent());
                GeneratedRouteInvoker invoker = invoker(fixture, "checked");
                assertThrows(java.io.IOException.class, () -> invoker.invoke0(fixture.bean()));
                fixture.container().shutdown();
                assertEquals(1, fixture.owner().getField("stopped").getInt(null));
                if (mode.equals("legacy")) baseline = frames;
                else for (int i = 0; i < frames.size(); i++) assertArrayEquals(baseline.get(i), frames.get(i), "frame=" + i);
            }
        }
    }

    @Test
    void differentOwnersShareOneAdapterClassAcrossPackages() throws Exception {
        var sources = Map.of("dispatch/fixture/Left.java", controller("Left", "@GetMapping(\"/left\") public String call() { return \"left\"; }"),
                "dispatch/other/Right.java", controller("Right", "@GetMapping(\"/right\") public String call() { return \"right\"; }")
                        .replace("package dispatch.fixture;", "package dispatch.other;"));
        var output = compile("owners", sources, "switch", true);
        try (var fixture = fixture(output, "Left")) {
            Class<?> right = fixture.loader().loadClass("dispatch.other.Right");
            var leftInvoker = invoker(fixture, "call");
            var rightInvoker = GeneratedRouteInvokers.find(right.getMethod("call"));
            assertSame(leftInvoker.getClass(), rightInvoker.getClass());
            assertEquals("right", rightInvoker.invoke0(fixture.container().getBean(right)));
        } catch (Throwable error) {
            if (error instanceof Exception checked) throw checked;
            throw new AssertionError(error);
        }
    }

    @Test
    void primitiveSwitchAddsNoSteadyStateAllocationAndReportsOnlySampleTiming() throws Throwable {
        var platform = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(platform instanceof com.sun.management.ThreadMXBean,
                "Thread allocation counter unavailable; allocation gate NOT certified");
        var meter = (com.sun.management.ThreadMXBean) platform;
        org.junit.jupiter.api.Assumptions.assumeTrue(meter.isThreadAllocatedMemorySupported());
        if (!meter.isThreadAllocatedMemoryEnabled()) meter.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        meter.getThreadAllocatedBytes(thread);
        String source = controller("Warm", "public static final Object TOKEN = new Object(); public volatile int seen; "
                + "@GetMapping(\"/warm\") public Object warm(@RequestParam(\"n\") int n) { seen = n; return TOKEN; }");
        for (String mode : List.of("legacy", "switch")) {
            var output = compile(mode, Map.of("dispatch/fixture/Warm.java", source), mode, true);
            try (var fixture = fixture(output, "Warm")) {
                var invoker = invoker(fixture, "warm", int.class);
                Object token = fixture.owner().getField("TOKEN").get(null);
                for (int i = 0; i < 10; i++) repeat(invoker, fixture.bean(), token);
                long before = meter.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                repeat(invoker, fixture.bean(), token);
                long duration = System.nanoTime() - start;
                long allocated = meter.getThreadAllocatedBytes(thread) - before;
                assertTrue(allocated >= 0 && allocated <= 8192, mode + " bytes=" + allocated);
                assertEquals(99_999, fixture.owner().getField("seen").getInt(fixture.bean()));
                System.out.println("PHASE7 dispatch sample mode=" + mode + " calls=100000 bytes=" + allocated
                        + " ns/call=" + duration / 100_000.0 + "; shared host, not a throughput/latency gate");
            }
        }
    }

    private static void repeat(GeneratedRouteInvoker invoker, Object bean, Object expected) throws Throwable {
        for (int i = 0; i < 100_000; i++) if (invoker.invokeInt(bean, i) != expected) throw new AssertionError("wrong dispatch");
    }

    private static String frameBody(ByteBuffer frame) {
        return new String(frame.array(), 18 + frame.getInt(10), frame.getInt(14), StandardCharsets.UTF_8);
    }

    @Test
    void unsupportedCrossPackageBindingsAndInvalidOptionsFailExplicitly() throws Exception {
        for (var test : Map.of("local", "@GetMapping(\"/\") String local() { return \"x\"; }",
                "generic", "@GetMapping(\"/\") public <T> String generic() { return \"x\"; }",
                "hidden", "static class Hidden {} @GetMapping(\"/\") public String hidden(@RequestBody Hidden value) { return \"x\"; }").entrySet()) {
            var result = compile(test.getKey(), Map.of("dispatch/fixture/Unsupported.java", controller("Unsupported", test.getValue())), "switch", false);
            assertTrue(result.diagnostics().contains("Unsupported switch dispatch method"), result.diagnostics());
        }
        var invalid = compile("invalid", Map.of("dispatch/fixture/Golden.java", GOLDEN), "unknown", false);
        assertTrue(invalid.diagnostics().contains("reactor.codegen.dispatch must be legacy or switch"));
        StringBuilder oversized = new StringBuilder();
        for (int i = 0; i < 257; i++) oversized.append("@GetMapping(\"/r").append(i).append("\") public String r").append(i).append("() { return \"x\"; }\n");
        var large = compile("large", Map.of("dispatch/fixture/Large.java", controller("Large", oversized.toString())), "switch", false);
        assertTrue(large.diagnostics().contains("at most 256 routes per arity"));
        var unbound = compile("unbound", Map.of("dispatch/fixture/Unbound.java", controller("Unbound",
                "@GetMapping(\"/\") public String unbound(String value) { return value; }")), "switch", false);
        assertTrue(unbound.diagnostics().contains("Unsupported switch dispatch parameter"));
        var noOwner = compile("noOwner", Map.of("dispatch/fixture/Unregistered.java", controller("Unregistered",
                "@GetMapping(\"/\") public String call() { return \"ok\"; }")
                .replace("@com.reactor.rust.di.annotation.Component ", "")), "switch", false);
        assertTrue(noOwner.diagnostics().contains("registered component/@Bean owner"));
    }

    private static String controller(String name, String methods) {
        return "package dispatch.fixture;\nimport com.reactor.rust.annotations.*;\n"
                + "@com.reactor.rust.di.annotation.Component public class " + name + " {\n" + methods + "\n}\n";
    }

    private static GeneratedRouteInvoker invoker(Fixture fixture, String name, Class<?>... parameters) throws Exception {
        return GeneratedRouteInvokers.find(fixture.owner().getMethod(name, parameters));
    }

    private static int routeId(GeneratedRouteInvoker invoker) throws Exception {
        var field = invoker.getClass().getDeclaredField("routeId");
        field.setAccessible(true);
        return field.getInt(invoker);
    }

    private static String dispatchSource(Compilation output) throws Exception {
        return Files.readString(output.generated().resolve("com/reactor/generated/ReactorApplicationDescriptor__Dispatch.java"));
    }

    private static void assertNoDispatchForwarders(String source) {
        assertFalse(source.contains("public static Object dispatch"), source);
        assertFalse(source.contains("return dispatch"), source);
        assertFalse(source.contains("Object[]"), source);
        assertFalse(source.contains("java.lang.reflect"), source);
    }

    private static String adapterSource(String source, String name) {
        int start = source.indexOf("    public static final class " + name + " implements ");
        assertTrue(start >= 0, "Missing adapter " + name);
        int end = source.indexOf("\n    }\n", start);
        assertTrue(end > start, "Missing adapter end " + name);
        return source.substring(start, end);
    }

    private static String assertDirectSwitchCall(String adapter, String signature, String call) {
        int start = adapter.indexOf("@Override public Object " + signature);
        assertTrue(start >= 0, "Missing invocation " + signature);
        int end = adapter.indexOf("\n        }", start);
        assertTrue(end > start, "Missing invocation end " + signature);
        String body = adapter.substring(start, end);
        assertTrue(body.contains("switch (routeId)"), body);
        assertTrue(body.contains(call), body);
        assertTrue(body.contains("default: throw invalidRoute(routeId);"), body);
        assertFalse(body.contains("return dispatch"), body);
        return body;
    }

    private static Object invoke(GeneratedRouteInvoker invoker, Object bean, Object[] args) throws Throwable {
        return switch (args.length) {
            case 0 -> invoker.invoke0(bean);
            case 1 -> invoker.invoke1(bean, args[0]);
            case 2 -> invoker.invoke2(bean, args[0], args[1]);
            case 3 -> invoker.invoke3(bean, args[0], args[1], args[2]);
            case 4 -> invoker.invoke4(bean, args[0], args[1], args[2], args[3]);
            case 5 -> invoker.invoke5(bean, args[0], args[1], args[2], args[3], args[4]);
            case 6 -> invoker.invoke6(bean, args[0], args[1], args[2], args[3], args[4], args[5]);
            case 7 -> invoker.invoke7(bean, args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
            case 8 -> invoker.invoke8(bean, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
            default -> throw new AssertionError();
        };
    }

    private static long classCount(Path root) throws Exception {
        try (var files = Files.walk(root)) { return files.filter(path -> path.toString().endsWith(".class")).count(); }
    }

    private Fixture fixture(Compilation output, String name) throws Exception {
        URLClassLoader loader = output.loader();
        Class<?> owner = loader.loadClass("dispatch.fixture." + name);
        BeanContainer container = BeanContainer.create();
        ExceptionHandlerRegistry errors = ExceptionHandlerRegistry.create();
        HandlerRegistry handlers = HandlerRegistry.create(errors);
        ApplicationDescriptor descriptor = (ApplicationDescriptor) loader.loadClass("com.reactor.generated.ReactorApplicationDescriptor")
                .getConstructor().newInstance();
        descriptor.registerComponents(container, "dispatch");
        descriptor.registerConfigurationBeans(container, "dispatch");
        descriptor.registerHandlers(container, handlers, "dispatch");
        descriptor.registerExceptionHandlers(container, errors, "dispatch");
        Object bean = container.getBean(owner);
        Map<String, Integer> ids = new TreeMap<>();
        CountingGuard guard = new CountingGuard();
        for (var method : GeneratedRouteInvokers.routeMethods(owner)) {
            GeneratedRouteMetadata metadata = GeneratedRouteInvokers.metadata(method);
            int id = handlers.registerHandler(bean, method, metadata.requestType(), metadata.responseType());
            handlers.attachGuard(id, guard);
            ids.put(method.getName(), id);
        }
        handlers.freeze();
        return new Fixture(loader, container, handlers, owner, bean, ids, guard);
    }

    private record Fixture(URLClassLoader loader, BeanContainer container, HandlerRegistry handlers, Class<?> owner,
                           Object bean, Map<String, Integer> ids, CountingGuard guard) implements AutoCloseable {
        public void close() throws Exception { container.shutdown(); loader.close(); }
    }

    private static final class CountingGuard implements RequestGuard {
        int before;
        int after;
        public void before(RequestGuardContext context) { before++; }
        public void after(Throwable failure) { after++; }
    }

    private record Call(String method, String path, String query, String headers, byte[] body) {}

    private Compilation compile(String name, Map<String, String> sources, String mode, boolean success) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name));
        Path generated = Files.createDirectories(root.resolve("generated"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        List<Path> files = new ArrayList<>();
        for (var source : new TreeMap<>(sources).entrySet()) {
            Path file = root.resolve("sources").resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            files.add(file);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>(List.of("--release", "21", "-proc:full", "-encoding", "UTF-8",
                    "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), "-s", generated.toString()));
            if (mode != null) options.add("-Areactor.codegen.dispatch=" + mode);
            var task = compiler.getTask(null, manager, diagnostics, options, null, manager.getJavaFileObjectsFromPaths(files));
            task.setProcessors(List.of(new ReactorStartupProcessor()));
            boolean actual = task.call();
            String messages = diagnostics.getDiagnostics().toString();
            assertEquals(success, actual, messages);
            return new Compilation(generated, classes, messages);
        }
    }

    private static Map<String, String> fingerprints(Path directory) throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                hashes.put(directory.relativize(file).toString().replace('\\', '/'),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
            }
        }
        return hashes;
    }

    private static Map<String, String> resources(Path classes) throws Exception {
        return fingerprints(classes.resolve("META-INF"));
    }

    private record Compilation(Path generated, Path classes, String diagnostics) {
        URLClassLoader loader() throws Exception {
            return new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ReactorSwitchDispatchProcessorTest.class.getClassLoader());
        }
    }
}

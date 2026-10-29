package com.reactor.rust.http.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/** No simulated transport: every success/failure below crosses a real loopback socket and JNI. */
@EnabledIfSystemProperty(named = "reactor.http-client.native-test-library", matches = ".+")
class NativeHttpTransportTest {
    private HttpServer server;
    private ThreadPoolExecutor serverWorkers;
    private final List<ReactorHttpClientRuntime> runtimes = new ArrayList<>();
    private final Map<String, String> previous = new HashMap<>();
    private ReactorHttpClientRuntime runtime;
    private ReactorHttpClientRuntime.Client client;

    @Test
    void preparedMetadataIsSharedButCallsBodiesAndMutationsAreIndependent() throws Exception {
        var calls = new AtomicInteger();
        server.createContext("/base/prepared", exchange -> respond(exchange, 200,
                calls.incrementAndGet() + ":" + exchange.getRequestURI().getRawQuery()));
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new ClassLoader(previousLoader) {
            @Override
            public java.util.Enumeration<java.net.URL> getResources(String name) throws IOException {
                return name.equals("META-INF/services/" + OutboundHeaderProvider.class.getName())
                        ? java.util.Collections.emptyEnumeration() : super.getResources(name);
            }
        });
        try { open(); } finally { Thread.currentThread().setContextClassLoader(previousLoader); }
        property("test.native.base-url", System.getProperty("test.native.base-url") + "/base");
        var owner = runtime.client("test.native.base-url");
        var plan = owner.prepare("GET", "/prepared", null, null, 2000, 0, true);
        var first = plan.request();
        var second = plan.request();
        assertNotSame(first, second);
        assertNotNull(first.nativeMetadata());
        assertSame(first.nativeMetadata(), second.nativeMetadata());
        var breaker = new CircuitBreakerState(64, 16, 50, 1000, 1, true);
        assertEquals("1:null", HttpCircuitBreakerInvocation.execute(first, String.class, breaker,
                breaker.acquirePermission()).toCompletableFuture().get(3, TimeUnit.SECONDS));
        assertEquals("2:null", second.execute(String.class).toCompletableFuture().get(3, TimeUnit.SECONDS));
        var mutated = plan.request().query("name", "İzmir").header("X-Per-Call", "one");
        assertNull(mutated.nativeMetadata());
        assertEquals("3:name=%C4%B0zmir", mutated.execute(String.class).toCompletableFuture().get(3, TimeUnit.SECONDS));
        assertSame(first.nativeMetadata(), plan.request().nativeMetadata());
        assertThrows(IllegalArgumentException.class, () -> owner.prepare("GET", "/prepared", null, "bad\r\nvalue", 2000, 0, true));
        assertThrows(HttpClientException.class, () -> owner.prepare("GET", "/" + "a".repeat(9000), null, null, 2000, 0, true));
    }

    @Test
    void preparedMethodsNeverFreezeContextualHeaderProviders() throws Exception {
        property("test.native.header-provider", "true");
        NativeHeaderProvider.BUILDS.set(0);
        server.createContext("/prepared-provider", exchange -> {
            assertNotNull(exchange.getRequestHeaders().getFirst("traceparent"));
            respond(exchange, 200, "context");
        });
        open();
        var plan = client.prepare("GET", "/prepared-provider", null, null, 2000, 0, true);
        assertEquals(0, NativeHeaderProvider.BUILDS.get(), "Preparation must not invoke a contextual provider");
        for (int i = 0; i < 3; i++) {
            var call = plan.request();
            assertNull(call.nativeMetadata());
            assertEquals("context", call.execute(String.class).toCompletableFuture().get(3, TimeUnit.SECONDS));
        }
        assertEquals(3, NativeHeaderProvider.BUILDS.get());
    }

    @Test
    void idleCompletionWorkerParksWithoutJniPollingAndWakesForEveryBurstAndClose() throws Exception {
        server.createContext("/idle", exchange -> respond(exchange, 200, "awake"));
        open();
        Thread parked = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (parked == null && System.nanoTime() < deadline) {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getName().startsWith("reactor-http-native-completion-")
                        && LockSupport.getBlocker(thread) instanceof NativeHttpTransport) parked = thread;
            }
            if (parked == null) Thread.sleep(1);
        }
        assertNotNull(parked, "An idle runtime must park, not poll JNI every 50ms");
        for (int i = 0; i < 100; i++) {
            assertEquals("awake", get("/idle", 2000, 0).execute(String.class).toCompletableFuture().get(3, TimeUnit.SECONDS));
        }
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!(LockSupport.getBlocker(parked) instanceof NativeHttpTransport) && System.nanoTime() < deadline) Thread.sleep(1);
        assertInstanceOf(NativeHttpTransport.class, LockSupport.getBlocker(parked));
        runtime.close();
        parked.join(3000);
        assertFalse(parked.isAlive(), "Close must wake a parked completion worker");
    }

    @Test
    void shortActivityGapsDoNotForceOneJavaParkPerReply() throws Exception {
        server.createContext("/short-gap", exchange -> respond(exchange, 200, "awake"));
        open();
        try (var transport = new NativeHttpTransport()) {
            transport.start();
            transport.register(java.net.URI.create(System.getProperty("test.native.base-url")));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (transport.idleParkCount() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(transport.idleParkCount() > 0, "Startup must park without an initial polling cycle");
            long before = transport.idleParkCount();
            var breaker = new CircuitBreakerState(64, 16, 50, 1000, 1, true);
            for (int i = 0; i < 30; i++) {
                assertEquals("awake", transport.executeProtectedBody(get("/short-gap", 2000, 0),
                        bytes -> new String(bytes, StandardCharsets.UTF_8), breaker, breaker.acquirePermission())
                        .get(3, TimeUnit.SECONDS));
                Thread.sleep(2);
            }
            assertTrue(transport.idleParkCount() - before < 15,
                    "Short gaps should not repeatedly switch from native waiting to Java park/unpark");
            long during = transport.idleParkCount();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (transport.idleParkCount() == during && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(transport.idleParkCount() > during, "A genuinely idle client must eventually park");
        }
    }

    @Test
    void unusedHeaderFieldsStillRejectMalformedFrameLengths() {
        var valid = java.nio.ByteBuffer.allocate(12).putInt(4).putInt(123).putInt(456).flip();
        byte[] unchanged = valid.array().clone();
        NativeHttpTransport.skipHeaderField(valid);
        assertEquals(456, valid.getInt());
        assertArrayEquals(unchanged, valid.array());
        for (int invalid : new int[]{-1, 1, Integer.MAX_VALUE}) {
            var frame = java.nio.ByteBuffer.allocate(4).putInt(invalid).flip();
            assertThrows(IllegalStateException.class, () -> NativeHttpTransport.skipHeaderField(frame));
        }
        assertThrows(java.nio.BufferUnderflowException.class,
                () -> NativeHttpTransport.skipHeaderField(java.nio.ByteBuffer.allocate(3)));
    }

    @Test
    void protectedBodySkipsHeaderObjectsWithoutChangingResponseHeaderApi() {
        server.createContext("/header-projection", exchange -> {
            exchange.getResponseHeaders().add("X-Projection", "one");
            exchange.getResponseHeaders().add("X-Projection", "two");
            respond(exchange, 200, "kept");
        });
        open();
        var breaker = new CircuitBreakerState(64, 16, 50, 1000, 1, true);
        assertEquals("kept", HttpCircuitBreakerInvocation.execute(get("/header-projection", 2000, 0),
                String.class, breaker, breaker.acquirePermission()).toCompletableFuture().join());
        var response = HttpCircuitBreakerInvocation.executeResponse(get("/header-projection", 2000, 0),
                String.class, breaker, breaker.acquirePermission()).toCompletableFuture().join();
        assertEquals(200, response.status());
        assertEquals("kept", response.body());
        assertEquals(Set.of("one", "two"), Set.copyOf(response.headers().allValues("X-Projection")));
    }

    @Test
    void nativeProtectedBodyCompletesWithTheOwnedDecoderValue() throws Exception {
        byte[] expected = {0, (byte) 0xff, (byte) 0xc3, (byte) 0xb6};
        server.createContext("/owned-body", exchange -> respond(exchange, 200, expected));
        open();
        var seen = new AtomicReference<byte[]>();
        var breaker = new CircuitBreakerState(64, 16, 50, 1000, 1, true);
        try (var transport = new NativeHttpTransport()) {
            transport.start();
            transport.register(java.net.URI.create(System.getProperty("test.native.base-url")));
            byte[] body = transport.executeProtectedBody(get("/owned-body", 2000, 0), bytes -> {
                seen.set(bytes);
                return bytes;
            }, breaker, breaker.acquirePermission()).get(3, TimeUnit.SECONDS);
            assertSame(seen.get(), body, "The body-only future must carry the decoder value itself");
            assertArrayEquals(expected, body);
            assertEquals(CircuitBreakerState.Phase.CLOSED, breaker.phase());
        }
    }

    @Test
    void nativeProtectedBodyListsAndHttpFailuresKeepTheirOutcomeRules() {
        server.createContext("/body-list", exchange -> respond(exchange, 200, "[\"one\",\"two\"]"));
        server.createContext("/body-status", exchange -> respond(exchange, 503, "unavailable"));
        open();
        var listBreaker = new CircuitBreakerState(1, 1, 100, 1000, 1, true);
        assertEquals(List.of("one", "two"), HttpCircuitBreakerInvocation.executeList(
                get("/body-list", 2000, 0), String.class, listBreaker, listBreaker.acquirePermission()).toCompletableFuture().join());
        assertEquals(CircuitBreakerState.Phase.CLOSED, listBreaker.phase());
        for (boolean record : new boolean[]{false, true}) {
            var breaker = new CircuitBreakerState(1, 1, 100, 1000, 1, record);
            var failure = assertThrows(CompletionException.class, () -> HttpCircuitBreakerInvocation.execute(
                    get("/body-status", 2000, 0), String.class, breaker, breaker.acquirePermission()).toCompletableFuture().join());
            assertEquals(503, assertInstanceOf(HttpClientException.class, failure.getCause()).status());
            assertEquals(record ? CircuitBreakerState.Phase.OPEN : CircuitBreakerState.Phase.CLOSED, breaker.phase());
        }
    }

    @Test
    void nativeBreakerRecordsStatusAndListDecodeAtTheExistingCompletionBoundary() {
        server.createContext("/list", exchange -> respond(exchange, 503, "[\"ok\"]"));
        server.createContext("/invalid", exchange -> respond(exchange, 200, "not-json"));
        open();
        for (boolean record : new boolean[]{false, true}) {
            var breaker = new CircuitBreakerState(1, 1, 100, 1000, 1, record);
            var response = HttpCircuitBreakerInvocation.executeListResponse(get("/list", 2000, 0), String.class,
                    breaker, breaker.acquirePermission()).toCompletableFuture().join();
            assertEquals(503, response.status());
            assertEquals(List.of("ok"), response.body());
            assertEquals(record ? CircuitBreakerState.Phase.OPEN : CircuitBreakerState.Phase.CLOSED, breaker.phase());
        }
        var decodeFailure = new CircuitBreakerState(1, 1, 100, 1000, 1, false);
        assertThrows(CompletionException.class, () -> HttpCircuitBreakerInvocation.executeList(get("/invalid", 2000, 0),
                String.class, decodeFailure, decodeFailure.acquirePermission()).toCompletableFuture().join());
        assertEquals(CircuitBreakerState.Phase.OPEN, decodeFailure.phase());
    }

    @Test
    void nativeBreakerCancellationCountsOnceAndDoesNotReplayALateReply() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext("/pending", exchange -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); respond(exchange, 200, "late"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/ok", exchange -> respond(exchange, 200, "ok"));
        open();
        var breaker = new CircuitBreakerState(64, 2, 50, 1000, 1, true);
        try {
            var result = HttpCircuitBreakerInvocation.execute(get("/pending", 2000, 0), String.class,
                    breaker, breaker.acquirePermission()).thenApply(value -> value).toCompletableFuture();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(result.cancel(false));
            assertTrue(result.isCancelled());
            assertEquals(CircuitBreakerState.Phase.CLOSED, breaker.phase(), "One cancellation, not two outcomes");
            release.countDown();
            assertEquals("ok", get("/ok", 2000, 0).execute(String.class).toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(CircuitBreakerState.Phase.CLOSED, breaker.phase(), "Late native completion must not record again");
            breaker.recordSuccess(breaker.acquirePermission());
            assertEquals(CircuitBreakerState.Phase.OPEN, breaker.phase(), "Exactly one failure and one success");
        } finally { release.countDown(); }
    }

    @Test
    void staleNativeHandleCannotRegisterStopCancelOrDestroyAReusedClientSlot() throws Exception {
        NativeOutboundHttp.loadAndProbe();
        server.createContext("/registry", exchange -> respond(exchange, 200, "alive"));
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        long old = NativeOutboundHttp.create(1, 2, 1, 1, 1, 256, 256, 16, 8192, 1048576, 1000, 1000);
        NativeOutboundHttp.destroy(old);
        long current = NativeOutboundHttp.create(1, 2, 1, 1, 1, 256, 256, 16, 8192, 1048576, 1000, 1000);
        try {
            assertNotEquals(old, current);
            assertThrows(IllegalStateException.class, () -> NativeOutboundHttp.register(old, url));
            NativeOutboundHttp.stop(old);
            assertFalse(NativeOutboundHttp.cancel(old, 1));
            NativeOutboundHttp.destroy(old);
            NativeOutboundHttp.register(current, url);
            NativeOutboundHttp.send(current, 1, "GET", url + "/registry", new String[0], new byte[0], 2000, 0);
            byte[] completion = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (completion == null && System.nanoTime() < deadline) completion = NativeOutboundHttp.poll(current, 100);
            assertNotNull(completion);
            var frame = java.nio.ByteBuffer.wrap(completion);
            assertEquals(1, frame.getLong());
            assertEquals(0, frame.getInt(), "No stale-handle operation may stop the new generation");
            assertEquals(200, frame.getInt());
        } finally { NativeOutboundHttp.destroy(current); }
    }

    @Test
    void splitCompletionKeepsOneOwnedBodyAndReusesOnlyTheMetadataHolder() throws Exception {
        NativeOutboundHttp.loadAndProbe();
        byte[] expected = new byte[4096];
        java.util.Arrays.fill(expected, (byte) 0xf7);
        expected[17] = 0;
        server.createContext("/split", exchange -> {
            exchange.getResponseHeaders().add("X-Split", "one");
            exchange.getResponseHeaders().add("X-Split", "two");
            respond(exchange, 200, expected);
        });
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        long handle = NativeOutboundHttp.create(1, 2, 1, 1, 1, 256, 8192, 16, 8192, 1048576, 1000, 1000);
        Object[] holder = new Object[1];
        byte[] first = null;
        try {
            NativeOutboundHttp.register(handle, url);
            assertThrows(IllegalStateException.class, () -> NativeOutboundHttp.pollBody(handle, new Object[2], 0));
            for (int i = 1; i <= 20; i++) {
                long id = ((long) i) << 12;
                NativeOutboundHttp.send(handle, id, "GET", url + "/split", new String[0], new byte[0], 2000, 0);
                byte[] body = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (body == null && System.nanoTime() < deadline) body = NativeOutboundHttp.pollBody(handle, holder, 100);
                assertNotNull(body);
                assertArrayEquals(expected, body);
                byte[] metadata = assertInstanceOf(byte[].class, holder[0]);
                assertTrue(metadata.length < 512, "Metadata must not contain the 4 KiB body");
                var frame = java.nio.ByteBuffer.wrap(metadata);
                assertEquals(id, frame.getLong());
                assertEquals(0, frame.getInt());
                assertEquals(200, frame.getInt());
                int headers = frame.getInt();
                for (int h = 0; h < headers * 2; h++) { int size = frame.getInt(); frame.position(frame.position() + size); }
                assertEquals(expected.length, frame.getInt());
                assertFalse(frame.hasRemaining());
                if (first == null) first = body;
                else assertNotSame(first, body, "Callers own their byte[]; no shared worker body may escape");
                holder[0] = null;
            }
            assertArrayEquals(expected, first);
            holder[0] = new byte[1];
            assertNull(NativeOutboundHttp.pollBody(handle, holder, 0));
            assertNull(holder[0], "Idle polling must not retain the previous metadata");
        } finally { NativeOutboundHttp.destroy(handle); }
    }

    @Test
    void resolvesLocalhostThroughTheBoundedNativeResolver() {
        property("test.native.base-url", "http://localhost:" + server.getAddress().getPort());
        property("reactor.http-client.connect-timeout-ms", "4000");
        server.createContext("/dns", exchange -> respond(exchange, 200, "resolved"));
        open();
        assertEquals("resolved", get("/dns", 5000, 0).execute(String.class).toCompletableFuture().join());
    }

    @Test
    void rawHtmlAndJsonAreNotReencodedAndErrorResponsesKeepTheirStatus() {
        String html = "<!doctype html><html lang=\"tr\"><body>\u0130stanbul</body></html>";
        String json = "{\"city\":\"\u0130stanbul\",\"count\":1}";
        server.createContext("/html", exchange -> respond(exchange, 200, html));
        server.createContext("/json", exchange -> respond(exchange, 404, json));
        open();
        assertEquals(html, get("/html", 2000, 0).execute(String.class).toCompletableFuture().join());
        var response = get("/json", 2000, 0).executeResponse(String.class).toCompletableFuture().join();
        assertEquals(404, response.status());
        assertEquals(json, response.body());
        var failure = assertThrows(CompletionException.class, () -> get("/json", 2000, 0).execute(String.class).toCompletableFuture().join());
        assertEquals(404, assertInstanceOf(HttpClientException.class, failure.getCause()).status());
    }

    @Test
    void boundsHeaderProvidersBeforeAnySocketSend() {
        property("test.native.header-provider", "true");
        property("test.native.header-count", "32");
        AtomicInteger received = new AtomicInteger();
        server.createContext("/unused", exchange -> { received.incrementAndGet(); respond(exchange, 200, "ok"); });
        open();
        var failure = assertThrows(CompletionException.class, () -> get("/unused", 2000, 0).execute(String.class).toCompletableFuture().join());
        assertEquals(413, assertInstanceOf(HttpClientException.class, failure.getCause()).status());
        assertEquals(0, received.get());
    }

    @Test
    void conservativeDefaultsAndExplicitBodyLimitsHaveDistinctAdmission() {
        for (String key : List.of("reactor.http-client.max-request-bytes", "reactor.http-client.max-response-bytes",
                "reactor.http-client.max-inflight", "reactor.http-client.threads")) {
            if (!previous.containsKey(key)) previous.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        var defaults = new NativeHttpTransport();
        assertEquals(16 * 1024, defaults.maxRequestBytes());
        assertEquals(64 * 1024, defaults.maxResponseBytes());
        property("reactor.http-client.max-request-bytes", "2048");
        property("reactor.http-client.max-response-bytes", "4096");
        var explicit = new NativeHttpTransport();
        assertEquals(2048, explicit.maxRequestBytes());
        assertEquals(4096, explicit.maxResponseBytes());
    }

    @Test
    void decoderHasNoInheritedThreadLocalsAndCloseFromCompletionWorkerIsSafe() throws Exception {
        InheritableThreadLocal<String> context = new InheritableThreadLocal<>();
        context.set("must-not-cross-transport-boundary");
        AtomicReference<String> inherited = new AtomicReference<>("not-called");
        AtomicReference<String> decoderThread = new AtomicReference<>();
        server.createContext("/decode", exchange -> respond(exchange, 200, "ok"));
        open();
        try (var transport = new NativeHttpTransport()) {
            transport.start();
            transport.register(java.net.URI.create(System.getProperty("test.native.base-url")));
            var decoded = transport.execute(get("/decode", 2000, 0), bytes -> {
                inherited.set(context.get());
                decoderThread.set(Thread.currentThread().getName());
                transport.close();
                return new String(bytes, StandardCharsets.UTF_8);
            }, false).get(3, TimeUnit.SECONDS);
            assertEquals("ok", decoded.body());
            assertNull(inherited.get());
            assertTrue(decoderThread.get().startsWith("reactor-http-native-completion-"));
        } finally { context.remove(); }
    }

    @Test
    void circuitWrapperPropagatesNativeCancellationAndBackoffCannotReplayCancelledCall() throws Exception {
        property("reactor.http-client.max-inflight", "1");
        property("reactor.http-client.retry-backoff-ms", "1000");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        server.createContext("/retry-cancel", exchange -> {
            calls.incrementAndGet(); respond(exchange, 503, "busy"); entered.countDown();
        });
        server.createContext("/ok", exchange -> respond(exchange, 200, "ok"));
        open();
        var breaker = new CircuitBreakerState(16, 2, 50, 1000, 1, true);
        var response = HttpCircuitBreakerInvocation.execute(get("/retry-cancel", 2000, 2), String.class,
                breaker, breaker.acquirePermission()).toCompletableFuture();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(response.cancel(true));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            try { assertEquals("ok", get("/ok", 1000, 0).execute(String.class).toCompletableFuture().join()); break; }
            catch (CompletionException full) { if (System.nanoTime() > deadline) throw full; Thread.sleep(10); }
        }
        Thread.sleep(1100);
        assertEquals(1, calls.get());
    }

    @BeforeEach
    void setup() throws IOException {
        property("rust.lib.path", System.getProperty("reactor.http-client.native-test-library"));
        property("reactor.http-client.transport", "rust-native");
        property("reactor.http-client.threads", "1");
        property("reactor.http-client.native.completion-threads", "1");
        property("reactor.http-client.max-inflight", "4");
        property("reactor.http-client.max-request-bytes", "1024");
        property("reactor.http-client.max-response-bytes", "1024");
        property("reactor.http-client.retries", "1");
        property("reactor.http-client.retry-backoff-ms", "5");
        property("reactor.http-client.shutdown-timeout-ms", "500");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
        serverWorkers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "native-test-http-server");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(serverWorkers);
        server.start();
        property("test.native.base-url", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        runtimes.forEach(ReactorHttpClientRuntime::close);
        server.stop(0);
        serverWorkers.shutdownNow();
        assertTrue(serverWorkers.awaitTermination(2, TimeUnit.SECONDS));
        previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }

    private void property(String key, String value) {
        if (!previous.containsKey(key)) previous.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private ReactorHttpClientRuntime open() {
        runtime = ReactorHttpClientRuntime.fromProperties();
        runtimes.add(runtime);
        client = runtime.client("test.native.base-url");
        return runtime;
    }

    private ReactorHttpClientRuntime.Request get(String path, long timeout, int retries) {
        return client.request("GET", path, null, null, timeout, retries, true);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().add("X-Native-Test", "present");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        exchange.close();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rawUnicodeBodyEncodedUriAndResponseHeaders() throws Exception {
        String text = "\u0130stanbul \u00e7a\u011fr\u0131s\u0131 \uD83D\uDE80";
        AtomicReference<String> target = new AtomicReference<>();
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<byte[]> received = new AtomicReference<>();
        server.createContext("/echo", exchange -> {
            target.set(exchange.getRequestURI().toASCIIString());
            header.set(exchange.getRequestHeaders().getFirst("X-Caller"));
            received.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 201, received.get());
        });
        open();
        var result = runtime.client("test.native.base-url")
                .request("POST", "/echo/{name}", "text/plain; charset=utf-8", "text/plain", 2000, 0, false)
                .path("name", "m\u00fc\u015fteri 1").query("city", "\u0130stanbul")
                .header("X-Caller", "caller").body(text).executeResponse(String.class).toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(201, result.status());
        assertEquals(text, result.body());
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), received.get());
        assertEquals("caller", header.get());
        assertEquals("present", result.headers().firstValue("X-Native-Test").orElseThrow());
        assertTrue(target.get().contains("m%C3%BC%C5%9Fteri%201"));
        assertTrue(target.get().contains("city=%C4%B0stanbul"));
    }

    @Test
    void byteArrayIsNotTextEncodedAndTypedListStillDecodes() {
        server.createContext("/binary", exchange -> respond(exchange, 200, exchange.getRequestBody().readAllBytes()));
        server.createContext("/list", exchange -> respond(exchange, 200, "[\"one\",\"two\"]"));
        open();
        byte[] raw = {0, 1, (byte) 0xff, (byte) 0xc3, (byte) 0xb6};
        assertArrayEquals(raw, runtime.client("test.native.base-url").request("POST", "/binary", null, null, 2000, 0, false)
                .body(raw).execute(byte[].class).toCompletableFuture().join());
        assertEquals(List.of("one", "two"), get("/list", 2000, 0).executeList(String.class).toCompletableFuture().join());
    }

    @Test
    void retriesReuseOnceOnlyCallerHeadersAndPostRequiresIdempotency() {
        property("test.native.header-provider", "true");
        NativeHeaderProvider.BUILDS.set(0);
        AtomicInteger gets = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();
        List<String> propagated = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/retry", exchange -> {
            propagated.add(exchange.getRequestHeaders().getFirst("traceparent"));
            int attempt = gets.incrementAndGet();
            respond(exchange, attempt == 1 ? 503 : 200, "ok");
        });
        server.createContext("/post", exchange -> { posts.incrementAndGet(); respond(exchange, 503, "busy"); });
        open();
        Thread caller = Thread.currentThread();
        assertEquals("ok", get("/retry", 2000, -1).execute(String.class).toCompletableFuture().join());
        assertEquals(2, gets.get());
        assertEquals(1, NativeHeaderProvider.BUILDS.get());
        assertSame(caller, NativeHeaderProvider.THREAD.get());
        assertEquals(2, propagated.size());
        assertNotNull(propagated.getFirst());
        assertEquals(propagated.getFirst(), propagated.getLast());
        assertThrows(CompletionException.class, () -> runtime.client("test.native.base-url")
                .request("POST", "/post", null, null, 2000, -1, false).body("write")
                .execute(String.class).toCompletableFuture().join());
        assertEquals(1, posts.get());
        assertThrows(CompletionException.class, () -> runtime.client("test.native.base-url")
                .request("POST", "/post", null, null, 2000, 1, false).body("write")
                .execute(String.class).toCompletableFuture().join());
        assertEquals(1, posts.get());
        assertThrows(CompletionException.class, () -> runtime.client("test.native.base-url")
                .request("POST", "/post", null, null, 2000, 1, true).body("write")
                .execute(String.class).toCompletableFuture().join());
        assertEquals(3, posts.get());
    }

    @Test
    void rejectsDeclaredAndChunkedOversizedBodiesAndRequestWithoutRetry() {
        property("reactor.http-client.max-response-bytes", "8");
        property("reactor.http-client.max-request-bytes", "8");
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/declared", exchange -> { calls.incrementAndGet(); respond(exchange, 200, "0123456789abcdef"); });
        server.createContext("/chunked", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write("0123456789abcdef".getBytes(StandardCharsets.US_ASCII)); }
        });
        open();
        for (String path : List.of("/declared", "/chunked")) {
            CompletionException error = assertThrows(CompletionException.class,
                    () -> get(path, 2000, 2).execute(byte[].class).toCompletableFuture().join());
            assertEquals(502, assertInstanceOf(HttpClientException.class, error.getCause()).status());
        }
        assertEquals(2, calls.get());
        CompletionException error = assertThrows(CompletionException.class, () -> runtime.client("test.native.base-url")
                .request("POST", "/declared", null, null, 2000, 0, false).body(new byte[9])
                .execute(byte[].class).toCompletableFuture().join());
        assertEquals(413, assertInstanceOf(HttpClientException.class, error.getCause()).status());
        assertEquals(2, calls.get());
    }

    @Test
    void enforcesInboundAndProviderHeaderLimits() {
        property("reactor.http-client.max-headers", "4");
        server.createContext("/headers", exchange -> {
            for (int i = 0; i < 8; i++) exchange.getResponseHeaders().add("X-Extra-" + i, "value");
            respond(exchange, 200, "ok");
        });
        open();
        var failure = assertThrows(CompletionException.class,
                () -> get("/headers", 2000, 0).execute(String.class).toCompletableFuture().join());
        assertEquals(502, assertInstanceOf(HttpClientException.class, failure.getCause()).status());
        assertThrows(CompletionException.class, () -> get("/headers", 2000, 0)
                .header("A", "1").header("B", "2").header("C", "3").header("D", "4")
                .execute(String.class).toCompletableFuture().join());
    }

    @Test
    void timeoutTerminatesRealIoWithoutImplicitRetry() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/slow", exchange -> {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); respond(exchange, 200, "late"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        open();
        var response = get("/slow", 250, 0).execute(String.class).toCompletableFuture();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.net.http.HttpTimeoutException.class, error.getCause().getCause());
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test
    void cancellationAbortsSocketAndReleasesCapacityOnlyAfterAcknowledgment() throws Exception {
        property("reactor.http-client.max-inflight", "1");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); respond(exchange, 200, "late"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.createContext("/ok", exchange -> respond(exchange, 200, "ok"));
        open();
        var response = get("/slow", 2000, 0).execute(String.class).toCompletableFuture();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertThrows(CompletionException.class, () -> get("/ok", 2000, 0).execute(String.class).toCompletableFuture().join());
            assertTrue(response.cancel(true));
            assertTrue(response.isCancelled());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (true) {
                try { assertEquals("ok", get("/ok", 1000, 0).execute(String.class).toCompletableFuture().join()); break; }
                catch (CompletionException full) { if (System.nanoTime() > deadline) throw full; Thread.sleep(10); }
            }
        } finally { release.countDown(); }
    }

    @Test
    void completionAndUserCodeNeverRunOnTokioIoAndRetainBulkhead() throws Exception {
        property("reactor.http-client.max-inflight", "1");
        CountDownLatch sendResponse = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch callbackRelease = new CountDownLatch(1);
        AtomicReference<String> callbackThread = new AtomicReference<>();
        server.createContext("/ok", exchange -> {
            try { sendResponse.await(2, TimeUnit.SECONDS); respond(exchange, 200, "ok"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        open();
        var response = get("/ok", 2000, 0).execute(String.class).toCompletableFuture().thenApply(value -> {
            callbackThread.set(Thread.currentThread().getName()); callbackEntered.countDown();
            try { callbackRelease.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return value;
        });
        sendResponse.countDown();
        try {
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
            assertTrue(callbackThread.get().startsWith("reactor-http-native-completion-"));
            assertThrows(CompletionException.class, () -> get("/ok", 1000, 0).execute(String.class).toCompletableFuture().join());
        } finally { callbackRelease.countDown(); }
        assertEquals("ok", response.get(2, TimeUnit.SECONDS));
    }

    @Test
    void reusesConnectionAndBoundsOriginsAndConnectionsWithoutQueueing() throws Exception {
        property("reactor.http-client.native.max-connections", "1");
        property("reactor.http-client.native.max-origins", "1");
        Set<Integer> ports = ConcurrentHashMap.newKeySet();
        server.createContext("/ok", exchange -> { ports.add(exchange.getRemoteAddress().getPort()); respond(exchange, 200, "ok"); });
        open();
        for (int i = 0; i < 5; i++) assertEquals("ok", get("/ok", 2000, 0).execute(String.class).toCompletableFuture().join());
        assertEquals(1, ports.size());
        property("test.native.other", "http://127.0.0.1:1");
        assertThrows(IllegalStateException.class, () -> runtime.client("test.native.other"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); respond(exchange, 200, "late"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        var first = get("/slow", 2000, 0).execute(String.class).toCompletableFuture();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> get("/ok", 1000, 0).execute(String.class).toCompletableFuture().get(500, TimeUnit.MILLISECONDS));
            assertEquals(503, assertInstanceOf(HttpClientException.class, error.getCause()).status());
        } finally { release.countDown(); }
        assertEquals("late", first.get(2, TimeUnit.SECONDS));
    }

    @Test
    void byteBudgetRejectsConcurrentReservationBeforeBuildingRequest() throws Exception {
        property("reactor.http-client.native.byte-budget-bytes", "250000");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); respond(exchange, 200, "ok"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        open();
        var first = get("/slow", 2000, 0).execute(String.class).toCompletableFuture();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var error = assertThrows(CompletionException.class,
                    () -> get("/slow", 1000, 0).execute(String.class).toCompletableFuture().join());
            assertEquals(503, assertInstanceOf(HttpClientException.class, error.getCause()).status());
        } finally { release.countDown(); }
        assertEquals("ok", first.get(2, TimeUnit.SECONDS));
    }

    @Test
    void serverDisconnectAfterReceivingPostIsNeverReplayed() {
        AtomicInteger received = new AtomicInteger();
        server.createContext("/disconnect", exchange -> {
            assertEquals("write", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            received.incrementAndGet(); exchange.close();
        });
        open();
        assertThrows(CompletionException.class, () -> runtime.client("test.native.base-url")
                .request("POST", "/disconnect", null, null, 2000, 0, false).body("write")
                .execute(String.class).toCompletableFuture().join());
        assertEquals(1, received.get());
    }

    @Test
    void closeCancelsDrainsAndRejectsNewCallsAndHttps() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); respond(exchange, 200, "late"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        open();
        property("test.native.https", "https://127.0.0.1:443");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runtime.client("test.native.https")).getMessage().contains("HTTP-only"));
        var client = runtime.client("test.native.base-url");
        var pending = get("/slow", 2000, 0).execute(String.class).toCompletableFuture();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            runtime.close();
            assertTrue(pending.isDone());
            assertTrue(pending.isCompletedExceptionally());
            assertThrows(CompletionException.class, () -> client.request("GET", "/slow", null, null, 1000, 0, true)
                    .execute(String.class).toCompletableFuture().join());
            runtime.close();
        } finally { release.countDown(); }
    }
}

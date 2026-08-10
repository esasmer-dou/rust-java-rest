package com.reactor.rust.http.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorHttpClientRuntimeTest {
    private static final List<String> KEYS = List.of(
            "test.http.base-url",
            "reactor.http-client.threads",
            "reactor.http-client.queue-capacity",
            "reactor.http-client.max-inflight",
            "reactor.http-client.retries",
            "reactor.http-client.retry-backoff-ms",
            "reactor.http-client.max-request-bytes",
            "reactor.http-client.max-response-bytes");

    private HttpServer server;
    private ExecutorService serverExecutor;
    private ReactorHttpClientRuntime runtime;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        serverExecutor = Executors.newFixedThreadPool(2);
        server.setExecutor(serverExecutor);
        server.start();
        System.setProperty("test.http.base-url", "http://127.0.0.1:" + server.getAddress().getPort());
        System.setProperty("reactor.http-client.threads", "1");
        System.setProperty("reactor.http-client.queue-capacity", "8");
        System.setProperty("reactor.http-client.max-inflight", "4");
        System.setProperty("reactor.http-client.retries", "1");
        System.setProperty("reactor.http-client.retry-backoff-ms", "1");
        System.setProperty("reactor.http-client.max-request-bytes", "1024");
        System.setProperty("reactor.http-client.max-response-bytes", "1024");
    }

    @AfterEach
    void stopServer() {
        if (runtime != null) runtime.close();
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
        KEYS.forEach(System::clearProperty);
    }

    @Test
    void sendsEncodedParametersAndRawUtf8StringBody() {
        AtomicReference<String> target = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/customers", exchange -> {
            target.set(exchange.getRequestURI().toASCIIString());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "İstanbul çağrısı");
        });
        runtime = ReactorHttpClientRuntime.fromProperties();

        String response = runtime.client("test.http.base-url")
                .request("POST", "/customers/{id}", "text/plain; charset=utf-8", "text/plain", 1_000L, 0, false)
                .path("id", "müşteri 1")
                .query("şehir", "İstanbul")
                .body("Çağrı gövdesi")
                .execute(String.class)
                .toCompletableFuture()
                .join();

        assertEquals("İstanbul çağrısı", response);
        assertEquals("Çağrı gövdesi", body.get());
        assertTrue(target.get().contains("m%C3%BC%C5%9Fteri%201"));
        assertTrue(target.get().contains("%C5%9Fehir=%C4%B0stanbul"));
    }

    @Test
    void retriesTransientGetButDoesNotRetryPostByDefault() {
        AtomicInteger getCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        server.createContext("/retry-get", exchange -> {
            int call = getCalls.incrementAndGet();
            respond(exchange, call == 1 ? 503 : 200, call == 1 ? "busy" : "ready");
        });
        server.createContext("/retry-post", exchange -> {
            postCalls.incrementAndGet();
            respond(exchange, 503, "busy");
        });
        runtime = ReactorHttpClientRuntime.fromProperties();

        String value = runtime.client("test.http.base-url")
                .request("GET", "/retry-get", "application/json", "text/plain", 1_000L, -1, false)
                .execute(String.class).toCompletableFuture().join();
        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class,
                () -> runtime.client("test.http.base-url")
                        .request("POST", "/retry-post", "application/json", "text/plain", 1_000L, -1, false)
                        .body("payload")
                        .execute(String.class).toCompletableFuture().join());

        assertEquals("ready", value);
        assertEquals(2, getCalls.get());
        assertEquals(1, postCalls.get());
        assertEquals(503, assertInstanceOf(HttpClientException.class, failure.getCause()).status());
    }

    @Test
    void rejectsOversizedResponseWithoutRetryingIt() {
        AtomicInteger calls = new AtomicInteger();
        System.setProperty("reactor.http-client.max-response-bytes", "8");
        server.createContext("/large", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "0123456789abcdef");
        });
        runtime = ReactorHttpClientRuntime.fromProperties();

        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class,
                () -> runtime.client("test.http.base-url")
                        .request("GET", "/large", "application/json", "application/octet-stream", 1_000L, -1, false)
                        .execute(byte[].class).toCompletableFuture().join());

        assertEquals(1, calls.get());
        assertEquals(502, assertInstanceOf(HttpClientException.class, failure.getCause()).status());
    }

    @Test
    void decodesTypedListsWithoutParameterizedTypeReflection() {
        server.createContext("/names", exchange -> respond(exchange, 200, "[\"Çağrı\",\"İstanbul\"]"));
        runtime = ReactorHttpClientRuntime.fromProperties();

        List<String> names = runtime.client("test.http.base-url")
                .request("GET", "/names", "application/json", "application/json", 1_000L, 0, false)
                .executeList(String.class)
                .toCompletableFuture()
                .join();

        assertEquals(List.of("Çağrı", "İstanbul"), names);
    }

    @Test
    void failsFastWhenBulkheadIsFull() throws Exception {
        System.setProperty("reactor.http-client.max-inflight", "1");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
                respond(exchange, 200, "ok");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        runtime = ReactorHttpClientRuntime.fromProperties();
        ReactorHttpClientRuntime.Client client = runtime.client("test.http.base-url");

        var first = client.request("GET", "/slow", "application/json", "text/plain", 2_000L, 0, false)
                .execute(String.class).toCompletableFuture();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class,
                () -> client.request("GET", "/slow", "application/json", "text/plain", 2_000L, 0, false)
                        .execute(String.class).toCompletableFuture().join());
        release.countDown();

        assertEquals(503, assertInstanceOf(HttpClientException.class, failure.getCause()).status());
        assertEquals("ok", first.join());
    }

    private static void respond(HttpExchange exchange, int status, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

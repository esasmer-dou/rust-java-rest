package com.reactor.rust.http.client;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real verified TLS sockets and JNI, not a simulated HTTP transport. */
@EnabledIfSystemProperty(named = "reactor.http-client.native-test-library", matches = ".+")
class NativeHttpTransportTlsTest {
    @TempDir Path directory;
    private final Map<String, String> previous = new HashMap<>();
    private final Set<Integer> peers = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private HttpsServer server;
    private ReactorHttpClientRuntime runtime;

    @BeforeEach
    void configure() {
        property("rust.lib.path", System.getProperty("reactor.http-client.native-test-library"));
        property("reactor.http-client.transport", "native");
        property("reactor.http-client.native.tls.enabled", "true");
        property("reactor.http-client.native.tls.ca-pem-path", "");
        property("reactor.http-client.threads", "1");
        property("reactor.http-client.max-inflight", "1");
        property("reactor.http-client.native.max-connections", "1");
        property("reactor.http-client.max-request-bytes", "1024");
        property("reactor.http-client.max-response-bytes", "1024");
        property("reactor.http-client.native.byte-budget-bytes", "1048576");
        property("reactor.http-client.connect-timeout-ms", "500");
        property("reactor.http-client.shutdown-timeout-ms", "1000");
        property("reactor.http-client.retries", "0");
    }

    @AfterEach
    void close() throws Exception {
        if (runtime != null) runtime.close();
        if (server != null) server.stop(0);
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        previous.forEach((key, value) -> {
            if (value == null) System.clearProperty(key); else System.setProperty(key, value);
        });
    }

    @Test
    void trustedGetPostKeepAliveAndNoJdkClientThreads() throws Exception {
        Set<Thread> existingThreads = Thread.getAllStackTraces().keySet();
        startTls(false);
        trustServer();
        open("localhost");
        for (int i = 0; i < 12; i++) {
            int sequence = i;
            var response = awaitAdmission(() -> {
                var request = runtime.client("test.tls.base-url")
                        .request(sequence == 0 ? "GET" : "POST", "/echo", "text/plain", null, 2000, 0, false);
                if (sequence > 0) request.body("body-" + sequence);
                return request.execute(String.class).toCompletableFuture();
            }, call -> call.get(3, TimeUnit.SECONDS));
            assertEquals(i == 0 ? "ready" : "body-" + i, response);
        }
        assertEquals(1, peers.size(), "TLS keep-alive must reuse the one bounded connection");
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> !existingThreads.contains(thread)
                        && thread.getName().matches("HttpClient-[0-9]+-SelectorManager")));
    }

    @Test
    void defaultRequestResponseAndBudgetLimitsAdmitHttps() throws Exception {
        startTls(false);
        trustServer();
        property("reactor.http-client.max-request-bytes", "16384");
        property("reactor.http-client.max-response-bytes", "65536");
        property("reactor.http-client.native.byte-budget-bytes", "1048576");
        open("localhost");
        assertEquals("ready", runtime.client("test.tls.base-url")
                .request("GET", "/echo", null, null, 5000, 0, true)
                .execute(String.class).toCompletableFuture().get(6, TimeUnit.SECONDS));
    }

    @Test
    void wrongSanIsRejectedAndFailedHandshakesReleaseCapacity() throws Exception {
        startTls(false);
        trustServer();
        open("127.0.0.1");
        rejectRepeatedly();
    }

    @Test
    void untrustedAndExpiredCertificatesAreRejected() throws Exception {
        startTls(true);
        trustServer();
        open("localhost");
        rejectRepeatedly();
        runtime.close();
        runtime = null;
        property("reactor.http-client.native.tls.ca-pem-path", "");
        open("localhost");
        rejectRepeatedly();
    }

    @Test
    void tcpAndTlsShareDeadlineAndCancellationDoesNotLeakBudget() throws Exception {
        startTls(false);
        trustServer();
        try (ServerSocket blackhole = new ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())) {
            Semaphore accepted = new Semaphore(0);
            workers.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try (var socket = blackhole.accept()) {
                        accepted.release();
                        socket.setSoTimeout(1000);
                        while (socket.getInputStream().read() != -1) { }
                    } catch (java.io.IOException stopped) {
                        if (blackhole.isClosed()) return;
                    }
                }
            });
            property("test.tls.base-url", "https://localhost:" + blackhole.getLocalPort());
            runtime = ReactorHttpClientRuntime.fromProperties();
            var client = runtime.client("test.tls.base-url");
            for (int i = 0; i < 5; i++) {
                long start = System.nanoTime();
                var call = awaitAdmission(() -> client.request("GET", "/", null, null, 5000, 0, true)
                        .execute(String.class).toCompletableFuture(),
                        attempt -> awaitBlackholeAcceptance(attempt, accepted));
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> call.get(2, TimeUnit.SECONDS));
                assertInstanceOf(java.net.http.HttpTimeoutException.class, rootCause(failure));
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            }
            var cancelled = awaitAdmission(() -> client.request("GET", "/", null, null, 5000, 0, true)
                    .execute(String.class).toCompletableFuture(),
                    attempt -> awaitBlackholeAcceptance(attempt, accepted));
            assertTrue(cancelled.cancel(true), () -> "Handshake was no longer pending: " + cancelled);
        }
        // A successful call in the same runtime proves cancellation acknowledged its reservations.
        property("test.tls.ready-url", "https://localhost:" + server.getAddress().getPort());
        var ready = runtime.client("test.tls.ready-url");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            try {
                assertEquals("ready", ready.request("GET", "/echo", null, null, 2000, 0, true)
                        .execute(String.class).toCompletableFuture().get(3, TimeUnit.SECONDS));
                break;
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof HttpClientException status) || status.status() != 503
                        || System.nanoTime() >= deadline) throw failure;
                Thread.sleep(10);
            }
        }
    }

    @Test
    void disablingNativeTlsSelectsJdkWithoutLoadingNativeRuntime() {
        property("reactor.http-client.native.tls.enabled", "false");
        runtime = ReactorHttpClientRuntime.fromProperties();
        assertEquals(ReactorHttpClientRuntime.class, runtime.getClass());
    }

    @Test
    void admissionWaitObservesAsynchronousJavaRejection() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        var response = awaitAdmission(() -> {
            if (submissions.incrementAndGet() > 1) return CompletableFuture.completedFuture("ready");
            CompletableFuture<String> rejected = new CompletableFuture<>();
            workers.submit(() -> rejected.completeExceptionally(new HttpClientException(
                    "Native outbound capacity is full or runtime is closed", 503)));
            return rejected;
        }, call -> call.get(2, TimeUnit.SECONDS));
        assertEquals("ready", response);
        assertEquals(2, submissions.get());
    }

    @Test
    void admissionWaitDoesNotRetryAsynchronousNativeCapacity() {
        AtomicInteger submissions = new AtomicInteger();
        ExecutionException failure = assertThrows(ExecutionException.class, () -> awaitAdmission(() -> {
            submissions.incrementAndGet();
            CompletableFuture<String> rejected = new CompletableFuture<>();
            workers.submit(() -> rejected.completeExceptionally(new HttpClientException(
                    "Native outbound connection or byte capacity is full", 503)));
            return rejected;
        }, call -> call.get(2, TimeUnit.SECONDS)));
        assertEquals("Native outbound connection or byte capacity is full", failure.getCause().getMessage());
        assertEquals(1, submissions.get());
    }

    @FunctionalInterface
    private interface AdmissionWait<T> {
        T await(CompletableFuture<String> call) throws Exception;
    }

    private <T> T awaitAdmission(Supplier<CompletableFuture<String>> submit, AdmissionWait<T> wait)
            throws Exception {
        long drainedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            try {
                return wait.await(submit.get());
            } catch (ExecutionException failure) {
                // Future visibility precedes callback-slot release; never retry native budget/connection errors.
                if (!(failure.getCause() instanceof HttpClientException capacity)
                        || capacity.status() != 503
                        || !"Native outbound capacity is full or runtime is closed".equals(capacity.getMessage())
                        || System.nanoTime() >= drainedBy) throw failure;
            }
            Thread.sleep(1);
        }
    }

    private CompletableFuture<String> awaitBlackholeAcceptance(CompletableFuture<String> call,
                                                               Semaphore accepted) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!accepted.tryAcquire(1, TimeUnit.MILLISECONDS)) {
            if (call.isDone()) {
                call.get();
                fail("Request completed before the blackhole accepted its TCP connection");
            }
            assertTrue(System.nanoTime() < deadline,
                    () -> "Blackhole did not accept the pending request: " + call);
        }
        return call;
    }

    private void rejectRepeatedly() throws Exception {
        var client = runtime.client("test.tls.base-url");
        for (int i = 0; i < 12; i++) {
            long drainedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (true) {
                var call = client.request("GET", "/echo", null, null, 2000, 0, true)
                        .execute(String.class).toCompletableFuture();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> call.get(3, TimeUnit.SECONDS));
                Throwable cause = rootCause(failure);
                if (cause instanceof java.io.IOException) break;
                // Future completion may wake this thread before its bounded callback slot is released.
                if (!(cause instanceof HttpClientException capacity)
                        || capacity.status() != 503 || System.nanoTime() >= drainedBy) {
                    assertInstanceOf(java.io.IOException.class, cause,
                            "Certificate rejection leaked capacity at attempt " + i);
                }
                Thread.sleep(1);
            }
        }
    }

    private static Throwable rootCause(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private void open(String hostname) {
        property("test.tls.base-url", "https://" + hostname + ":" + server.getAddress().getPort());
        runtime = ReactorHttpClientRuntime.fromProperties();
        runtime.client("test.tls.base-url");
    }

    private void trustServer() { property("reactor.http-client.native.tls.ca-pem-path", directory.resolve("ca.pem").toString()); }

    private void startTls(boolean expired) throws Exception {
        Path store = directory.resolve("server.p12");
        keytool("-genkeypair", "-alias", "server", "-keystore", store.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-validity", expired ? "1" : "2",
                "-startdate", expired ? "2020/01/01 00:00:00" : "-1d", "-noprompt");
        keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", store.toString(),
                "-storepass", "changeit", "-file", directory.resolve("ca.pem").toString());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "changeit".toCharArray()); }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.setExecutor(workers);
        server.createContext("/echo", exchange -> {
            peers.add(exchange.getRemoteAddress().getPort());
            byte[] input = exchange.getRequestBody().readAllBytes();
            byte[] body = input.length == 0 ? "ready".getBytes(StandardCharsets.UTF_8) : input;
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            exchange.close();
        });
        server.start();
    }

    private void keytool(String... arguments) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        var command = new java.util.ArrayList<String>();
        command.add(executable);
        command.addAll(java.util.List.of(arguments));
        Path log = directory.resolve("keytool.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "TLS fixture keytool timed out");
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(log); } catch (java.io.IOException failure) { return failure.toString(); }
            });
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private void property(String key, String value) {
        if (!previous.containsKey(key)) previous.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }
}

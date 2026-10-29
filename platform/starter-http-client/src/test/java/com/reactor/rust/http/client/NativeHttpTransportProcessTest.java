package com.reactor.rust.http.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Fresh real JVMs with actual VM class-load output, including a positive JDK control. */
class NativeHttpTransportProcessTest {
    @Test
    void defaultJdkControlReallyLoadsAndStartsJdkHttpClient() throws Exception {
        String output = child("jdk");
        assertTrue(output.contains("JDK_PROOF requests=1"), output);
        assertTrue(output.contains("jdk.internal.net.http.HttpClientImpl"), "Actual class loading must be captured\n" + output);
    }

    @Test
    @EnabledIfSystemProperty(named = "reactor.http-client.featureless-test-library", matches = ".+")
    void packagedLibraryWithoutOutboundSymbolsFailsAtStartupWithoutFallback() throws Exception {
        String output = child("missing");
        assertTrue(output.contains("MISSING_SYMBOLS_PROOF jdkHttpThreads=0"), output);
        assertFalse(output.contains("jdk.internal.net.http.HttpClientImpl"), output);
    }

    @Test
    void packagedLibrarySupportsNativeOutboundWithoutJdkFallback() throws Exception {
        String output = child("packaged");
        assertTrue(output.contains("NATIVE_PROOF requests=6 jdkHttpThreads=0 connections=1"), output);
        assertFalse(output.contains("jdk.internal.net.http.HttpClientImpl"), output);
    }

    @Test
    @EnabledIfSystemProperty(named = "reactor.http-client.native-test-library", matches = ".+")
    void freshNativeChildHasExactlyZeroJdkHttpThreadsAndNoHttpClientImplClass() throws Exception {
        String output = child("native");
        assertTrue(output.contains("NATIVE_PROOF requests=6 jdkHttpThreads=0 connections=1"), output);
        assertTrue(output.contains("java.net.http.HttpRequest"), "Actual request type class loading must be captured\n" + output);
        assertFalse(output.contains("jdk.internal.net.http.HttpClientImpl"), output);
        assertFalse(output.contains("jdk.internal.net.http.HttpClientFacade"), output);
        assertFalse(output.contains("SelectorManager"), output);
    }

    @Test
    @EnabledIfSystemProperty(named = "reactor.http-client.native-test-library", matches = ".+")
    void freshChildRejectsNinthLiveRuntimeWithoutPriorReaperInterference() throws Exception {
        String output = child("clients");
        assertTrue(output.contains("MAX_CLIENTS_PROOF live=8 rejected=9 jdkHttpThreads=0"), output);
        assertFalse(output.contains("jdk.internal.net.http.HttpClientImpl"), output);
    }

    private static String child(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new ArrayList<String>();
        command.add(java);
        command.add("-Xmx128m");
        command.add("-Xss512k");
        command.add("-XX:ActiveProcessorCount=2");
        command.add("-verbose:class");
        if ("native".equals(mode) || "clients".equals(mode)) {
            command.add("-Drust.lib.path=" + System.getProperty("reactor.http-client.native-test-library"));
        }
        if ("missing".equals(mode)) {
            command.add("-Drust.lib.path=" + System.getProperty("reactor.http-client.featureless-test-library"));
        }
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(Child.class.getName());
        command.add(mode);
        Path output = Path.of("target", "outbound-" + mode + "-child.log").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("Bounded child JVM timed out; inspect " + output);
            }
            String text = Files.readString(output).replace('/', '.');
            assertEquals(0, process.exitValue(), text);
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            if (jdkThreads() != 0) throw new AssertionError("Child did not start with zero JDK HTTP threads");
            System.setProperty("reactor.http-client.threads", "1");
            System.setProperty("reactor.http-client.max-inflight", "2");
            System.setProperty("reactor.http-client.max-request-bytes", "4096");
            System.setProperty("reactor.http-client.max-response-bytes", "4096");
            System.setProperty("reactor.http-client.retries", "0");
            System.setProperty("reactor.http-client.request-timeout-ms", "3000");
            if (!"jdk".equals(mode)) System.setProperty("reactor.http-client.transport", "rust-native");
            if ("missing".equals(mode)) {
                try (var ignored = ReactorHttpClientRuntime.fromProperties()) {
                    throw new AssertionError("Feature-less packaged artifact unexpectedly started native outbound HTTP");
                } catch (IllegalStateException expected) {
                    if (!expected.getMessage().contains("--features outbound-http")) throw expected;
                }
                if (jdkThreads() != 0) throw new AssertionError("Startup failure silently started JDK transport");
                System.out.println("MISSING_SYMBOLS_PROOF jdkHttpThreads=0");
                return;
            }
            if ("clients".equals(mode)) {
                var runtimes = new ArrayList<ReactorHttpClientRuntime>();
                try {
                    for (int i = 0; i < 8; i++) runtimes.add(ReactorHttpClientRuntime.fromProperties());
                    try (var ignored = ReactorHttpClientRuntime.fromProperties()) {
                        throw new AssertionError("Ninth live runtime was admitted");
                    } catch (IllegalStateException expected) {
                        if (!expected.getMessage().contains("8 native outbound runtimes")) throw expected;
                    }
                    if (jdkThreads() != 0) throw new AssertionError("Client limit started JDK transport");
                    System.out.println("MAX_CLIENTS_PROOF live=8 rejected=9 jdkHttpThreads=0");
                } finally { runtimes.forEach(ReactorHttpClientRuntime::close); }
                return;
            }
            AtomicInteger calls = new AtomicInteger();
            Set<Integer> connections = ConcurrentHashMap.newKeySet();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(8), task -> {
                        Thread thread = new Thread(task, "outbound-child-server"); thread.setDaemon(true); return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                calls.incrementAndGet(); connections.add(exchange.getRemoteAddress().getPort());
                byte[] request = exchange.getRequestBody().readAllBytes();
                byte[] body = request.length == 0 ? "ok".getBytes(StandardCharsets.UTF_8) : request;
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            System.setProperty("child.base-url", "http://127.0.0.1:" + server.getAddress().getPort());
            try (var runtime = ReactorHttpClientRuntime.fromProperties()) {
                var client = runtime.client("child.base-url");
                int requests = "native".equals(mode) || "packaged".equals(mode) ? 5 : 1;
                for (int i = 0; i < requests; i++) {
                    String value = client.request("GET", "/", null, null, 3000, 0, true)
                            .execute(String.class).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    if (!"ok".equals(value)) throw new AssertionError("Real HTTP GET failed");
                }
                if ("native".equals(mode) || "packaged".equals(mode)) {
                    if (com.reactor.rust.bridge.NativeBridge.nativeAbiVersion() != 29) {
                        throw new AssertionError("Integrated artifact did not preserve REST ABI 29");
                    }
                    if (!com.reactor.rust.bridge.NativeBridge.nativeBuildInfo().replace('_', '-').contains("outbound-http")) {
                        throw new AssertionError("Integrated artifact build info does not include outbound-http");
                    }
                    System.out.println("ABI_PROOF restAbi=29 outbound-http=true");
                    String raw = "\u0130stanbul \uD83D\uDE80";
                    String value = client.request("POST", "/", "text/plain; charset=utf-8", "text/plain", 3000, 0, false)
                            .body(raw).execute(String.class).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    if (!raw.equals(value)) throw new AssertionError("Real raw UTF-8 HTTP POST failed");
                    if (jdkThreads() != 0 || connections.size() != 1 || calls.get() != 6) {
                        throw new AssertionError("Native isolation/reuse failed: jdk=" + jdkThreads() + " connections=" + connections.size());
                    }
                    System.out.println("NATIVE_PROOF requests=6 jdkHttpThreads=0 connections=1");
                } else {
                    if (jdkThreads() < 1) throw new AssertionError("JDK positive control did not start its client threads");
                    System.out.println("JDK_PROOF requests=1 jdkHttpThreads=" + jdkThreads());
                }
            } finally {
                server.stop(0); workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS);
            }
            if ("native".equals(mode)) {
                if (jdkThreads() != 0) throw new AssertionError("JDK HTTP threads appeared during close");
                long completionWorkers = Thread.getAllStackTraces().keySet().stream()
                        .filter(thread -> thread.getName().startsWith("reactor-http-native-completion-")).count();
                if (completionWorkers != 0) throw new AssertionError("Native completion workers did not drain");
            }
        }

        private static long jdkThreads() {
            return Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getName().startsWith("HttpClient-") || thread.getName().contains("SelectorManager")).count();
        }
    }
}

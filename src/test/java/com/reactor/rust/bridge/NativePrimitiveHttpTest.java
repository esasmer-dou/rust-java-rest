package com.reactor.rust.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run in a dedicated JVM against the freshly built native artifact, not a stale packaged DLL/SO. */
@EnabledIfSystemProperty(named = "reactor.test.native-http", matches = "true")
class NativePrimitiveHttpTest {
    @Test
    void primitiveCallbacksAndFailureRecoveryAcrossRealHttp() throws Exception {
        System.setProperty("reactor.rust.jni.workers", "1");
        System.setProperty("reactor.rust.runtime.worker-threads", "1");
        System.setProperty("reactor.rust.async.response-timeout-ms", "100");
        String expectedHttp1 = System.getProperty("reactor.test.http1");
        if (expectedHttp1 != null) {
            System.setProperty("reactor.rust.http.http1-only-enabled", expectedHttp1);
        }
        NativeBridge.configureRuntimeFromProperties();
        NativeBridge.passNativeBridgeClass(Callbacks.class);
        NativeBridge.registerRoutes(List.of(route("int"), route("long"), route("boolean"),
                route("double"), route("short"),
                new RouteDef("GET", "/async/early", 10, "java.lang.Void", "java.lang.String",
                        true, false, false, false, true, 0L, 4096L),
                new RouteDef("GET", "/async/rejected", 11, "java.lang.Void", "java.lang.String",
                        true, false, false, false, true, 0L, 4096L),
                new RouteDef("GET", "/async/deferred", 12, "java.lang.Void", "java.lang.String",
                        true, false, false, false, true, 0L, 4096L),
                new RouteDef("GET", "/async/throwing", 13, "java.lang.Void", "java.lang.String",
                        true, false, false, false, true, 0L, 4096L),
                new RouteDef("GET", "/async/never", 14, "java.lang.Void", "java.lang.String",
                        true, false, false, false, true, 0L, 4096L),
                new RouteDef("GET", "/async/slow-start", 15, "java.lang.Void", "java.lang.String",
                        true, false, false, false, true, 0L, 4096L),
                new RouteDef("GET", "/sync/string", 16, "java.lang.Void", "java.lang.String",
                        true, false, true, false, false, 0L, 4096L)));
        int port = NativeBridge.startHttpServerAndGetPort(0);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            if (expectedHttp1 != null) {
                String diagnostics = NativeBridge.nativeMemoryDiagnosticsJson();
                assertTrue(diagnostics.contains("\"http1_only\":" + (Boolean.parseBoolean(expectedHttp1) ? "1" : "0")),
                        diagnostics);
            }
            for (String[] example : new String[][] {
                    {"int", "2147483647"}, {"long", "9223372036854775807"},
                    {"boolean", "true"}, {"boolean", "false"}, {"double", "1.25"}, {"short", "-32768"}}) {
                HttpResponse<String> response = get(client, port, example[0], example[1]);
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(example[1], response.body());
            }
            for (String invalid : List.of("-42", "-43", "-44")) {
                HttpResponse<String> failed = get(client, port, "long", invalid);
                assertEquals(500, failed.statusCode(), failed.body());
                assertTrue(failed.body().contains("error"));
                HttpResponse<String> recovered = get(client, port, "long", "17");
                assertEquals(200, recovered.statusCode(), recovered.body());
                assertEquals("17", recovered.body());
            }
            assertEquals(400, get(client, port, "short", "32768").statusCode());
            assertEquals(400, get(client, port, "long", "not-a-number").statusCode());
            var generic = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/sync/string?text=test")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, generic.statusCode(), generic.body());
            assertEquals("{\"message\":\"generic-sync\"}", generic.body());
            for (String type : List.of("early", "rejected", "deferred", "throwing", "slow-start", "early", "never")) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/async/" + type)).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                boolean failed = type.equals("rejected") || type.equals("throwing");
                assertEquals(type.equals("never") ? 504 : failed ? 500 : 200, response.statusCode(), response.body());
                assertTrue(response.body().contains(type.equals("never") ? "async_response_timeout"
                        : failed ? "async_start_failed" : "async-ok"));
            }
            String diagnostics = NativeBridge.nativeMemoryDiagnosticsJson();
            assertTrue(diagnostics.contains("\"async\":{\"pending\":0,"), diagnostics);
        } finally {
            assertTrue(NativeBridge.stopHttpServer());
        }
    }

    private static HttpResponse<String> get(HttpClient client, int port, String type, String value) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/primitive/" + type + "?value=" + value)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static RouteDef route(String type) {
        return new RouteDef("GET", "/primitive/" + type, 1, "java.lang.Void", "java.lang.String",
                true, false, false, false, false, 0L, 4096L,
                type.equals("int") ? "value" : "", 0, Integer.MIN_VALUE, Integer.MAX_VALUE,
                type.equals("long") ? "value" : "", 0L, Long.MIN_VALUE, Long.MAX_VALUE,
                type.equals("boolean") ? "value" : "", false,
                type.equals("double") ? "value" : "", 0.0, -Double.MAX_VALUE, Double.MAX_VALUE,
                type.equals("short") ? "value" : "", (short) 0, Short.MIN_VALUE, Short.MAX_VALUE,
                "", Integer.MIN_VALUE, Integer.MAX_VALUE, "", Long.MIN_VALUE, Long.MAX_VALUE,
                "", "", -Double.MAX_VALUE, Double.MAX_VALUE, "", Short.MIN_VALUE, Short.MAX_VALUE,
                2, false, 0, 0);
    }

    public static final class Callbacks {
        public static int handleRustBodylessRequestIntoBuffer(int id, ByteBuffer out, int offset,
                                                               int capacity, String path, String query, String headers) {
            return write(out, offset, "{\"message\":\"generic-sync\"}");
        }
        public static boolean handleRustAsyncBodylessRequest(int handlerId, long requestId,
                                                            String path, String query, String headers) {
            if (handlerId == 13) throw new IllegalStateException("injected async start failure");
            if (handlerId == 14) return true;
            if (handlerId == 15) {
                try { Thread.sleep(150); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            }
            byte[] frame = ByteBuffer.allocate(18).put("RJRSPV1!".getBytes(StandardCharsets.US_ASCII))
                    .putShort((short) 200).putInt(0).putInt(8).array();
            byte[] body = "async-ok".getBytes(StandardCharsets.UTF_8);
            byte[] response = java.util.Arrays.copyOf(frame, frame.length + body.length);
            System.arraycopy(body, 0, response, frame.length, body.length);
            if (handlerId == 12) {
                new Thread(() -> NativeBridge.completeAsyncResponse(requestId, response), "test-async-completion").start();
            } else {
                NativeBridge.completeAsyncResponse(requestId, response);
            }
            return handlerId != 11;
        }
        public static int handleRustQueryIntRequestIntoBuffer(int id, ByteBuffer out, int offset, int capacity, int value) {
            return write(out, offset, Integer.toString(value));
        }
        public static int handleRustQueryLongRequestIntoBuffer(int id, ByteBuffer out, int offset, int capacity, long value) {
            if (value == -42) throw new IllegalStateException("injected JNI exception");
            if (value == -43) return Integer.MAX_VALUE;
            if (value == -44) return Integer.MIN_VALUE;
            return write(out, offset, Long.toString(value));
        }
        public static int handleRustQueryBooleanRequestIntoBuffer(int id, ByteBuffer out, int offset, int capacity, boolean value) {
            return write(out, offset, Boolean.toString(value));
        }
        public static int handleRustQueryDoubleRequestIntoBuffer(int id, ByteBuffer out, int offset, int capacity, double value) {
            return write(out, offset, Double.toString(value));
        }
        public static int handleRustQueryShortRequestIntoBuffer(int id, ByteBuffer out, int offset, int capacity, short value) {
            return write(out, offset, Short.toString(value));
        }
        private static int write(ByteBuffer out, int offset, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            out.position(offset);
            out.put(bytes);
            return bytes.length;
        }
    }
}

package com.reactor.rust.maven;

import com.sun.net.httpserver.HttpServer;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Packaged alone by the EmaTech harness; never added to an application or runtime artifact. */
public final class AppCdsOfflineFixture {
    private static Class<?> frameworkWriter;
    private AppCdsOfflineFixture() {}

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--framework".equals(args[0])) {
            exerciseFramework(args[1]);
            args = Arrays.copyOfRange(args, 2, args.length);
        }
        if (Arrays.equals(args, new String[]{"--offline"})) {
            try (var input = AppCdsOfflineFixture.class.getResourceAsStream("/appcds-hot-classes.txt")) {
                if (input != null) {
                    byte[] bytes = input.readNBytes(262_145);
                    if (bytes.length > 262_144) throw new IllegalArgumentException("Training index exceeds 256 KiB");
                    String[] names = new String(bytes, StandardCharsets.UTF_8).split("\\R");
                    if (names.length > 4096) throw new IllegalArgumentException("Training index exceeds 4096 types");
                    for (String name : names) {
                        if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
                            throw new IllegalArgumentException("Invalid indexed training class");
                        // Build-time metadata only: no bean, business method, native library or network startup.
                        Class.forName(name, false, AppCdsOfflineFixture.class.getClassLoader());
                    }
                    System.out.println("APPCDS_PROFILED_CLASSES=" + names.length);
                }
            }
            String body = new Payload(64).json();
            if (!body.equals("{\"item16\":\"appcds\",\"count\":64}")) throw new AssertionError(body);
            // Offline training must not warm the diagnostic HTTP server or management subsystem.
            System.out.println("APPCDS_OFFLINE_OK");
            return;
        }
        if (Arrays.equals(args, new String[]{"--hang"})) {
            while (true) Thread.sleep(1000);
        }
        if (args.length != 2 || !"--serve".equals(args[0])) {
            throw new IllegalArgumentException("Only --offline, --hang or --serve <ready-file> is supported");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy()));
        server.createContext("/", exchange -> {
            String body = switch (exchange.getRequestURI().getPath()) {
                case "/ready" -> "APPCDS_READY";
                case "/data" -> responseJson();
                case "/heap" -> heap();
                default -> "NOT_FOUND";
            };
            byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders("NOT_FOUND".equals(body) ? 404 : 200, bytes.length);
            try (var response = exchange.getResponseBody()) {
                response.write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        Files.writeString(Path.of(args[1]), Integer.toString(server.getAddress().getPort()), StandardCharsets.US_ASCII);
    }

    private static String heap() {
        var usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return usage.getUsed() + "," + usage.getCommitted();
    }

    private static void exerciseFramework(String descriptorName) throws Exception {
        // Reflection is confined to this test-only fixture, never the framework hot path.
        Class<?> type = Class.forName("com.reactor.rust.json.JsonBufferWriter");
        frameworkWriter = type;
        if (!new Payload(64).json().equals(responseJson())) throw new AssertionError("Actual framework JSON output mismatch");
        Class<?> descriptor = Class.forName(descriptorName);
        Class<?> contract = Class.forName("com.reactor.rust.startup.ApplicationDescriptor");
        if (!contract.isAssignableFrom(descriptor)) throw new AssertionError("Not an ApplicationDescriptor");
        Object value = descriptor.getConstructor().newInstance();
        // Only generated metadata is consulted. Never register components, handlers or extensions.
        if (!Boolean.TRUE.equals(descriptor.getMethod("coversPackage", String.class).invoke(value, new Object[]{null}))) {
            throw new AssertionError("Generated application descriptor covers no packages");
        }
        System.out.println("APPCDS_FRAMEWORK_OK writer=" + type.getProtectionDomain().getCodeSource().getLocation()
                + " descriptor=" + descriptor.getProtectionDomain().getCodeSource().getLocation());
    }

    private static String responseJson() {
        Class<?> type = frameworkWriter;
        if (type == null) return new Payload(64).json();
        try {
        ByteBuffer buffer = ByteBuffer.allocate(128);
        Object writer = type.getMethod("reusable", ByteBuffer.class, int.class).invoke(null, buffer, 0);
        type.getMethod("beginObject").invoke(writer);
        type.getMethod("fieldString", String.class, String.class).invoke(writer, "item16", "appcds");
        type.getMethod("comma").invoke(writer);
        type.getMethod("fieldInt", String.class, int.class).invoke(writer, "count", 64);
        type.getMethod("endObject").invoke(writer);
        int length = (Integer) type.getMethod("result").invoke(writer);
        byte[] bytes = new byte[length];
        buffer.get(0, bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Actual framework JSON failed", error);
        }
    }

    record Payload(int count) {
        String json() { return "{\"item16\":\"appcds\",\"count\":" + count + "}"; }
    }
}

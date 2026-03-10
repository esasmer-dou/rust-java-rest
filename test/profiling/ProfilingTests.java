package com.reactor.rust.profiling;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonWriter;
import com.reactor.rust.dto.*;
import com.reactor.rust.json.DslJsonService;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance Profiling Tests
 * Identifies bottlenecks in Rust-Spring framework
 *
 * Run: mvn exec:java -Dexec.mainClass="com.reactor.rust.profiling.ProfilingTests"
 */
public class ProfilingTests {

    private static final int WARMUP = 1000;
    private static final int ITERATIONS = 100_000;
    private static final int THREADS = Runtime.getRuntime().availableProcessors();

    public static void main(String[] args) throws Throwable {
        System.out.println("========================================================================");
        System.out.println("               RUST-SPRING PROFILING TESTS");
        System.out.println("========================================================================");
        System.out.println();
        System.out.println("Processors: " + THREADS);
        System.out.println("Warmup: " + WARMUP + ", Iterations: " + ITERATIONS);
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println();

        // Run all tests
        testJsonSerialization_Small();
        testJsonSerialization_Large();
        testJsonDeserialization();
        testByteBufferWrite();
        testParamParsing();
        testParamParsing_Optimized();
        testHandlerInvocation();
        testConcurrentThroughput();
        testAllocationOverhead();

        System.out.println("========================================================================");
        System.out.println("  PROFILING COMPLETED");
        System.out.println("========================================================================");
    }

    // ========================
    // TEST 1: JSON Serialization (Small Object)
    // ========================
    static void testJsonSerialization_Small() {
        System.out.println("=== TEST 1a: JSON Serialization (Small Object) ===");

        // Small object
        OrderCreateRequest req = new OrderCreateRequest(123, 350.75);

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            DslJsonService.serialize(req);
        }

        // Test
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            DslJsonService.serialize(req);
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) ITERATIONS;
        double opsPerSec = 1_000_000_000.0 / avgNs;
        int size = DslJsonService.serialize(req).length;

        System.out.printf("  Average time: %.2f ns (%.3f us)%n", avgNs, avgNs / 1000);
        System.out.printf("  Operations/sec: %.0f%n", opsPerSec);
        System.out.printf("  Output size: %d bytes%n%n", size);
    }

    // ========================
    // TEST 1b: JSON Serialization (Large Object)
    // ========================
    static void testJsonSerialization_Large() {
        System.out.println("=== TEST 1b: JSON Serialization (Large Object - 19 items) ===");

        // Large object with 19 items
        Address address = new Address("Ankara", "Ataturk Cd.");
        Customer customer = new Customer("Test Company", "test@email.com");
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            items.add(new Item("item" + i, 10.0 + i));
        }
        OrderRequest order = new OrderRequest("ORD-001", 350.75, true, address, customer, items);

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            DslJsonService.serialize(order);
        }

        // Test
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            DslJsonService.serialize(order);
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) ITERATIONS;
        double opsPerSec = 1_000_000_000.0 / avgNs;
        int size = DslJsonService.serialize(order).length;

        System.out.printf("  Average time: %.2f ns (%.3f us)%n", avgNs, avgNs / 1000);
        System.out.printf("  Operations/sec: %.0f%n", opsPerSec);
        System.out.printf("  Output size: %d bytes%n%n", size);
    }

    // ========================
    // TEST 2: JSON Deserialization
    // ========================
    static void testJsonDeserialization() {
        System.out.println("=== TEST 2: JSON Deserialization ===");

        // Create test data
        String json = "{\"orderId\":123,\"amount\":350.75}";
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            DslJsonService.parse(jsonBytes, OrderCreateRequest.class);
        }

        // Test
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            DslJsonService.parse(jsonBytes, OrderCreateRequest.class);
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) ITERATIONS;
        double opsPerSec = 1_000_000_000.0 / avgNs;

        System.out.printf("  Average time: %.2f ns (%.3f us)%n", avgNs, avgNs / 1000);
        System.out.printf("  Operations/sec: %.0f%n", opsPerSec);
        System.out.printf("  Input size: %d bytes%n%n", jsonBytes.length);
    }

    // ========================
    // TEST 3: ByteBuffer Write
    // ========================
    static void testByteBufferWrite() {
        System.out.println("=== TEST 3: ByteBuffer Write (Direct) ===");

        ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024);
        byte[] data = "{\"status\":1,\"message\":\"OK\",\"orderId\":15}".getBytes(StandardCharsets.UTF_8);

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            buffer.clear();
            buffer.put(data);
        }

        // Test
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            buffer.clear();
            buffer.put(data);
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) ITERATIONS;
        double opsPerSec = 1_000_000_000.0 / avgNs;

        System.out.printf("  Average time: %.2f ns (%.3f us)%n", avgNs, avgNs / 1000);
        System.out.printf("  Operations/sec: %.0f%n", opsPerSec);
        System.out.printf("  Data size: %d bytes%n%n", data.length);
    }

    // ========================
    // TEST 4a: Param Parsing (Current)
    // ========================
    static void testParamParsing() {
        System.out.println("=== TEST 4a: Param Parsing (Current Implementation) ===");

        String params = "status=pending&page=1&limit=10&sort=created";

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            parseParamsCurrent(params);
        }

        // Test
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            parseParamsCurrent(params);
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) ITERATIONS;
        double opsPerSec = 1_000_000_000.0 / avgNs;

        System.out.printf("  Average time: %.2f ns (%.3f us)%n", avgNs, avgNs / 1000);
        System.out.printf("  Operations/sec: %.0f%n", opsPerSec);
        System.out.printf("  Input: %s%n%n", params);
    }

    // Current implementation (allocates HashMap)
    static Map<String, String> parseParamsCurrent(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String p : s.split("&")) {
            int i = p.indexOf('=');
            if (i > 0) {
                m.put(p.substring(0, i), p.substring(i + 1));
            }
        }
        return m;
    }

    // ========================
    // TEST 4b: Param Parsing (Optimized with ThreadLocal)
    // ========================
    static void testParamParsing_Optimized() {
        System.out.println("=== TEST 4b: Param Parsing (Optimized - ThreadLocal HashMap) ===");

        String params = "status=pending&page=1&limit=10&sort=created";
        ThreadLocal<HashMap<String, String>> cache = ThreadLocal.withInitial(() -> new HashMap<>(8));

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            parseParamsOptimized(params, cache);
        }

        // Test
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            parseParamsOptimized(params, cache);
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) ITERATIONS;
        double opsPerSec = 1_000_000_000.0 / avgNs;

        System.out.printf("  Average time: %.2f ns (%.3f us)%n", avgNs, avgNs / 1000);
        System.out.printf("  Operations/sec: %.0f%n", opsPerSec);
        System.out.printf("  Improvement potential: analyze%n%n", opsPerSec);
    }

    // Optimized implementation (reuses HashMap)
    static Map<String, String> parseParamsOptimized(String s, ThreadLocal<HashMap<String, String>> cache) {
        HashMap<String, String> m = cache.get();
        m.clear();
        if (s == null || s.isEmpty()) return m;
        for (String p : s.split("&")) {
            int i = p.indexOf('=');
            if (i > 0) {
                m.put(p.substring(0, i), p.substring(i + 1));
            }
        }
        return m;
    }

    // ========================
    // TEST 5: Handler Invocation (MethodHandle)
    // ========================
    static void testHandlerInvocation() throws Throwable {
        System.out.println("=== TEST 5: Handler Invocation (MethodHandle) ===");

        // Create a mock handler
        TestHandler handler = new TestHandler();
        java.lang.reflect.Method method = TestHandler.class.getMethod("handle",
            ByteBuffer.class, int.class, byte[].class, String.class, String.class, String.class);
        java.lang.invoke.MethodHandle mh = java.lang.invoke.MethodHandles.lookup()
            .unreflect(method).bindTo(handler);

        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            buffer.clear();
            mh.invoke(buffer, 0, body, "", "", "");
        }

        // Test
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            buffer.clear();
            mh.invoke(buffer, 0, body, "", "", "");
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) ITERATIONS;
        double opsPerSec = 1_000_000_000.0 / avgNs;

        System.out.printf("  Average time: %.2f ns (%.3f us)%n", avgNs, avgNs / 1000);
        System.out.printf("  Operations/sec: %.0f%n%n", opsPerSec);
    }

    static class TestHandler {
        public int handle(ByteBuffer out, int offset, byte[] body, String p, String q, String h) {
            byte[] resp = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            out.position(offset);
            out.put(resp);
            return resp.length;
        }
    }

    // ========================
    // TEST 6: Concurrent Throughput
    // ========================
    static void testConcurrentThroughput() throws Exception {
        System.out.println("=== TEST 6: Concurrent Throughput ===");

        int threads = THREADS;
        int iterationsPerThread = ITERATIONS / threads;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicLong totalTime = new AtomicLong();
        AtomicLong totalOps = new AtomicLong();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);

        // Create test data
        Address address = new Address("Ankara", "Ataturk Cd.");
        Customer customer = new Customer("Test", "test@test.com");
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) items.add(new Item("i" + i, 10.0));
        OrderRequest order = new OrderRequest("ORD-001", 100.0, true, address, customer, items);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.nanoTime();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        DslJsonService.serialize(order);
                    }
                    long end = System.nanoTime();
                    totalTime.addAndGet(end - start);
                    totalOps.addAndGet(iterationsPerThread);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        executor.shutdown();

        double avgNs = totalTime.get() / (double) totalOps.get();
        double opsPerSec = totalOps.get() / (totalTime.get() / 1_000_000_000.0);

        System.out.printf("  Threads: %d%n", threads);
        System.out.printf("  Total operations: %d%n", totalOps.get());
        System.out.printf("  Average time: %.2f ns (%.3f us)%n", avgNs, avgNs / 1000);
        System.out.printf("  Total ops/sec: %.0f%n", opsPerSec);
        System.out.printf("  Ops/sec/thread: %.0f%n%n", opsPerSec / threads);
    }

    // ========================
    // TEST 7: Allocation Overhead Analysis
    // ========================
    static void testAllocationOverhead() {
        System.out.println("=== TEST 7: Allocation Overhead Analysis ===");

        // Test 1: byte[] allocation per call
        long start1 = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            byte[] arr = new byte[64];
        }
        long end1 = System.nanoTime();

        // Test 2: Reused byte[]
        byte[] reused = new byte[64];
        long start2 = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            // Simulate reuse
            for (int j = 0; j < 64; j++) reused[j] = 0;
        }
        long end2 = System.nanoTime();

        // Test 3: HashMap allocation
        long start3 = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            Map<String, String> m = new HashMap<>();
        }
        long end3 = System.nanoTime();

        // Test 4: String allocation
        long start4 = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            String s = "status=pending&page=1";
        }
        long end4 = System.nanoTime();

        System.out.printf("  byte[64] allocation: %.2f ns%n", (end1 - start1) / (double) ITERATIONS);
        System.out.printf("  Reused byte[] clear: %.2f ns%n", (end2 - start2) / (double) ITERATIONS);
        System.out.printf("  HashMap allocation: %.2f ns%n", (end3 - start3) / (double) ITERATIONS);
        System.out.printf("  String literal: %.2f ns%n%n", (end4 - start4) / (double) ITERATIONS);

        System.out.println("BOTTLENECK INDICATORS:");
        System.out.println("  - If byte[] allocation > 50ns: consider buffer pooling");
        System.out.println("  - If HashMap allocation > 100ns: consider ThreadLocal reuse");
        System.out.println("  - GC pressure from frequent allocations reduces throughput");
    }
}

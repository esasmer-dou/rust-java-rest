package com.reactor.rust.profiling;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone Performance Profiling Tests (No JNI/Server dependency)
 */
public class StandaloneProfiling {

    private static final int WARMUP = 1000;
    private static final int ITERATIONS = 100_000;
    private static final int THREADS = Runtime.getRuntime().availableProcessors();

    public static void main(String[] args) throws Throwable {
        System.out.println("========================================================================");
        System.out.println("           RUST-SPRING JAVA PROFILING (Standalone)");
        System.out.println("========================================================================");
        System.out.println();
        System.out.println("Processors: " + THREADS);
        System.out.println("Warmup: " + WARMUP + ", Iterations: " + ITERATIONS);
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("JVM: " + System.getProperty("java.vm.name"));
        System.out.println();

        testStringAllocation();
        testByteBufferWrite();
        testParamParsing_Current();
        testParamParsing_Optimized();
        testHashMapAllocation();
        testMethodHandleInvocation();
        testConcurrentHashMapAccess();
        testAllocationOverhead();

        System.out.println("========================================================================");
        System.out.println("  PROFILING COMPLETED");
        System.out.println("========================================================================");
    }

    static void testStringAllocation() {
        System.out.println("=== TEST 1: String Allocation ===");

        long start1 = System.nanoTime();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            if (i >= WARMUP && i == WARMUP) start1 = System.nanoTime();
            String s = new String("status=pending&page=1");
        }
        long end1 = System.nanoTime();

        long start2 = System.nanoTime();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            if (i >= WARMUP && i == WARMUP) start2 = System.nanoTime();
            String s = "status=pending&page=1";
        }
        long end2 = System.nanoTime();

        long start3 = System.nanoTime();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            if (i >= WARMUP && i == WARMUP) start3 = System.nanoTime();
            StringBuilder sb = new StringBuilder();
            sb.append("status=pending&page=1");
            String s = sb.toString();
        }
        long end3 = System.nanoTime();

        System.out.printf("  new String():        %.2f ns%n", (end1 - start1) / (double) ITERATIONS);
        System.out.printf("  String literal:      %.2f ns%n", (end2 - start2) / (double) ITERATIONS);
        System.out.printf("  StringBuilder:       %.2f ns%n%n", (end3 - start3) / (double) ITERATIONS);
    }

    static void testByteBufferWrite() {
        System.out.println("=== TEST 2: ByteBuffer Write ===");

        byte[] data = "{\"status\":1,\"message\":\"OK\"}".getBytes(StandardCharsets.UTF_8);

        ByteBuffer heapBuffer = ByteBuffer.allocate(64 * 1024);
        long start1 = System.nanoTime();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            if (i >= WARMUP && i == WARMUP) start1 = System.nanoTime();
            heapBuffer.clear();
            heapBuffer.put(data);
        }
        long end1 = System.nanoTime();

        ByteBuffer directBuffer = ByteBuffer.allocateDirect(64 * 1024);
        long start2 = System.nanoTime();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            if (i >= WARMUP && i == WARMUP) start2 = System.nanoTime();
            directBuffer.clear();
            directBuffer.put(data);
        }
        long end2 = System.nanoTime();

        System.out.printf("  Heap ByteBuffer:     %.2f ns%n", (end1 - start1) / (double) ITERATIONS);
        System.out.printf("  Direct ByteBuffer:   %.2f ns%n%n", (end2 - start2) / (double) ITERATIONS);
    }

    static void testParamParsing_Current() {
        System.out.println("=== TEST 3a: Param Parsing (Current Implementation) ===");

        String params = "status=pending&page=1&limit=10&sort=created";

        for (int i = 0; i < WARMUP; i++) {
            parseParamsCurrent(params);
        }

        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            parseParamsCurrent(params);
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) ITERATIONS;
        System.out.printf("  Average time: %.2f ns (%.3f us)%n", avgNs, avgNs / 1000);
        System.out.printf("  Ops/sec: %.0f%n%n", 1_000_000_000.0 / avgNs);
    }

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

    static void testParamParsing_Optimized() {
        System.out.println("=== TEST 3b: Param Parsing (Optimized - ThreadLocal HashMap) ===");

        String params = "status=pending&page=1&limit=10&sort=created&filter=active";
        ThreadLocal<HashMap<String, String>> cache = ThreadLocal.withInitial(() -> new HashMap<>(8));

        for (int i = 0; i < WARMUP; i++) {
            parseParamsOptimized(params, cache);
        }

        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            parseParamsOptimized(params, cache);
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) ITERATIONS;
        System.out.printf("  Average time: %.2f ns (%.3f us)%n", avgNs, avgNs / 1000);
        System.out.printf("  Ops/sec: %.0f%n%n", 1_000_000_000.0 / avgNs);
    }

    static Map<String, String> parseParamsOptimized(String s, ThreadLocal<HashMap<String, String>> cache) {
        HashMap<String, String> m = cache.get();
        m.clear();
        if (s == null || s.isEmpty()) return m;
        int start = 0;
        while (start < s.length()) {
            int amp = s.indexOf('&', start);
            int end = amp >= 0 ? amp : s.length();
            String pair = s.substring(start, end);
            int eq = pair.indexOf('=');
            if (eq > 0) {
                m.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            start = end + 1;
        }
        return m;
    }

    static void testHashMapAllocation() {
        System.out.println("=== TEST 4: HashMap Allocation ===");

        long start1 = System.nanoTime();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            if (i >= WARMUP && i == WARMUP) start1 = System.nanoTime();
            Map<String, String> m = new HashMap<>();
        }
        long end1 = System.nanoTime();

        long start2 = System.nanoTime();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            if (i >= WARMUP && i == WARMUP) start2 = System.nanoTime();
            Map<String, String> m = new HashMap<>(8);
        }
        long end2 = System.nanoTime();

        long start3 = System.nanoTime();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            if (i >= WARMUP && i == WARMUP) start3 = System.nanoTime();
            Map<String, String> m = new ConcurrentHashMap<>();
        }
        long end3 = System.nanoTime();

        System.out.printf("  HashMap():           %.2f ns%n", (end1 - start1) / (double) ITERATIONS);
        System.out.printf("  HashMap(8):          %.2f ns%n", (end2 - start2) / (double) ITERATIONS);
        System.out.printf("  ConcurrentHashMap:   %.2f ns%n%n", (end3 - start3) / (double) ITERATIONS);
    }

    static void testMethodHandleInvocation() throws Throwable {
        System.out.println("=== TEST 5: MethodHandle vs Reflection ===");

        TestHandler handler = new TestHandler();
        java.lang.reflect.Method method = TestHandler.class.getMethod("handle",
            ByteBuffer.class, int.class, byte[].class, String.class, String.class, String.class);
        java.lang.invoke.MethodHandle mh = java.lang.invoke.MethodHandles.lookup()
            .unreflect(method).bindTo(handler);

        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        // Warmup MethodHandle
        for (int i = 0; i < WARMUP; i++) {
            buffer.clear();
            mh.invoke(buffer, 0, body, "", "", "");
        }

        long start1 = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            buffer.clear();
            mh.invoke(buffer, 0, body, "", "", "");
        }
        long end1 = System.nanoTime();

        // Warmup Reflection
        for (int i = 0; i < WARMUP; i++) {
            buffer.clear();
            method.invoke(handler, buffer, 0, body, "", "", "");
        }

        long start2 = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            buffer.clear();
            method.invoke(handler, buffer, 0, body, "", "", "");
        }
        long end2 = System.nanoTime();

        System.out.printf("  MethodHandle:        %.2f ns%n", (end1 - start1) / (double) ITERATIONS);
        System.out.printf("  Reflection:          %.2f ns%n", (end2 - start2) / (double) ITERATIONS);
        System.out.printf("  Speedup:             %.1fx%n%n", (double)(end2 - start2) / (end1 - start1));
    }

    static class TestHandler {
        public int handle(ByteBuffer out, int offset, byte[] body, String p, String q, String h) {
            byte[] resp = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            out.position(offset);
            out.put(resp);
            return resp.length;
        }
    }

    static void testConcurrentHashMapAccess() throws Exception {
        System.out.println("=== TEST 6: Concurrent HashMap Access ===");

        ConcurrentHashMap<Integer, String> map = new ConcurrentHashMap<>();
        for (int i = 0; i < 100; i++) {
            map.put(i, "value" + i);
        }

        int threads = THREADS;
        int iterationsPerThread = ITERATIONS / threads;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicLong totalTime = new AtomicLong();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.nanoTime();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        map.get((threadId + i) % 100);
                    }
                    long end = System.nanoTime();
                    totalTime.addAndGet(end - start);
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

        double avgNs = totalTime.get() / (double) (threads * iterationsPerThread);
        System.out.printf("  Threads: %d%n", threads);
        System.out.printf("  Average get(): %.2f ns%n", avgNs);
        System.out.printf("  Total ops/sec: %.0f%n%n", (threads * iterationsPerThread) / (totalTime.get() / 1_000_000_000.0));
    }

    static void testAllocationOverhead() {
        System.out.println("=== TEST 7: Allocation Overhead Analysis ===");

        long start1 = System.nanoTime();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            if (i >= WARMUP && i == WARMUP) start1 = System.nanoTime();
            byte[] arr = new byte[64];
        }
        long end1 = System.nanoTime();

        byte[] reused = new byte[64];
        long start2 = System.nanoTime();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            if (i >= WARMUP && i == WARMUP) start2 = System.nanoTime();
            for (int j = 0; j < 64; j++) reused[j] = 0;
        }
        long end2 = System.nanoTime();

        long start3 = System.nanoTime();
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            if (i >= WARMUP && i == WARMUP) start3 = System.nanoTime();
            Object[] arr = new Object[10];
        }
        long end3 = System.nanoTime();

        System.out.printf("  byte[64] allocation:  %.2f ns%n", (end1 - start1) / (double) ITERATIONS);
        System.out.printf("  Reused byte[] clear:  %.2f ns%n", (end2 - start2) / (double) ITERATIONS);
        System.out.printf("  Object[10] alloc:     %.2f ns%n%n", (end3 - start3) / (double) ITERATIONS);

        System.out.println("=== BOTTLENECK SUMMARY ===");
        System.out.println("  - byte[] allocation > 50ns: Buffer pooling recommended");
        System.out.println("  - HashMap allocation > 100ns: ThreadLocal reuse recommended");
        System.out.println("  - MethodHandle 5-10x faster than Reflection");
        System.out.println("  - Direct ByteBuffer slower for small writes (< 1KB)");
    }
}

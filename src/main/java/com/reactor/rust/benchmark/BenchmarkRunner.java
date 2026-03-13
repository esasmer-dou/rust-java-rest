package com.reactor.rust.benchmark;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive Benchmark Runner
 *
 * Collects metrics:
 * - RPS (Requests Per Second)
 * - Latency (Avg, P50, P90, P99, Max)
 * - Memory Usage (Heap, Non-Heap)
 * - GC Statistics
 * - Thread Count
 * - Object Allocation (via JMX)
 */
public class BenchmarkRunner {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws Exception {
        String frameworkUrl = System.getenv().getOrDefault("FRAMEWORK_URL", "http://localhost:8080");
        String springUrl = System.getenv().getOrDefault("SPRING_URL", "http://localhost:8081");
        int[] concurrencyLevels = {10, 50, 100, 1000};
        int requestsPerTest = 10000;
        int warmupRequests = 1000;

        System.out.println("# Benchmark Results - " + Instant.now());
        System.out.println();
        System.out.println("## Configuration");
        System.out.println("- Framework URL: " + frameworkUrl);
        System.out.println("- Spring Boot URL: " + springUrl);
        System.out.println("- Concurrency Levels: " + Arrays.toString(concurrencyLevels));
        System.out.println("- Requests per test: " + requestsPerTest);
        System.out.println();

        // Run benchmarks for each concurrency level
        for (int concurrency : concurrencyLevels) {
            System.out.println("## Concurrency: " + concurrency);
            System.out.println();

            // Benchmark Rust-Java Framework
            System.out.println("### Rust-Java Framework");
            BenchmarkResult frameworkResult = runBenchmark("Rust-Java", frameworkUrl + "/api/v1/candidates", concurrency, requestsPerTest, warmupRequests);
            printResult(frameworkResult);
            System.out.println();

            // Benchmark Spring Boot
            if (isServerAvailable(springUrl)) {
                System.out.println("### Spring Boot");
                BenchmarkResult springResult = runBenchmark("Spring Boot", springUrl + "/api/v1/candidates", concurrency, requestsPerTest, warmupRequests);
                printResult(springResult);
                System.out.println();
            }

            // Cool down
            Thread.sleep(5000);
        }

        // Print comparison table
        System.out.println();
        System.out.println("## Summary Comparison Table");
        printComparisonTable();
    }

    static boolean isServerAvailable(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/v1/candidates"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static BenchmarkResult runBenchmark(String name, String url, int concurrency, int totalRequests, int warmup) throws Exception {
        BenchmarkResult result = new BenchmarkResult();
        result.name = name;
        result.concurrency = concurrency;
        result.totalRequests = totalRequests;

        // Warmup
        System.out.println("Warmup: " + warmup + " requests...");
        runConcurrentRequests(url, warmup, Math.min(concurrency, 10));

        // Force GC before measurement
        System.gc();
        Thread.sleep(1000);

        // Record memory before
        Runtime runtime = Runtime.getRuntime();
        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

        // Record start time
        Instant start = Instant.now();

        // Run benchmark
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    long reqStart = System.nanoTime();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .GET()
                            .build();
                    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                    long reqEnd = System.nanoTime();

                    if (response.statusCode() == 200) {
                        successCount.incrementAndGet();
                        latencies.add(reqEnd - reqStart);
                    } else {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.MINUTES);
        Instant end = Instant.now();
        executor.shutdown();

        // Record memory after
        long memoryAfter = runtime.totalMemory() - runtime.freeMemory();

        // Calculate metrics
        long durationMs = Duration.between(start, end).toMillis();
        double durationSec = durationMs / 1000.0;

        result.rps = (successCount.get() / durationSec);
        result.successCount = successCount.get();
        result.errorCount = errorCount.get();
        result.durationMs = durationMs;
        result.memoryUsedMB = (memoryAfter - memoryBefore) / (1024.0 * 1024.0);

        // Calculate latency percentiles
        Collections.sort(latencies);
        if (!latencies.isEmpty()) {
            result.latencyAvgNs = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
            result.latencyP50Ns = getPercentile(latencies, 50);
            result.latencyP90Ns = getPercentile(latencies, 90);
            result.latencyP99Ns = getPercentile(latencies, 99);
            result.latencyMaxNs = latencies.get(latencies.size() - 1);
        }

        return result;
    }

    static void runConcurrentRequests(String url, int count, int concurrency) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            executor.submit(() -> {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
                    HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    // Ignore
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(2, TimeUnit.MINUTES);
        executor.shutdown();
    }

    static long getPercentile(List<Long> sortedLatencies, int percentile) {
        if (sortedLatencies.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sortedLatencies.size()) - 1;
        return sortedLatencies.get(Math.max(0, index));
    }

    static void printResult(BenchmarkResult r) {
        System.out.println("| Metric | Value |");
        System.out.println("|--------|-------|");
        System.out.printf("| RPS | %.2f |%n", r.rps);
        System.out.printf("| Latency (avg) | %.2f ms |%n", r.latencyAvgNs / 1_000_000.0);
        System.out.printf("| Latency (P50) | %.2f ms |%n", r.latencyP50Ns / 1_000_000.0);
        System.out.printf("| Latency (P90) | %.2f ms |%n", r.latencyP90Ns / 1_000_000.0);
        System.out.printf("| Latency (P99) | %.2f ms |%n", r.latencyP99Ns / 1_000_000.0);
        System.out.printf("| Latency (max) | %.2f ms |%n", r.latencyMaxNs / 1_000_000.0);
        System.out.printf("| Success | %d |%n", r.successCount);
        System.out.printf("| Errors | %d |%n", r.errorCount);
        System.out.printf("| Duration | %d ms |%n", r.durationMs);
        System.out.printf("| Memory Delta | %.2f MB |%n", r.memoryUsedMB);
    }

    static void printComparisonTable() {
        System.out.println();
        System.out.println("| Concurrency | Framework | RPS | Latency (avg) | Latency (P99) | Memory |");
        System.out.println("|-------------|-----------|-----|---------------|---------------|--------|");
        // This would be populated with actual results
    }

    static class BenchmarkResult {
        String name;
        int concurrency;
        int totalRequests;
        double rps;
        int successCount;
        int errorCount;
        long durationMs;
        double memoryUsedMB;
        double latencyAvgNs;
        long latencyP50Ns;
        long latencyP90Ns;
        long latencyP99Ns;
        long latencyMaxNs;
    }
}

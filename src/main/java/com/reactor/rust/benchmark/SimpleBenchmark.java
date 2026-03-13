package com.reactor.rust.benchmark;

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
 * Simple Load Test for Rust-Java REST Framework.
 * Run with: java -cp target/rust-java-rest-2.0.0.jar com.reactor.rust.benchmark.SimpleBenchmark
 */
public class SimpleBenchmark {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "http://localhost:8080/api/v1/candidates";
        int[] concurrencies = {10, 50, 100, 1000};
        int requestsPerConcurrency = 10000;

        System.out.println("# Rust-Java REST Framework Benchmark Results");
        System.out.println();
        System.out.println("**Test Date:** " + Instant.now());
        System.out.println();
        System.out.println("**Endpoint:** " + url);
        System.out.println();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Warmup
        System.out.println("## Warmup");
        runWarmup(client, url, 500);
        System.out.println();

        System.out.println("## Benchmark Results");
        System.out.println();
        System.out.println("| Concurrency | RPS | Avg Latency (ms) | P99 Latency (ms) | Success Rate | Memory (MB) |");
        System.out.println("|-------------|-----|------------------|------------------|--------------|-------------|");

        Runtime runtime = Runtime.getRuntime();

        for (int concurrency : concurrencies) {
            // Force GC before test
            System.gc();
            Thread.sleep(500);

            long memBefore = runtime.totalMemory() - runtime.freeMemory();

            // Run benchmark
            Instant start = Instant.now();
            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            CountDownLatch latch = new CountDownLatch(requestsPerConcurrency);

            for (int i = 0; i < requestsPerConcurrency; i++) {
                executor.submit(() -> {
                    try {
                        long reqStart = System.nanoTime();
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .timeout(Duration.ofSeconds(30))
                                .GET()
                                .build();
                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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

            long memAfter = runtime.totalMemory() - runtime.freeMemory();

            // Calculate metrics
            double durationSec = Duration.between(start, end).toMillis() / 1000.0;
            double rps = successCount.get() / durationSec;

            Collections.sort(latencies);
            double avgLatencyMs = latencies.isEmpty() ? 0 :
                latencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
            long p99Ns = latencies.isEmpty() ? 0 : latencies.get((int)(latencies.size() * 0.99));
            double p99LatencyMs = p99Ns / 1_000_000.0;
            double successRate = (successCount.get() * 100.0) / requestsPerConcurrency;
            double memoryMB = (memAfter - memBefore) / (1024.0 * 1024.0);

            System.out.printf("| %d | %.0f | %.2f | %.2f | %.1f%% | %.1f |%n",
                concurrency, rps, avgLatencyMs, p99LatencyMs, successRate, memoryMB);

            // Cool down
            Thread.sleep(2000);
        }

        System.out.println();
        System.out.println("## Memory Footprint");
        System.out.println();
        System.out.println("| Metric | Value |");
        System.out.println("|--------|-------|");
        System.out.printf("| Heap Used | %.1f MB |%n", (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0));
        System.out.printf("| Heap Max | %.1f MB |%n", runtime.maxMemory() / (1024.0 * 1024.0));
        System.out.printf("| Total Memory | %.1f MB |%n", runtime.totalMemory() / (1024.0 * 1024.0));
    }

    static void runWarmup(HttpClient client, String url, int count) throws Exception {
        System.out.print("Running " + count + " warmup requests... ");
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            executor.submit(() -> {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
                    client.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    // Ignore
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(1, TimeUnit.MINUTES);
        executor.shutdown();
        System.out.println("Done");
    }
}

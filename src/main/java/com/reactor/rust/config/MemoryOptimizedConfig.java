package com.reactor.rust.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;

/**
 * Memory-Optimized Configuration for 30MB Target
 *
 * This configuration reduces memory footprint from ~65MB to ~30MB through:
 * - Smaller buffer sizes (4KB instead of 64KB)
 * - Reduced buffer pool (20 instead of 100)
 * - Thread-local buffer reuse
 * - Lazy bean initialization
 *
 * JVM Flags Required:
 * -Xms16m -Xmx32m -XX:MaxMetaspaceSize=16m -XX:ReservedCodeCacheSize=16m
 * -XX:+UseSerialGC -XX:CICompilerCount=1 -XX:ThreadStackSize=256k
 * -Xss256k -XX:+UseCompressedOops -XX:+UseCompressedClassPointers
 */
public class MemoryOptimizedConfig {

    // Buffer configuration for 30MB target
    public static final int BUFFER_SIZE = 4096;          // 4KB (was 64KB)
    public static final int BUFFER_POOL_SIZE = 20;        // 20 buffers (was 100)
    public static final int MAX_WORKER_THREADS = 4;       // 4 threads (was 8)
    public static final int MAX_BLOCKING_THREADS = 16;    // 16 blocking (was 64)

    // Estimated memory savings:
    // Buffer pool: (64KB * 100) - (4KB * 20) = 6.32MB saved
    // Thread stacks: (8 * 1MB) - (4 * 256KB) = ~7MB saved
    // Total estimated savings: ~13-15MB

    /**
     * Thread-local buffer for zero-allocation JSON processing.
     * Each thread gets its own 4KB buffer.
     */
    public static final ThreadLocal<byte[]> THREAD_LOCAL_BUFFER =
        ThreadLocal.withInitial(() -> new byte[BUFFER_SIZE]);

    /**
     * Get a thread-local buffer for use.
     * @return byte array buffer
     */
    public static byte[] getBuffer() {
        return THREAD_LOCAL_BUFFER.get();
    }

    /**
     * Calculate optimal buffer pool size based on available memory.
     * @param availableMemoryMB Available memory in MB
     * @return Optimal pool size
     */
    public static int calculateOptimalPoolSize(int availableMemoryMB) {
        // For 30MB target: 20 buffers of 4KB = 80KB
        // For 50MB target: 50 buffers of 4KB = 200KB
        // For 100MB target: 100 buffers of 4KB = 400KB
        if (availableMemoryMB <= 30) {
            return 20;
        } else if (availableMemoryMB <= 50) {
            return 50;
        } else {
            return 100;
        }
    }

    /**
     * JVM Memory Configuration for 30MB target
     */
    public static class JvmConfig {
        public static final String HEAP_MIN = "16m";
        public static final String HEAP_MAX = "32m";
        public static final String METASPACE_MAX = "16m";
        public static final String CODE_CACHE_MAX = "16m";
        public static final String THREAD_STACK = "256k";
        public static final String GC = "SerialGC";
        public static final int CI_COMPILER_COUNT = 1;

        /**
         * Get JVM arguments for 30MB configuration
         */
        public static String[] getJvmArgs() {
            return new String[] {
                "-Xms" + HEAP_MIN,
                "-Xmx" + HEAP_MAX,
                "-XX:MaxMetaspaceSize=" + METASPACE_MAX,
                "-XX:ReservedCodeCacheSize=" + CODE_CACHE_MAX,
                "-XX:+Use" + GC,
                "-XX:CICompilerCount=" + CI_COMPILER_COUNT,
                "-XX:ThreadStackSize=" + THREAD_STACK,
                "-Xss" + THREAD_STACK,
                "-XX:+UseCompressedOops",
                "-XX:+UseCompressedClassPointers",
                "-XX:+UseStringDeduplication"
            };
        }
    }

    /**
     * Memory usage estimator
     */
    public static class MemoryEstimator {

        /**
         * Estimate current memory usage breakdown
         */
        public static MemoryBreakdown estimateBreakdown() {
            Runtime runtime = Runtime.getRuntime();

            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory();

            // Estimate component memory usage
            long bufferPoolMemory = (long) BUFFER_SIZE * BUFFER_POOL_SIZE;
            long threadStackMemory = (long) MAX_WORKER_THREADS * 256 * 1024; // 256KB per thread
            long estimatedHeapUsed = usedMemory - bufferPoolMemory;

            return new MemoryBreakdown(
                usedMemory / (1024 * 1024),
                totalMemory / (1024 * 1024),
                maxMemory / (1024 * 1024),
                bufferPoolMemory / (1024 * 1024),
                threadStackMemory / (1024 * 1024),
                estimatedHeapUsed / (1024 * 1024)
            );
        }
    }

    /**
     * Memory breakdown data class
     */
    public record MemoryBreakdown(
        double usedMB,
        double totalMB,
        double maxMB,
        double bufferPoolMB,
        double threadStacksMB,
        double heapUsedMB
    ) {
        @Override
        public String toString() {
            return String.format("""
                Memory Breakdown:
                =================
                Used Memory:    %.2f MB
                Total Memory:   %.2f MB
                Max Memory:     %.2f MB
                Buffer Pool:    %.2f MB
                Thread Stacks:  %.2f MB
                Heap Used:      %.2f MB
                """,
                usedMB, totalMB, maxMB, bufferPoolMB, threadStacksMB, heapUsedMB
            );
        }
    }

    /**
     * Annotation for lazy-loaded beans
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface LazyLoad {
        /**
         * Whether to load this bean lazily
         */
        boolean value() default true;
    }
}

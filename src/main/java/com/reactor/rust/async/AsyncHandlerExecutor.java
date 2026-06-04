package com.reactor.rust.async;

import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.config.PropertiesLoader;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Async execution support for CompletableFuture handlers.
 *
 * Uses virtual threads (Java 21) for optimal performance.
 */
public final class AsyncHandlerExecutor {

    private static final AsyncHandlerExecutor INSTANCE = new AsyncHandlerExecutor();

    private final ExecutorService executor;
    private final boolean virtualThreadsAvailable;
    private final Semaphore inFlight;
    private final int maxInflight;

    private AsyncHandlerExecutor() {
        this.virtualThreadsAvailable = isVirtualThreadAvailable();
        this.maxInflight = Math.max(1, PropertiesLoader.getInt("reactor.rust.async.max-inflight", 1024));
        this.inFlight = new Semaphore(maxInflight);
        this.executor = virtualThreadsAvailable
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(
                        Math.max(1, Runtime.getRuntime().availableProcessors()),
                        r -> {
                            Thread t = new Thread(r);
                            t.setName("async-handler-" + t.threadId());
                            t.setDaemon(true);
                            return t;
                        }
                );

        FrameworkLogger.info("[AsyncExecutor] Initialized (virtualThreads="
                + virtualThreadsAvailable + ", maxInflight=" + maxInflight + ")");
    }

    public static AsyncHandlerExecutor getInstance() {
        return INSTANCE;
    }

    /**
     * Check if virtual threads are available (Java 21+)
     */
    private boolean isVirtualThreadAvailable() {
        try {
            // Try to create a virtual thread
            Thread.ofVirtual().start(() -> {}).join(10);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Submit async task and return CompletableFuture.
     */
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        if (!inFlight.tryAcquire()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("async handler bulkhead full"));
        }
        try {
            return CompletableFuture.supplyAsync(task, executor)
                    .whenComplete((ignored, error) -> inFlight.release());
        } catch (RuntimeException e) {
            inFlight.release();
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Submit async task with timeout.
     */
    public <T> CompletableFuture<T> submit(Supplier<T> task, long timeoutMs) {
        return submit(task)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Get the appropriate executor.
     */
    public Executor getExecutor() {
        return executor;
    }

    /**
     * Shutdown executor (for graceful shutdown).
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        FrameworkLogger.info("[AsyncExecutor] Shutdown complete");
    }
}

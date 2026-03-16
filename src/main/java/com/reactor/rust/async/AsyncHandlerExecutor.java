package com.reactor.rust.async;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Async execution support for CompletableFuture handlers.
 *
 * Uses virtual threads (Java 21) for optimal performance.
 */
public final class AsyncHandlerExecutor {

    private static final AsyncHandlerExecutor INSTANCE = new AsyncHandlerExecutor();

    // Virtual thread executor (Java 21+)
    private final ExecutorService virtualThreadExecutor;

    // Fallback thread pool for non-virtual thread JVMs
    private final ExecutorService fallbackExecutor;

    private final boolean virtualThreadsAvailable;

    private AsyncHandlerExecutor() {
        this.virtualThreadsAvailable = isVirtualThreadAvailable();
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.fallbackExecutor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("async-handler-" + t.threadId());
                    t.setDaemon(true);
                    return t;
                }
        );

        System.out.println("[AsyncExecutor] Initialized (virtualThreads=" + virtualThreadsAvailable + ")");
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
        Executor executor = virtualThreadsAvailable ? virtualThreadExecutor : fallbackExecutor;
        return CompletableFuture.supplyAsync(task, executor);
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
        return virtualThreadsAvailable ? virtualThreadExecutor : fallbackExecutor;
    }

    /**
     * Shutdown executor (for graceful shutdown).
     */
    public void shutdown() {
        virtualThreadExecutor.shutdown();
        fallbackExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
            }
            if (!fallbackExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                fallbackExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            virtualThreadExecutor.shutdownNow();
            fallbackExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("[AsyncExecutor] Shutdown complete");
    }
}

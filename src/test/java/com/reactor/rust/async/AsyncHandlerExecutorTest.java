package com.reactor.rust.async;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncHandlerExecutorTest {

    @Test
    void completesSingleFutureWithoutChangingResultContract() {
        assertEquals("ok", AsyncHandlerExecutor.getInstance().submit(() -> "ok").join());
    }

    @Test
    void propagatesTaskFailureAndReleasesExecutionPath() {
        IllegalStateException failure = new IllegalStateException("expected");

        CompletionException completion = assertThrows(
                CompletionException.class,
                () -> AsyncHandlerExecutor.getInstance().submit(() -> {
                    throw failure;
                }).join()
        );

        assertSame(failure, completion.getCause());
        assertEquals("next", AsyncHandlerExecutor.getInstance().submit(() -> "next").join());
    }

    @Test
    void releasesTaskPermitBeforeDependentCompletionWork() throws Exception {
        AsyncHandlerExecutor executor = AsyncHandlerExecutor.getInstance();
        CountDownLatch allowTaskCompletion = new CountDownLatch(1);
        CountDownLatch dependentStarted = new CountDownLatch(1);
        CountDownLatch allowDependentCompletion = new CountDownLatch(1);

        var task = executor.submit(() -> {
            try {
                allowTaskCompletion.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return "ok";
        });
        var dependent = task.thenApply(value -> {
            dependentStarted.countDown();
            try {
                allowDependentCompletion.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return value;
        });

        allowTaskCompletion.countDown();
        assertTrue(dependentStarted.await(5, TimeUnit.SECONDS));
        assertEquals(executor.maxInflight(), executor.availablePermits());
        allowDependentCompletion.countDown();
        assertEquals("ok", dependent.join());
    }
}

package com.reactor.rust.concurrent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KeyAdmissionSupportTest {
    @Test
    void activeAdmissionDoesNotMapSynchronousBusinessRejections() throws Throwable {
        assertBusinessRejectionIsNotMapped(true);
    }

    @Test
    void disabledAdmissionDoesNotMapSynchronousBusinessRejections() throws Throwable {
        assertBusinessRejectionIsNotMapped(false);
    }

    private static void assertBusinessRejectionIsNotMapped(boolean enabled) throws Throwable {
        for (boolean stringKey : new boolean[] {false, true}) {
            LongKeyAdmission admission = new LongKeyAdmission(enabled, 1, 1);
            RejectedExecutionException businessFailure = new RejectedExecutionException("business executor rejected");
            AtomicInteger mappings = new AtomicInteger();
            KeyAdmissionSupport.Action<String> action = () -> { throw businessFailure; };
            KeyAdmissionSupport.Rejection<String> onRejected = error -> {
                mappings.incrementAndGet();
                return "admission rejected";
            };
            assertSame(businessFailure, assertThrows(RejectedExecutionException.class, () -> {
                if (stringKey) KeyAdmissionSupport.invoke(admission, "customer-42", action, onRejected);
                else KeyAdmissionSupport.invoke(admission, 42L, action, onRejected);
            }));
            assertEquals(0, mappings.get());
            assertEquals("released", stringKey
                    ? KeyAdmissionSupport.invoke(admission, "customer-42", () -> "released", onRejected)
                    : KeyAdmissionSupport.invoke(admission, 42L, () -> "released", onRejected));
            assertEquals(0, mappings.get());
            assertTrue(admission.metricsJson().contains(enabled ? "\"accepted\":2" : "\"accepted\":0"));
            assertTrue(admission.metricsJson().contains("\"rejected\":0"));
        }
    }

    @Test
    void namedRejectionPreservesTheErrorAndNeverInvokesTheRejectedAction() throws Throwable {
        LongKeyAdmission admission = new LongKeyAdmission(true, 1, 1);
        CompletableFuture<String> source = new CompletableFuture<>();
        AtomicInteger actions = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        CompletableFuture<String> returned = KeyAdmissionSupport.invoke(admission, 42L, () -> source,
                error -> { fail("accepted action must not use the rejection mapper"); return null; });
        assertSame(source, returned);
        CompletableFuture<Integer> cancelled = returned.thenApply(String::length);
        assertTrue(cancelled.cancel(false));
        assertEquals("legacy-503", KeyAdmissionSupport.invoke(admission, 42L,
                () -> { actions.incrementAndGet(); return "not admitted"; }, error -> {
                    assertEquals("command key is already in flight", error.getMessage());
                    rejections.incrementAndGet();
                    return "legacy-503";
                }));
        assertEquals(0, actions.get());
        assertEquals(1, rejections.get());
        assertFalse(source.isDone());
        source.complete("ready");
        CompletableFuture<String> next = new CompletableFuture<>();
        KeyAdmissionSupport.invoke(admission, 42L, () -> next, error -> null);
        assertThrows(RejectedExecutionException.class,
                () -> KeyAdmissionSupport.invoke(admission, 42L, () -> "no double release"));
        next.completeExceptionally(new IOException("upstream failed"));
        assertEquals("released", KeyAdmissionSupport.invoke(admission, 42L, () -> "released"));
    }

    @Test
    void stringRejectionAndMapperFailureDoNotReleaseSomeOtherCallsPermit() throws Throwable {
        LongKeyAdmission admission = new LongKeyAdmission(true, 1, 1);
        CompletableFuture<String> source = new CompletableFuture<>();
        KeyAdmissionSupport.invoke(admission, "customer-42", () -> source);
        IOException mapperFailure = new IOException("mapper failed");
        assertSame(mapperFailure, assertThrows(IOException.class,
                () -> KeyAdmissionSupport.invoke(admission, "customer-42", () -> "not admitted",
                        error -> { throw mapperFailure; })));
        assertThrows(RejectedExecutionException.class,
                () -> KeyAdmissionSupport.invoke(admission, "customer-42", () -> "still held"));
        source.cancel(false);
        IOException actionFailure = new IOException("action failed");
        assertSame(actionFailure, assertThrows(IOException.class,
                () -> KeyAdmissionSupport.invoke(admission, "customer-42", () -> { throw actionFailure; },
                        error -> { fail("ordinary failures must not use the rejection mapper"); return null; })));
        assertEquals("ready", KeyAdmissionSupport.invoke(admission, "customer-42", () -> "ready", error -> "rejected"));
    }

    @Test
    void synchronousValuesNullAndCheckedFailuresReleaseAdmission() throws Throwable {
        LongKeyAdmission admission = new LongKeyAdmission(true, 1, 1);
        String value = KeyAdmissionSupport.invoke(admission, 42L, () -> "ready");
        assertEquals("ready", value);
        IOException failure = new IOException("failed command");
        assertSame(failure, assertThrows(IOException.class,
                () -> KeyAdmissionSupport.invoke(admission, 42L, () -> { throw failure; })));
        assertNull(KeyAdmissionSupport.invoke(admission, 42L, () -> null));
        assertEquals("next", KeyAdmissionSupport.invoke(admission, 42L, () -> "next"));
        assertTrue(admission.metricsJson().contains("\"accepted\":4"));
    }

    @Test
    void stringKeysUseDeterministicBoundedHashingWithoutPerKeyState() throws Throwable {
        assertEquals(0xcbf29ce484222325L, LongKeyAdmission.stableHash(""));
        assertEquals(0xa430d84680aabd0bL, LongKeyAdmission.stableHash("hello"));
        assertEquals(LongKeyAdmission.stableHash("customer-42"),
                LongKeyAdmission.stableHash(new String("customer-42")));
        LongKeyAdmission admission = new LongKeyAdmission(true, 1, 16);
        CompletableFuture<String> pending = new CompletableFuture<>();
        assertSame(pending, KeyAdmissionSupport.invoke(admission, "customer-42", () -> pending));
        assertThrows(RejectedExecutionException.class,
                () -> KeyAdmissionSupport.invoke(admission, new String("customer-42"), () -> "blocked"));
        pending.completeExceptionally(new IOException("failed"));
        assertEquals("next", admission.execute("customer-42",
                () -> CompletableFuture.completedFuture("next")).join());
        assertThrows(NullPointerException.class,
                () -> KeyAdmissionSupport.invoke(admission, (String) null, () -> "unused"));
        assertTrue(admission.metricsJson().contains("\"rejected\":1"));
    }

    @Test
    void cancellingADependentCannotReleaseEarlyOrSkipTheCompletionObserver() throws Throwable {
        LongKeyAdmission admission = new LongKeyAdmission(true, 1, 1);
        CompletableFuture<String> pending = new CompletableFuture<>();
        CompletableFuture<String> returned = KeyAdmissionSupport.invoke(admission, 1L, () -> pending);
        assertSame(pending, returned);
        CompletableFuture<Integer> dependent = returned.thenApply(String::length);
        assertTrue(dependent.cancel(false));
        assertThrows(RejectedExecutionException.class,
                () -> KeyAdmissionSupport.invoke(admission, 1L, () -> "blocked"));
        pending.complete("ready");
        assertEquals("next", KeyAdmissionSupport.invoke(admission, 1L, () -> "next"));
    }

    @Test
    void imperativeCallerCancellationDoesNotCancelUpstreamOrSkipRelease() {
        LongKeyAdmission admission = new LongKeyAdmission(true, 1, 1);
        CompletableFuture<String> pending = new CompletableFuture<>();
        CompletableFuture<String> returned = admission.execute(1L, () -> pending);
        assertNotSame(pending, returned);
        assertTrue(returned.cancel(false));
        assertFalse(pending.isCancelled());
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> admission.execute(1L, () -> CompletableFuture.completedFuture("blocked")).join());
        pending.complete("finished after caller cancellation");
        CompletableFuture<String> next = new CompletableFuture<>();
        CompletableFuture<String> admittedNext = admission.execute(1L, () -> next);
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> admission.execute(1L, () -> CompletableFuture.completedFuture("blocked")).join());
        next.complete("ready");
        assertEquals("ready", admittedNext.join());
        assertEquals("next", admission.execute(1L, () -> CompletableFuture.completedFuture("next")).join());
    }

    @Test
    void failedObserverRegistrationCannotLeakOrDoubleRelease() throws Throwable {
        LongKeyAdmission admission = new LongKeyAdmission(true, 1, 1);
        CompletableFuture<String> brokenStage = new CompletableFuture<>() {
            @Override
            public CompletableFuture<String> whenComplete(BiConsumer<? super String, ? super Throwable> action) {
                action.accept("ready", null);
                throw new RejectedExecutionException("observer registration failed");
            }
        };
        assertThrows(RejectedExecutionException.class,
                () -> KeyAdmissionSupport.invoke(admission, 1L, () -> brokenStage));
        CompletableFuture<String> pending = new CompletableFuture<>();
        KeyAdmissionSupport.invoke(admission, 1L, () -> pending);
        assertThrows(RejectedExecutionException.class,
                () -> KeyAdmissionSupport.invoke(admission, 1L, () -> "blocked"));
        pending.complete("done");
    }

    @Test
    void disabledAdmissionIsAPassthroughAndNullOrThrownActionsDoNotLeak() throws Throwable {
        LongKeyAdmission disabled = new LongKeyAdmission(false, 1, 1);
        CompletableFuture<String> pending = new CompletableFuture<>();
        assertSame(pending, KeyAdmissionSupport.invoke(disabled, "key", () -> pending));
        assertEquals("parallel", KeyAdmissionSupport.invoke(disabled, "key", () -> "parallel"));
        assertTrue(disabled.metricsJson().contains("\"accepted\":0"));
        LongKeyAdmission enabled = new LongKeyAdmission(true, 1, 1);
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> enabled.execute(1L, () -> null).join());
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> enabled.execute(1L, () -> { throw new IllegalStateException("failed"); }).join());
        assertEquals("next", KeyAdmissionSupport.invoke(enabled, 1L, () -> "next"));
    }
}

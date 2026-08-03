package com.reactor.rust.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongKeyAdmissionTest {

    @Test
    void rejectsTheSameBusyStripeAndReleasesAfterCompletion() {
        LongKeyAdmission admission = new LongKeyAdmission(true, 1, 16);
        CompletableFuture<String> first = new CompletableFuture<>();

        CompletableFuture<String> accepted = admission.execute(42L, () -> first);
        CompletableFuture<String> rejected = admission.execute(42L, () -> CompletableFuture.completedFuture("x"));

        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class,
                rejected::join);
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        first.complete("ok");
        assertEquals("ok", accepted.join());
        assertEquals("next", admission.execute(
                42L,
                () -> CompletableFuture.completedFuture("next")).join());
        assertTrue(admission.metricsJson().contains("\"accepted\":2"));
        assertTrue(admission.metricsJson().contains("\"rejected\":1"));
    }

    @Test
    void disabledAdmissionDoesNotReject() {
        LongKeyAdmission admission = new LongKeyAdmission(false, 1, 1);
        assertEquals("ok", admission.execute(
                1L,
                () -> CompletableFuture.completedFuture("ok")).join());
    }
}

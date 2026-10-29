package com.reactor.rust.bridge;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AsyncResponseCompletionsTest {
    @Test void immediateCompletionPreservesResultAndFailure() {
        var results = new ArrayList<Object>();
        var bridge = new AsyncResponseCompletions<String>((id, frame, error) -> results.add(error == null ? frame : error));
        bridge.observe(1, CompletableFuture.completedFuture("ok"));
        var failure = new IllegalArgumentException("invalid");
        bridge.observe(2, CompletableFuture.failedFuture(failure));
        assertEquals(java.util.List.of("ok", failure), results);
    }

    @Test void expiredNativeOwnerStillReceivesLateFrameForCleanup() {
        var nativeOwner = new AtomicBoolean(true);
        var released = new ArrayList<String>();
        var bridge = new AsyncResponseCompletions<String>((id, frame, error) -> {
            assertEquals(7, id);
            assertFalse(nativeOwner.get());
            released.add(frame);
        });
        var source = new CompletableFuture<String>();
        bridge.observe(7, source);
        nativeOwner.set(false);
        assertFalse(source.isDone(), "Only Rust expires the HTTP request, not the source frame future");
        source.complete("late-native-frame");
        assertEquals(java.util.List.of("late-native-frame"), released);
        assertFalse(source.complete("duplicate"));
    }

    @Test void cancellationAndLongRequestIdAreForwardedUnchanged() {
        var errors = new ArrayList<Throwable>();
        var bridge = new AsyncResponseCompletions<String>((id, frame, error) -> {
            assertEquals(Long.MAX_VALUE, id);
            errors.add(error);
        });
        var source = new CompletableFuture<String>();
        bridge.observe(Long.MAX_VALUE, source);
        source.cancel(false);
        assertEquals(1, errors.size());
        assertInstanceOf(CancellationException.class, errors.getFirst());
    }

    @Test void pendingCallsNeedNoJavaRequestRegistryOrDeadlineOwner() {
        var results = new ArrayList<Long>();
        var bridge = new AsyncResponseCompletions<String>((id, frame, error) -> results.add(id));
        var calls = new ArrayList<CompletableFuture<String>>();
        for (int id = 1; id <= 128; id++) {
            var source = new CompletableFuture<String>();
            calls.add(source);
            bridge.observe(id, source);
        }
        assertTrue(results.isEmpty());
        for (var source : calls) source.complete("ok");
        assertEquals(128, results.size());
        assertEquals(1, AsyncResponseCompletions.class.getDeclaredFields().length,
                "The Java bridge only holds its completion sink, not another pending registry or timer");
    }
}

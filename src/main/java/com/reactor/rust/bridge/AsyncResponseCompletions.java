package com.reactor.rust.bridge;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Forwards every completion, including late owned frames, to the native request owner. */
final class AsyncResponseCompletions<T> {
    private final Completion<T> completion;

    AsyncResponseCompletions(Completion<T> completion) {
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    void observe(long requestId, CompletableFuture<T> source) {
        Objects.requireNonNull(source, "source");
        // Rust owns the deadline and bounded request registry. A second Java
        // timeout must not consume a late frame before native cleanup sees it.
        source.whenComplete((frame, error) -> completion.accept(requestId, frame, error));
    }

    @FunctionalInterface
    interface Completion<T> {
        void accept(long requestId, T frame, Throwable error);
    }

}

package com.reactor.rust.bridge;

import java.util.concurrent.CompletionStage;

/** Optional route guard. Instances are selected once while the route plan is built. */
public interface RequestGuard {
    void before(RequestGuardContext request);

    default void after() {}

    /** Completes a synchronous invocation and exposes its failure to observability guards. */
    default void after(Throwable failure) {
        after();
    }

    /**
     * Transfers completion to an asynchronous result. Context-only guards use the default,
     * which clears thread-local state immediately. Observability guards may wrap the stage.
     */
    default <T> CompletionStage<T> afterAsync(CompletionStage<T> stage) {
        after(null);
        return stage;
    }
}

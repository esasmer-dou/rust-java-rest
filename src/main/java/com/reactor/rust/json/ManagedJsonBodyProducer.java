package com.reactor.rust.json;

/**
 * One-shot, resource-owning producer. Keep ownership after a negative size result;
 * release it after successful writing or an exception. The framework also closes
 * a retained producer when native buffer retry is aborted. close must be idempotent
 * and must not throw. Unlike ordinary producers, a completed producer is not replayable.
 */
public interface ManagedJsonBodyProducer extends JsonBodyProducer, AutoCloseable {
    @Override void close();
}

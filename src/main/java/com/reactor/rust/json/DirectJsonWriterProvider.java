package com.reactor.rust.json;

/**
 * ServiceLoader extension point for build-time generated direct JSON writers.
 *
 * <p>A generated provider should return a writer only for exact DTO classes it owns.
 * Returning broad reflection-based writers defeats the purpose of this path.</p>
 */
public interface DirectJsonWriterProvider {
    DirectJsonWriter<?> findWriter(Class<?> type);
}

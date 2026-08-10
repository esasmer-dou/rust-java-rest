package com.reactor.rust.exception;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionHandlerRegistryTest {

    private final ExceptionHandlerRegistry registry = ExceptionHandlerRegistry.getInstance();

    @AfterEach
    void cleanup() {
        registry.clear();
    }

    @Test
    void generatedHandlerDispatchesWithoutReflectionFallback() {
        Handler bean = new Handler();
        registry.registerGenerated(
                bean,
                IllegalArgumentException.class,
                (target, error) -> ((Handler) target).invalid((IllegalArgumentException) error));

        Object result = registry.handleException(new IllegalArgumentException("invalid id"));

        assertEquals("invalid id", result);
        assertEquals(1, registry.generatedHandlerCount());
        assertEquals(0, registry.reflectionFallbackCount());
        assertTrue(registry.hasHandler(IllegalArgumentException.class));
    }

    @Test
    void generatedThrowableHandlerActsAsGlobalFallback() {
        registry.registerGenerated(new Handler(), Throwable.class, (target, error) -> "fallback");

        assertEquals("fallback", registry.handleException(new IllegalStateException("failed")));
    }

    private static final class Handler {
        String invalid(IllegalArgumentException error) {
            return error.getMessage();
        }
    }
}

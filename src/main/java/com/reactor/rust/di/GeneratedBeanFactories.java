package com.reactor.rust.di;

import com.reactor.rust.di.exception.BeanCreationException;

/** Executes build-time generated bean factories without reflective invocation. */
public final class GeneratedBeanFactories {

    private GeneratedBeanFactories() {
    }

    public static <T> T create(String description, ThrowingSupplier<? extends T> factory) {
        try {
            return factory.get();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new BeanCreationException("Failed to create generated bean: " + description, failure);
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }
}

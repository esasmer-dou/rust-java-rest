package com.reactor.rust.concurrent;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/** Reflection-free bridge for generated synchronous and asynchronous route invokers. */
public final class KeyAdmissionSupport {
    private KeyAdmissionSupport() {}

    @FunctionalInterface
    public interface Action<T> {
        T get() throws Throwable;
    }

    @FunctionalInterface
    public interface Rejection<T> {
        T apply(RejectedExecutionException error) throws Throwable;
    }

    public static <T> T invoke(LongKeyAdmission admission, long key, Action<T> action) throws Throwable {
        return Objects.requireNonNull(admission, "admission")
                .invoke(key, Objects.requireNonNull(action, "action"));
    }

    public static <T> T invoke(LongKeyAdmission admission, String key, Action<T> action) throws Throwable {
        return invoke(admission, LongKeyAdmission.stableHash(key), action);
    }

    public static <T> T invoke(
            LongKeyAdmission admission, long key, Action<T> action, Rejection<T> onRejected) throws Throwable {
        Objects.requireNonNull(onRejected, "onRejected");
        return Objects.requireNonNull(admission, "admission")
                .invoke(key, Objects.requireNonNull(action, "action"), onRejected);
    }

    public static <T> T invoke(
            LongKeyAdmission admission, String key, Action<T> action, Rejection<T> onRejected) throws Throwable {
        return invoke(admission, LongKeyAdmission.stableHash(key), action, onRejected);
    }
}

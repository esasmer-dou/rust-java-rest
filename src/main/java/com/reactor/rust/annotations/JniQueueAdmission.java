package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-route JNI queue admission control.
 *
 * <p>This differs from {@link RouteAdmission}. Route admission limits full route
 * in-flight work until the response completes. JNI queue admission gives a hot
 * route its own bounded native JNI lane, and releases its permit when a JNI worker
 * starts executing the job.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JniQueueAdmission {

    /**
     * Maximum route-local jobs allowed in the native JNI lane. A non-positive
     * value disables this route-local lane unless properties override it.
     */
    int maxPending() default -1;

    /**
     * Maximum time a request may wait for a pending slot before receiving 503.
     * For hot bodyless/direct routes, prefer zero so overload fails fast instead
     * of adding a second async wait before the native queue.
     */
    int queueTimeoutMs() default -1;
}

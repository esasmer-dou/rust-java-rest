package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-route native admission control.
 *
 * <p>The limiter is enforced on the Rust async side before a request enters the
 * JNI worker queue. It is intended for expensive routes where bounded waiting is
 * safer than allowing one route to fill the global JNI queue.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RouteAdmission {

    /**
     * Maximum in-flight requests for this route. A non-positive value disables
     * route-level admission unless a properties override supplies a positive value.
     */
    int maxConcurrent() default -1;

    /**
     * Maximum time a request may wait for a route permit before receiving 503.
     * Zero means fail fast when the route is saturated.
     */
    int queueTimeoutMs() default -1;
}

package com.reactor.rust.http.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Opt-in, per-method circuit breaker generated for a {@link ReactorHttpClient}.
 * A method annotation replaces the entire interface configuration.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpCircuitBreaker {
    int windowSize() default 64;
    int minimumCalls() default 16;
    int failureRateThreshold() default 50;
    long openDurationMs() default 10_000L;
    int halfOpenMaxCalls() default 2;
    boolean record5xxAsFailure() default true;
}

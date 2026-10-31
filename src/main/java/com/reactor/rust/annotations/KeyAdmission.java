package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Uses a named, bounded {@code LongKeyAdmission} bean for a generated HTTP route.
 * The key names a direct {@link PathVariable} or {@link RequestParam} parameter
 * (its Java name or binding name), whose type must be {@code long} or {@link String}.
 * Async routes retain admission until their returned stage completes; no waiting
 * queue or per-key allocation is introduced. Hash/stripe collisions share capacity.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface KeyAdmission {
    String bean();
    String key();

    /**
     * Optional public instance method on this handler, accepting one {@link Throwable}
     * and returning the route's declared return type (or a subtype). Invoked directly
     * only when permit acquisition throws {@link java.util.concurrent.RejectedExecutionException};
     * business and asynchronous failures remain the route's responsibility. Requires generated startup.
     */
    String onRejected() default "";
}

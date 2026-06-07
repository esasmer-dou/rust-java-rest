package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the operational workload class of a route.
 *
 * <p>This is startup metadata, not a hot-path feature flag. The framework uses
 * it to report route intent and to resolve optional profile-level route budgets
 * before the route is registered with Rust.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RouteWorkload {

    Type value() default Type.STANDARD;

    /**
     * Optional named budget key. Use this when the same workload class needs
     * different measured budgets, for example heavy-json-direct vs
     * heavy-json-producer.
     */
    String budget() default "";

    enum Type {
        STANDARD,
        SMALL_JSON,
        HEAVY_JSON,
        RAW_STATIC,
        FILE_STREAM,
        BLOCKING_IO,
        RPC
    }
}

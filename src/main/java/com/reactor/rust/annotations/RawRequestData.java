package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicit raw request metadata contract for direct-buffer handlers.
 *
 * <p>Direct V5 handlers can receive raw path/query/header strings, but producing
 * those strings from Rust has a cost. Keep everything disabled unless the handler
 * actually reads the value.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RawRequestData {
    boolean pathParams() default false;

    boolean query() default false;

    boolean headers() default false;
}

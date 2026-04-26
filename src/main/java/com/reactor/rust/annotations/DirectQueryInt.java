package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Low-allocation hot-path query integer binding for direct response writers.
 *
 * <p>Supported handler signature:
 * {@code int handler(ByteBuffer out, int offset, int value)}.
 * Rust parses the selected query parameter and passes the primitive int through JNI,
 * avoiding Java query String allocation and per-request query parsing.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DirectQueryInt {
    String value();

    int defaultValue() default 0;

    int min() default Integer.MIN_VALUE;

    int max() default Integer.MAX_VALUE;
}

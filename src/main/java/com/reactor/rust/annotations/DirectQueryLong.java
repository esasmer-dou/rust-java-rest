package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Low-allocation hot-path query long binding for direct response writers.
 *
 * <p>Supported handler signature:
 * {@code int handler(ByteBuffer out, int offset, long value)}.
 * Rust parses the selected query parameter and passes the primitive long through JNI,
 * avoiding Java query String allocation and per-request query parsing.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DirectQueryLong {
    String value();

    long defaultValue() default 0L;

    long min() default Long.MIN_VALUE;

    long max() default Long.MAX_VALUE;
}

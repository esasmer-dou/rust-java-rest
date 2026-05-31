package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Low-allocation hot-path path-variable int binding for direct response writers.
 *
 * <p>Supported handler signature:
 * {@code int handler(ByteBuffer out, int offset, int value)}.
 * Rust extracts the selected path variable and passes the primitive int through JNI,
 * avoiding Java path-param String allocation and per-request map lookup.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DirectPathInt {
    String value();

    int min() default Integer.MIN_VALUE;

    int max() default Integer.MAX_VALUE;
}

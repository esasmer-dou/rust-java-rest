package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Low-allocation hot-path path-variable long binding for direct response writers.
 *
 * <p>Supported handler signature:
 * {@code int handler(ByteBuffer out, int offset, long value)}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DirectPathLong {
    String value();

    long min() default Long.MIN_VALUE;

    long max() default Long.MAX_VALUE;
}

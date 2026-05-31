package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Low-allocation hot-path path-variable double binding for direct response writers.
 *
 * <p>Supported handler signature:
 * {@code int handler(ByteBuffer out, int offset, double value)}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DirectPathDouble {
    String value();

    double min() default -Double.MAX_VALUE;

    double max() default Double.MAX_VALUE;
}

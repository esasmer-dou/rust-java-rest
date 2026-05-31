package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Low-allocation hot-path query short binding for direct response writers.
 *
 * <p>Supported handler signature:
 * {@code int handler(ByteBuffer out, int offset, short value)}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DirectQueryShort {
    String value();

    short defaultValue() default 0;

    short min() default Short.MIN_VALUE;

    short max() default Short.MAX_VALUE;
}

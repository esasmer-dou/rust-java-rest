package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Low-allocation hot-path query boolean binding for direct response writers.
 *
 * <p>Supported handler signature:
 * {@code int handler(ByteBuffer out, int offset, boolean value)}.
 * Rust parses common boolean forms ({@code true/false, 1/0, yes/no, on/off})
 * and passes the primitive boolean through JNI.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DirectQueryBoolean {
    String value();

    boolean defaultValue() default false;
}

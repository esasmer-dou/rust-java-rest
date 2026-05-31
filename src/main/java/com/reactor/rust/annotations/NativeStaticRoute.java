package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a route as an immutable native static response.
 *
 * <p>The handler is invoked once during route registration and must return a
 * {@code RawResponse} already registered in native memory. Runtime requests are
 * served directly by Rust without entering the Java handler.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NativeStaticRoute {
}

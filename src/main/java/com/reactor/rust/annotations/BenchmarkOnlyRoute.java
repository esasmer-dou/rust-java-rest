package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a route as benchmark/demo-only.
 *
 * <p>Benchmark-only routes stay visible in diagnostics, but production gates and production route
 * metrics do not count them as application route violations. Use this only for bundled comparison
 * routes, never for hiding a real production hot path.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BenchmarkOnlyRoute {
    String value() default "";
}

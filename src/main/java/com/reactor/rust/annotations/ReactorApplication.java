package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the application entry point used by the generated startup descriptor.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReactorApplication {

    /** Human-readable application name used by generated documentation. */
    String name() default "";

    /** Application API version used by generated documentation. */
    String version() default "1.0.0";

    /** Short application description used by generated documentation. */
    String description() default "";

    /** Packages to scan. When empty, the application package is used. */
    String[] scanBasePackages() default {};

    /** Enables framework-owned health, WebSocket and static-file lifecycle hooks. */
    boolean standardRuntime() default false;

    /** Adds the optional built-in metrics and diagnostics routes without a manual module. */
    boolean metrics() default false;
}

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

    /** Packages to scan. When empty, the application package is used. */
    String[] scanBasePackages() default {};

    /** Enables framework-owned health, WebSocket and static-file lifecycle hooks. */
    boolean standardRuntime() default false;
}

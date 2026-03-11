package com.reactor.rust.di.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Field injection annotation.
 * Zero-overhead: resolved at startup, cached for runtime.
 *
 * Example:
 * @Autowired
 * private UserService userService;
 */
@Target({ElementType.FIELD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.RUNTIME)
public @interface Autowired {
    /**
     * Whether the dependency is required.
     * If true and bean not found, throws exception.
     */
    boolean required() default true;
}

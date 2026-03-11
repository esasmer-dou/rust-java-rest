package com.reactor.rust.di.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lifecycle callback - called after dependency injection is complete.
 *
 * Example:
 * @PostConstruct
 * public void init() {
 *     // initialization logic
 * }
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostConstruct {
}

package com.reactor.rust.di.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level annotation for @Configuration classes.
 * The return value is registered as a bean.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Bean {
    /**
     * Optional bean name. If empty, method name is used.
     */
    String value() default "";
}

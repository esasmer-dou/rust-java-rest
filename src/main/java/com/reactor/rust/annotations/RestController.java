package com.reactor.rust.annotations;

import com.reactor.rust.di.annotation.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a generated DI component whose methods expose HTTP routes.
 */
@Component
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RestController {

    /** Base path shared by all route methods in the controller. */
    String value() default "";
}

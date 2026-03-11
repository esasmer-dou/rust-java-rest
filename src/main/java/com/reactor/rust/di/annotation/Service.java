package com.reactor.rust.di.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specialized @Component for service layer beans.
 * Semantically indicates a service class in the business logic layer.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface Service {
    /**
     * Optional bean name.
     */
    String value() default "";
}

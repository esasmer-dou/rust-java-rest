package com.reactor.rust.di.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a Spring-style component for automatic bean registration.
 *
 * <p>Classes annotated with @Component will be automatically detected
 * and registered in the BeanContainer during application startup.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @Component
 * public class UserService {
 *     // Automatically registered as singleton bean
 * }
 * }</pre>
 *
 * @see Service
 * @see Repository
 * @see Configuration
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Component {
    /**
     * Optional bean name. If empty, class simple name with lowercase first letter is used.
     */
    String value() default "";
}

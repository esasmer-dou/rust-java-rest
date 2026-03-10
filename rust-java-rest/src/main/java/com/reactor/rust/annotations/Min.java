package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * The annotated element must be a number whose value must be greater than or equal to the specified minimum.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Min {
    long value();
    String message() default "must be greater than or equal to {value}";
}

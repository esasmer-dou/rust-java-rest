package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * The annotated element must be a positive number (greater than 0).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Positive {
    String message() default "must be positive";
}

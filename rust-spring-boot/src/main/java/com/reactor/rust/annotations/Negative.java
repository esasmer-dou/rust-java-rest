package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * The annotated element must be a negative number (less than 0).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Negative {
    String message() default "must be negative";
}

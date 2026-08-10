package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * The annotated element must be a number whose value must be less than or equal to the specified maximum.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Max {
    long value();
    String message() default "must be less than or equal to {value}";
}

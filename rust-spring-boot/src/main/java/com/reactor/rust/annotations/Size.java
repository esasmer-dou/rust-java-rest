package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * The annotated element size must be between the specified boundaries.
 * Supports String, Collection, Map, and arrays.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Size {
    int min() default 0;
    int max() default Integer.MAX_VALUE;
    String message() default "size must be between {min} and {max}";
}

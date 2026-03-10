package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * The annotated element must not be null nor empty.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NotEmpty {
    String message() default "must not be empty";
}

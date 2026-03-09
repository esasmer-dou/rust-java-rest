package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * The annotated element must be a well-formed email address.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Email {
    String message() default "must be a valid email address";
}

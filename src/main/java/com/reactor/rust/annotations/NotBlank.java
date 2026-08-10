package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * The annotated element must not be null and must contain at least one non-whitespace character.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotBlank {
    String message() default "must not be blank";
}

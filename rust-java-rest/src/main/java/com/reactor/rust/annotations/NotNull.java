package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * The annotated element must not be null.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NotNull {
    String message() default "must not be null";
}

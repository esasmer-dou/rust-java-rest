package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * The annotated CharSequence must match the specified regular expression.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Pattern {
    String regexp();
    String message() default "must match pattern '{regexp}'";
}

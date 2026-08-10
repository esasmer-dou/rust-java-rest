package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Enables a generated bean when a startup property matches. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RequiresProperties.class)
public @interface RequiresProperty {
    String name();
    String value() default "";
    boolean matchIfMissing() default false;
}

package com.reactor.rust.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Build-time OpenAPI operation documentation. It is not retained at runtime. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface Operation {
    String operationId() default "";
    String summary() default "";
    String description() default "";
    String[] tags() default {};
}

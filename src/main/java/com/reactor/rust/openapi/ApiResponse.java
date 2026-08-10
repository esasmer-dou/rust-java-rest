package com.reactor.rust.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Adds a documented response to the generated OpenAPI contract. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@Repeatable(ApiResponses.class)
public @interface ApiResponse {
    int status();
    String description();
    Class<?> body() default Void.class;
}

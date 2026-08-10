package com.reactor.rust.http.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares an interface whose implementation is generated at build time. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface ReactorHttpClient {
    String name() default "";

    String baseUrlProperty();
}

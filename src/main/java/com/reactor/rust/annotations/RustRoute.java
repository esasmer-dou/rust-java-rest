package com.reactor.rust.annotations;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RustRoute {
    String method();
    String path();
    Class<?> requestType();
    Class<?> responseType();
}

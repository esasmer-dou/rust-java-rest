package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation for mapping HTTP PATCH requests onto specific handler methods.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PatchMapping {
    /**
     * Path for this route.
     * Combined with class-level @RequestMapping if present.
     */
    String value();
}

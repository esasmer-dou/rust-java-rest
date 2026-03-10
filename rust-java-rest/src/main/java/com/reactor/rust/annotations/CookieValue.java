package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation to bind a method parameter to an HTTP cookie.
 *
 * Example:
 * <pre>
 * {@code
 * @GetMapping("/profile")
 * public ResponseEntity<User> getProfile(@CookieValue("session") String sessionId) { ... }
 * }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CookieValue {
    /**
     * Name of the cookie to bind to.
     */
    String value();

    /**
     * Whether the cookie is required.
     */
    boolean required() default false;

    /**
     * Default value if cookie is not provided.
     */
    String defaultValue() default "";
}

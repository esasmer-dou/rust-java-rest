package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation to specify the Content-Type header for the response.
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @GetMapping("/data")
 * @ContentType("application/json")
 * public String getData() {
 *     return "{\"status\":\"ok\"}";
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ContentType {
    /**
     * The MIME type for the Content-Type header.
     */
    String value();
}

package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation to bind a method parameter to an HTTP header.
 *
 * Example:
 * <pre>
 * {@code
 * @PostMapping("/orders")
 * public ResponseEntity<Order> createOrder(
 *     @RequestBody OrderRequest request,
 *     @HeaderParam("X-Request-ID") String requestId,
 *     @HeaderParam(value = "X-Api-Version", defaultValue = "1.0") String version
 * ) { ... }
 * }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface HeaderParam {
    /**
     * Name of the HTTP header to bind to (case-insensitive).
     */
    String value();

    /**
     * Whether the header is required.
     */
    boolean required() default false;

    /**
     * Default value if header is not provided.
     */
    String defaultValue() default "";
}

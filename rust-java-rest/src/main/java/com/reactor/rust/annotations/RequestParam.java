package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation to bind a method parameter to a web request parameter.
 *
 * Example:
 * <pre>
 * {@code
 * @GetMapping("/orders")
 * public ResponseEntity<List<Order>> searchOrders(
 *     @RequestParam(value = "status", required = false) String status,
 *     @RequestParam(value = "page", defaultValue = "1") int page
 * ) { ... }
 * }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestParam {
    /**
     * Name of the request parameter to bind to.
     */
    String value();

    /**
     * Whether the parameter is required.
     * If true and parameter is missing, throws BadRequestException.
     */
    boolean required() default true;

    /**
     * Default value if parameter is not provided.
     * Ignored if required is true.
     */
    String defaultValue() default "";
}

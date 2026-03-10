package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation to bind a method parameter to the body of the web request.
 *
 * The body is automatically deserialized using DSL-JSON.
 *
 * Example:
 * <pre>
 * {@code
 * @PostMapping("/orders")
 * public ResponseEntity<Order> createOrder(@RequestBody @Valid OrderRequest request) { ... }
 * }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestBody {
    /**
     * Whether the body is required.
     * If true and body is empty, throws BadRequestException.
     */
    boolean required() default true;
}

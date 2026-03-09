package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Marks a method with the HTTP status code to return.
 *
 * Example:
 * <pre>
 * {@code
 * @PostMapping("/orders")
 * @ResponseStatus(HttpStatus.CREATED)
 * public ResponseEntity<Order> createOrder(@RequestBody OrderRequest request) { ... }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResponseStatus {
    /**
     * The HTTP status code to return.
     */
    int value() default 200;
}

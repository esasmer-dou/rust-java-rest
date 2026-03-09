package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation to bind a method parameter to a URI template variable.
 *
 * Example:
 * <pre>
 * {@code
 * @GetMapping("/orders/{id}")
 * public ResponseEntity<Order> getOrder(@PathVariable("id") String orderId) { ... }
 * }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PathVariable {
    /**
     * Name of the path variable to bind to.
     */
    String value();
}

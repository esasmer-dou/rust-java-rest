package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Class-level annotation for base path prefix.
 * Combined with method-level @GetMapping, @PostMapping, etc.
 *
 * Example:
 * <pre>
 * {@code
 * @RequestMapping("/api/v1")
 * public class OrderController {
 *     @GetMapping("/orders")
 *     public ResponseEntity<List<Order>> getOrders() { ... }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestMapping {
    /**
     * Base path prefix for all methods in this class.
     * Should start with "/" but not end with "/".
     */
    String value() default "";
}

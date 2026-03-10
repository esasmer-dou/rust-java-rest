package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation for mapping HTTP GET requests onto specific handler methods.
 *
 * Example:
 * <pre>
 * {@code
 * @GetMapping("/orders/{id}")
 * public ResponseEntity<Order> getOrder(@PathVariable("id") String id) { ... }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GetMapping {
    /**
     * Path for this route.
     * Combined with class-level @RequestMapping if present.
     */
    String value();
}

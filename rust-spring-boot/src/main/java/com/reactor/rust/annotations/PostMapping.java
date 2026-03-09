package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation for mapping HTTP POST requests onto specific handler methods.
 *
 * Example:
 * <pre>
 * {@code
 * @PostMapping("/orders")
 * @ResponseStatus(HttpStatus.CREATED)
 * public ResponseEntity<Order> createOrder(@RequestBody @Valid OrderRequest request) { ... }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostMapping {
    /**
     * Path for this route.
     * Combined with class-level @RequestMapping if present.
     */
    String value();
}

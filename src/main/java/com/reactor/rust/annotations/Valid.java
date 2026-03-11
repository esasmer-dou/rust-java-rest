package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Triggers validation of the annotated parameter.
 * Used with @RequestBody to validate the deserialized object.
 *
 * Example:
 * <pre>
 * {@code
 * @PostMapping("/orders")
 * public ResponseEntity<Order> create(@RequestBody @Valid OrderRequest request) { ... }
 * }
 * </pre>
 */
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Valid {
}

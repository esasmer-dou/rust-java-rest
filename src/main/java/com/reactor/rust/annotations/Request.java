package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Request Record'larını işaretler (Constraint #9).
 * REST API request body'leri için kullanılır.
 *
 * Örnek:
 * @Request
 * public record OrderCreateRequest(int orderId, double amount) {}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Request {
}

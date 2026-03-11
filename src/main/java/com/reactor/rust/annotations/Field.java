package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Request/Response Record field'ları için konfigürasyon (Constraint #9).
 *
 * Örnek:
 * @Request
 * public record OrderCreateRequest(
 *     @Field(required = true)
 *     String orderId,
 *
 *     @Field(min = 0, max = 1000000)
 *     double amount,
 *
 *     @Field(defaultValue = "PENDING")
 *     String status
 * ) {}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Field {
    /**
     * Alan zorunlu mu?
     */
    boolean required() default false;

    /**
     * Minimum değer (sayısal alanlar için).
     */
    double min() default Double.MIN_VALUE;

    /**
     * Maximum değer (sayısal alanlar için).
     */
    double max() default Double.MAX_VALUE;

    /**
     * Varsayılan değer.
     */
    String defaultValue() default "";

    /**
     * Regex pattern (String alanlar için).
     */
    String pattern() default "";
}

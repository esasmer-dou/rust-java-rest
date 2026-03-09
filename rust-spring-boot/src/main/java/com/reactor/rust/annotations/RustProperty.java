package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Properties bazlı configuration injection (Constraint #8).
 * rust-spring.properties dosyasından değerleri inject eder.
 *
 * Örnek:
 * @RustProperty("server.port")
 * private int port;
 *
 * @RustProperty(value = "db.pool.size", defaultValue = "10")
 * private int poolSize;
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RustProperty {
    /**
     * Properties dosyasındaki key.
     */
    String value();

    /**
     * Varsayılan değer (opsiyonel).
     */
    String defaultValue() default "";
}

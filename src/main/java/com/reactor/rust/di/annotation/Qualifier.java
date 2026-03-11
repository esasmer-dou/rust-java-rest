package com.reactor.rust.di.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Qualifier for disambiguating beans when multiple candidates exist.
 *
 * Example:
 * @Autowired
 * @Qualifier("primaryDataSource")
 * private DataSource dataSource;
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Qualifier {
    String value();
}

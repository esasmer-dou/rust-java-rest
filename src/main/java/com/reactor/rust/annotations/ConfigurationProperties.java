package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an immutable, build-time generated configuration record.
 *
 * <p>The startup processor generates direct property reads and a constructor call. No reflective
 * field injection or runtime proxy is used.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface ConfigurationProperties {

    /** Property prefix, for example {@code reactor.http}. */
    String value();
}

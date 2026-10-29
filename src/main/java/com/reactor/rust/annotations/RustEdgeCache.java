package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Explicit public GET projection; a hit bypasses the Java handler and its guards. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RustEdgeCache {
    String versionKey();
    /** Prefix/template containing exactly one {version}; the encoded path parameter is appended. */
    String dataKeyPrefix();
    String pathParam();
    long maxAgeMs() default 0;
    boolean serveStale() default false;
}

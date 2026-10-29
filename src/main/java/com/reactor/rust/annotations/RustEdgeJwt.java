package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Opt-in HS256 early rejection. Valid tokens still run the Java authentication/authorization guard. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface RustEdgeJwt {
    /** Same configuration prefix as the Java verifier. Secrets must come from runtime configuration. */
    String value() default "reactor.security.jwt";
}

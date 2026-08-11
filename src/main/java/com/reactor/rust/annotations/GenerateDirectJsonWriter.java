package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requests a build-time, exact-class direct JSON writer for a supported record DTO.
 * The generated writer is registered before route compilation and bound once to
 * each matching route; request handling does not perform lazy registry lookup.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateDirectJsonWriter {
}

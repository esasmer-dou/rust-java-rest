package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an immutable FileResponse route that can be served directly by Rust.
 *
 * <p>The handler is invoked once during startup. Runtime requests bypass the Java
 * handler and JNI response frame; Rust opens and streams the registered file path.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NativeStaticFileRoute {
}

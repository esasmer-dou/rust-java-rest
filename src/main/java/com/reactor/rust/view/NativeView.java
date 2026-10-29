package com.reactor.rust.view;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-in compile-time fixed-header/relative-tail view, never registered on existing routes.
 * All multibyte fields are big endian regardless of ByteBuffer order. Long/double slots
 * align to 8 bytes, int/float and relative-offset/length descriptors to 4, short/char to 2.
 * Boolean, byte and unsigned enum ordinals occupy one byte. Descriptors cannot reference
 * the header and are bounded by the supplied record length. Padding is written as zero.
 *
 * Schema versions are out-of-band: no magic, version or total-length prefix is inserted.
 * The transport must select the matching schema and supply the record boundary.
 * Reference fields are non-null. Enums support 1..256 values; nested views must be acyclic
 * and their SOURCE metadata visible in the compilation. Generic schemas are rejected.
 * Each schema is bounded to 256 fields, nesting depth 32 and 1024 retained view/slice
 * objects, including repeated branches of a nested graph.
 *
 * Generated binary writers preserve output position/limit/order. JSON appends to output
 * and may leave partial bytes on failure; discard that output rather than blindly retrying.
 * Integer, enum and UTF-8 JSON paths do not materialize strings. Float/double formatting
 * may allocate and rejects non-finite numbers. Output must not alias any input storage,
 * including ByteBuffer duplicate/slice aliases, which cannot generally be detected.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface NativeView {
    int schemaVersion() default 1;
}

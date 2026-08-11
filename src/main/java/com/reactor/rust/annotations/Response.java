package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Response Record'larını işaretler (Constraint #9).
 * REST API response body'leri için kullanılır. Bu annotation tek başına direct JSON writer
 * seçmez; ölçülmüş serialization-heavy DTO'lar için ayrıca
 * {@link GenerateDirectJsonWriter} kullanılabilir.
 *
 * Örnek:
 * @Response
 * public record OrderResponse(String id, String status, double total) {}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Response {
}

package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation for mapping HTTP PATCH requests onto specific handler methods.
 *
 * Example:
 * <pre>
 * {@code
 * @PatchMapping(value = "/orders/{id}/status", requestType = StatusUpdateRequest.class, responseType = OrderResponse.class)
 * public int updateStatus(ByteBuffer out, int offset, byte[] body, String pathParams) { ... }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PatchMapping {
    /**
     * Path for this route.
     * Combined with class-level @RequestMapping if present.
     */
    String value();

    /**
     * Request body type (for deserialization).
     * Use Void.class for no request body (default).
     */
    Class<?> requestType() default Void.class;

    /**
     * Response body type (for serialization).
     * Required for proper JSON serialization.
     */
    Class<?> responseType() default Void.class;
}

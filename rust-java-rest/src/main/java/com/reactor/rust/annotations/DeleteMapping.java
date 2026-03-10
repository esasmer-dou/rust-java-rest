package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation for mapping HTTP DELETE requests onto specific handler methods.
 *
 * Example:
 * <pre>
 * {@code
 * @DeleteMapping(value = "/orders/{id}", responseType = DeleteResponse.class)
 * public int deleteOrder(ByteBuffer out, int offset, byte[] body, String pathParams) { ... }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DeleteMapping {
    /**
     * Path for this route.
     * Combined with class-level @RequestMapping if present.
     */
    String value();

    /**
     * Request body type (for deserialization).
     * Use Void.class for no request body (default for DELETE).
     */
    Class<?> requestType() default Void.class;

    /**
     * Response body type (for serialization).
     * Required for proper JSON serialization.
     */
    Class<?> responseType() default Void.class;
}

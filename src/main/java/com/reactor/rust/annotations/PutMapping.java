package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation for mapping HTTP PUT requests onto specific handler methods.
 *
 * Example:
 * <pre>
 * {@code
 * @PutMapping(value = "/orders/{id}", requestType = OrderRequest.class, responseType = OrderResponse.class)
 * public int updateOrder(ByteBuffer out, int offset, byte[] body, String pathParams) { ... }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PutMapping {
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

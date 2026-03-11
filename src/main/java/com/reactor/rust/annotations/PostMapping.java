package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation for mapping HTTP POST requests onto specific handler methods.
 *
 * Example:
 * <pre>
 * {@code
 * @PostMapping(value = "/orders", requestType = OrderRequest.class, responseType = OrderResponse.class)
 * public int createOrder(ByteBuffer out, int offset, byte[] body) { ... }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostMapping {
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

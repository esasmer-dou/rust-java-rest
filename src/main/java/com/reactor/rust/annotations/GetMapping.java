package com.reactor.rust.annotations;

import java.lang.annotation.*;

/**
 * Annotation for mapping HTTP GET requests onto specific handler methods.
 *
 * Example:
 * <pre>
 * {@code
 * @GetMapping(value = "/orders/{id}", responseType = OrderResponse.class)
 * public int getOrder(ByteBuffer out, int offset, byte[] body, String pathParams) { ... }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GetMapping {
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

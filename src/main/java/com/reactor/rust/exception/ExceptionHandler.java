package com.reactor.rust.exception;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation used to mark a method as an exception handler.
 * The method will be called when an exception of the specified type is thrown.
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * @Component
 * public class GlobalExceptionHandler {
 *
 *     @ExceptionHandler(NotFoundException.class)
 *     public ErrorResponse handleNotFound(NotFoundException e) {
 *         return new ErrorResponse(404, e.getMessage());
 *     }
 *
 *     @ExceptionHandler({BadRequestException.class, ValidationException.class})
 *     public ResponseEntity<ErrorResponse> handleClientErrors(Exception e) {
 *         return ResponseEntity.status(400).body(new ErrorResponse(400, e.getMessage()));
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExceptionHandler {
    /**
     * Exceptions handled by this method.
     * If empty, the method parameter type will be used.
     */
    Class<? extends Throwable>[] value() default {};
}

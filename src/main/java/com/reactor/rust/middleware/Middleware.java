package com.reactor.rust.middleware;

/**
 * Middleware interface for request/response interception.
 * Middleware can modify requests, responses, or short-circuit the chain.
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * public class LoggingMiddleware implements Middleware {
 *     @Override
 *     public MiddlewareChain.Result process(MiddlewareContext context, MiddlewareChain chain) {
 *         System.out.println("Request: " + context.method() + " " + context.path());
 *         MiddlewareChain.Result result = chain.next(context);
 *         System.out.println("Response: " + result.status());
 *         return result;
 *     }
 * }
 * }</pre>
 */
public interface Middleware {

    /**
     * Process the request/response.
     *
     * @param context The request context
     * @param chain The middleware chain to continue processing
     * @return The result (response or continuation)
     */
    MiddlewareChain.Result process(MiddlewareContext context, MiddlewareChain chain);

    /**
     * Get the order priority of this middleware.
     * Lower values execute first.
     */
    default int getOrder() {
        return 100;
    }
}

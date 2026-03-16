package com.reactor.rust.middleware.builtins;

import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.middleware.Middleware;
import com.reactor.rust.middleware.MiddlewareChain;
import com.reactor.rust.middleware.MiddlewareContext;

/**
 * Built-in middleware for request/response logging.
 */
@Component
public class LoggingMiddleware implements Middleware {

    @Override
    public MiddlewareChain.Result process(MiddlewareContext context, MiddlewareChain chain) {
        long startTime = System.currentTimeMillis();

        // Log request
        System.out.println("[HTTP] --> " + context.method() + " " + context.path() +
                (context.queryString() != null && !context.queryString().isEmpty() ? "?" + context.queryString() : ""));

        // Process chain
        MiddlewareChain.Result result = chain.next(context);

        // Log response
        long duration = System.currentTimeMillis() - startTime;
        String status;
        if (result instanceof MiddlewareChain.Result.Response r) {
            status = String.valueOf(r.status());
        } else if (result instanceof MiddlewareChain.Result.ResponseWithHeaders r) {
            status = String.valueOf(r.status());
        } else {
            status = "200";
        }

        System.out.println("[HTTP] <-- " + context.method() + " " + context.path() +
                " " + status + " (" + duration + "ms)");

        return result;
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE; // Run last (outermost)
    }
}

package com.reactor.rust.middleware;

/**
 * Middleware chain for executing middleware in sequence.
 */
public final class MiddlewareChain {

    /**
     * Result of middleware processing.
     * Either continues to handler or returns a response directly.
     */
    public sealed interface Result {
        /**
         * Continue to next middleware or handler.
         */
        record Continue() implements Result {}

        /**
         * Return a response immediately (short-circuit).
         */
        record Response(int status, String body) implements Result {
            public static Response ok(String body) {
                return new Response(200, body);
            }
            public static Response created(String body) {
                return new Response(201, body);
            }
            public static Response badRequest(String body) {
                return new Response(400, body);
            }
            public static Response unauthorized(String body) {
                return new Response(401, body);
            }
            public static Response forbidden(String body) {
                return new Response(403, body);
            }
            public static Response notFound(String body) {
                return new Response(404, body);
            }
            public static Response internalError(String body) {
                return new Response(500, body);
            }
        }

        /**
         * Return a response with custom headers.
         */
        record ResponseWithHeaders(int status, String body, java.util.Map<String, String> headers) implements Result {
            public static ResponseWithHeaders of(int status, String body, java.util.Map<String, String> headers) {
                return new ResponseWithHeaders(status, body, headers);
            }
        }
    }

    private final java.util.List<Middleware> middlewares;
    private final int currentIndex;
    private final MiddlewareHandler terminalHandler;

    /**
     * Functional interface for the terminal handler (actual route handler).
     */
    @FunctionalInterface
    public interface MiddlewareHandler {
        Result handle(MiddlewareContext context);
    }

    public MiddlewareChain(java.util.List<Middleware> middlewares, MiddlewareHandler terminalHandler) {
        this(middlewares, 0, terminalHandler);
    }

    private MiddlewareChain(java.util.List<Middleware> middlewares, int currentIndex, MiddlewareHandler terminalHandler) {
        this.middlewares = middlewares;
        this.currentIndex = currentIndex;
        this.terminalHandler = terminalHandler;
    }

    /**
     * Process the next middleware in the chain.
     */
    public Result next(MiddlewareContext context) {
        if (currentIndex >= middlewares.size()) {
            // No more middleware - execute terminal handler
            return terminalHandler.handle(context);
        }

        Middleware current = middlewares.get(currentIndex);
        MiddlewareChain nextChain = new MiddlewareChain(middlewares, currentIndex + 1, terminalHandler);

        return current.process(context, nextChain);
    }

    /**
     * Create a builder for constructing middleware chains.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for MiddlewareChain.
     */
    public static class Builder {
        private final java.util.List<Middleware> middlewares = new java.util.ArrayList<>();
        private MiddlewareHandler terminalHandler = ctx -> new Result.Continue();

        public Builder add(Middleware middleware) {
            middlewares.add(middleware);
            return this;
        }

        public Builder terminalHandler(MiddlewareHandler handler) {
            this.terminalHandler = handler;
            return this;
        }

        public MiddlewareChain build() {
            // Sort by order
            middlewares.sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
            return new MiddlewareChain(middlewares, terminalHandler);
        }
    }
}

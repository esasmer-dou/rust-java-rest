package com.reactor.rust.cors;

import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.middleware.Middleware;
import com.reactor.rust.middleware.MiddlewareChain;
import com.reactor.rust.middleware.MiddlewareContext;

import java.util.HashMap;
import java.util.Map;

/**
 * CORS middleware for handling cross-origin requests.
 * Automatically handles preflight (OPTIONS) requests.
 */
public class CorsMiddleware implements Middleware {

    private CorsConfig config;

    public CorsMiddleware() {
        // Will be initialized lazily from BeanContainer
    }

    public CorsMiddleware(CorsConfig config) {
        this.config = config;
    }

    private CorsConfig getConfig() {
        if (config == null) {
            config = BeanContainer.getInstance().getBeansOfType(CorsConfig.class)
                    .stream()
                    .findFirst()
                    .orElse(new CorsConfig());
        }
        return config;
    }

    @Override
    public MiddlewareChain.Result process(MiddlewareContext context, MiddlewareChain chain) {
        CorsConfig cfg = getConfig();

        if (!cfg.isEnabled()) {
            return chain.next(context);
        }

        String origin = context.getHeader("origin");
        String method = context.method();

        // Handle preflight request
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return handlePreflight(context, cfg, origin);
        }

        // Process actual request
        MiddlewareChain.Result result = chain.next(context);

        // Add CORS headers to response
        return addCorsHeaders(result, cfg, origin, method);
    }

    /**
     * Handle CORS preflight (OPTIONS) request.
     */
    private MiddlewareChain.Result handlePreflight(MiddlewareContext context, CorsConfig cfg, String origin) {
        String requestMethod = context.getHeader("access-control-request-method");

        // Check if origin is allowed
        if (!cfg.isOriginAllowed(origin)) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            return new MiddlewareChain.Result.ResponseWithHeaders(403, "{\"error\":\"Origin not allowed\"}", headers);
        }

        // Check if method is allowed
        if (requestMethod != null && !cfg.isMethodAllowed(requestMethod)) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            return new MiddlewareChain.Result.ResponseWithHeaders(405, "{\"error\":\"Method not allowed\"}", headers);
        }

        // Build preflight response
        Map<String, String> headers = cfg.buildCorsHeaders(origin, requestMethod);
        headers.put("Content-Type", "application/json");

        return new MiddlewareChain.Result.ResponseWithHeaders(204, "", headers);
    }

    /**
     * Add CORS headers to response.
     */
    private MiddlewareChain.Result addCorsHeaders(MiddlewareChain.Result result, CorsConfig cfg,
                                                   String origin, String method) {
        Map<String, String> corsHeaders = cfg.buildCorsHeaders(origin, method);

        if (result instanceof MiddlewareChain.Result.Response r) {
            Map<String, String> allHeaders = new HashMap<>(corsHeaders);
            return new MiddlewareChain.Result.ResponseWithHeaders(r.status(), r.body(), allHeaders);
        } else if (result instanceof MiddlewareChain.Result.ResponseWithHeaders r) {
            Map<String, String> allHeaders = new HashMap<>(r.headers());
            allHeaders.putAll(corsHeaders);
            return new MiddlewareChain.Result.ResponseWithHeaders(r.status(), r.body(), allHeaders);
        } else {
            // Continue - just return with CORS headers
            return new MiddlewareChain.Result.ResponseWithHeaders(200, "", corsHeaders);
        }
    }

    @Override
    public int getOrder() {
        return 1; // Run early, but after security
    }
}

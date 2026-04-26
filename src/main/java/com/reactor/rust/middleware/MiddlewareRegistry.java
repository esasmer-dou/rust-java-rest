package com.reactor.rust.middleware;

import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.logging.FrameworkLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry for middleware components.
 * Scans and manages all middleware in the application.
 */
public final class MiddlewareRegistry {

    private static final MiddlewareRegistry INSTANCE = new MiddlewareRegistry();

    private final List<Middleware> middlewares = new CopyOnWriteArrayList<>();
    private volatile MiddlewareChain cachedChain;

    private MiddlewareRegistry() {}

    public static MiddlewareRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Scan and register all Middleware beans.
     */
    public void scanAndRegister() {
        for (Object bean : BeanContainer.getInstance().getBeansOfType(Object.class)) {
            if (bean instanceof Middleware middleware) {
                register(middleware);
            }
        }
        rebuildChain();
        FrameworkLogger.info("[MiddlewareRegistry] Registered " + middlewares.size() + " middlewares");
    }

    /**
     * Register a middleware.
     */
    public void register(Middleware middleware) {
        middlewares.add(middleware);
        middlewares.sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        rebuildChain();
    }

    /**
     * Remove a middleware.
     */
    public void unregister(Middleware middleware) {
        middlewares.remove(middleware);
        rebuildChain();
    }

    /**
     * Get all registered middlewares.
     */
    public List<Middleware> getMiddlewares() {
        return new ArrayList<>(middlewares);
    }

    /**
     * Get the cached middleware chain.
     */
    public MiddlewareChain getChain(MiddlewareChain.MiddlewareHandler terminalHandler) {
        if (cachedChain == null) {
            rebuildChain();
        }
        return MiddlewareChain.builder()
                .terminalHandler(terminalHandler)
                .build();
    }

    /**
     * Process a request through the middleware chain.
     */
    public MiddlewareChain.Result process(MiddlewareContext context, MiddlewareChain.MiddlewareHandler handler) {
        MiddlewareChain.Builder builder = MiddlewareChain.builder();
        for (Middleware m : middlewares) {
            builder.add(m);
        }
        builder.terminalHandler(handler);
        return builder.build().next(context);
    }

    private void rebuildChain() {
        // Chain is rebuilt on each request to allow dynamic terminal handlers
        this.cachedChain = null;
    }
}

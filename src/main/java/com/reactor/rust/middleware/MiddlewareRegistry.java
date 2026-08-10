package com.reactor.rust.middleware;

import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.logging.FrameworkLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

/**
 * Registry for middleware components.
 * Scans and manages all middleware in the application.
 */
@Deprecated(forRemoval = true)
public final class MiddlewareRegistry {

    private static final MiddlewareRegistry COMPATIBILITY_INSTANCE = new MiddlewareRegistry();
    private static volatile MiddlewareRegistry active = COMPATIBILITY_INSTANCE;

    private static final Middleware[] EMPTY = new Middleware[0];

    private volatile Middleware[] middlewares = EMPTY;

    private MiddlewareRegistry() {}

    public static MiddlewareRegistry getInstance() {
        return active;
    }

    public static MiddlewareRegistry create() {
        return new MiddlewareRegistry();
    }

    public static void activate(MiddlewareRegistry registry) {
        active = java.util.Objects.requireNonNull(registry, "registry");
    }

    public static void deactivate(MiddlewareRegistry registry) {
        if (active == registry) {
            active = COMPATIBILITY_INSTANCE;
        }
    }

    /**
     * Scan and register all Middleware beans.
     */
    public void scanAndRegister() {
        scanAndRegister(BeanContainer.getInstance());
    }

    public void scanAndRegister(BeanContainer container) {
        for (Object bean : container.getBeansOfType(Object.class)) {
            if (bean instanceof Middleware middleware) {
                register(middleware);
            }
        }
        FrameworkLogger.info("[MiddlewareRegistry] Registered " + middlewares.length + " middlewares");
    }

    /**
     * Register a middleware.
     */
    public synchronized void register(Middleware middleware) {
        Middleware[] current = middlewares;
        for (Middleware existing : current) {
            if (existing == middleware) return;
        }
        Middleware[] updated = java.util.Arrays.copyOf(current, current.length + 1);
        updated[current.length] = java.util.Objects.requireNonNull(middleware, "middleware");
        java.util.Arrays.sort(updated, Comparator.comparingInt(Middleware::getOrder));
        middlewares = updated;
    }

    /**
     * Remove a middleware.
     */
    public synchronized void unregister(Middleware middleware) {
        Middleware[] current = middlewares;
        int index = -1;
        for (int i = 0; i < current.length; i++) {
            if (current[i] == middleware) {
                index = i;
                break;
            }
        }
        if (index < 0) return;
        Middleware[] updated = new Middleware[current.length - 1];
        System.arraycopy(current, 0, updated, 0, index);
        System.arraycopy(current, index + 1, updated, index, current.length - index - 1);
        middlewares = updated;
    }

    /**
     * Get all registered middlewares.
     */
    public List<Middleware> getMiddlewares() {
        return new ArrayList<>(List.of(middlewares));
    }

    /**
     * Get the cached middleware chain.
     */
    public MiddlewareChain getChain(MiddlewareChain.MiddlewareHandler terminalHandler) {
        return new MiddlewareChain(middlewares, terminalHandler);
    }

    /**
     * Process a request through the middleware chain.
     */
    public MiddlewareChain.Result process(MiddlewareContext context, MiddlewareChain.MiddlewareHandler handler) {
        Middleware[] snapshot = middlewares;
        if (snapshot.length == 0) {
            return handler.handle(context);
        }
        return new MiddlewareChain(snapshot, handler).next(context);
    }

    synchronized void clear() {
        middlewares = EMPTY;
    }
}

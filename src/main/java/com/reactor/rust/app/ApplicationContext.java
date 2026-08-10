package com.reactor.rust.app;

import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.config.NativeCapabilityPlan;
import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.exception.ExceptionHandlerRegistry;
import com.reactor.rust.middleware.MiddlewareRegistry;
import com.reactor.rust.websocket.WebSocketRegistry;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application-owned startup state.
 *
 * <p>The native HTTP server has one active Java callback target per process. This context owns
 * that target and publishes it once before the server starts. Request processing then uses the
 * already-built registries; no per-request dependency lookup or context allocation is added.</p>
 */
public final class ApplicationContext implements AutoCloseable {

    private final BeanContainer beans;
    private final ExceptionHandlerRegistry exceptionHandlers;
    private final HandlerRegistry handlers;
    private final MiddlewareRegistry middlewares;
    private final WebSocketRegistry webSockets;
    private volatile NativeCapabilityPlan capabilities;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private ApplicationContext(BeanContainer beans) {
        this.beans = Objects.requireNonNull(beans, "beans");
        this.exceptionHandlers = ExceptionHandlerRegistry.create();
        this.handlers = HandlerRegistry.create(exceptionHandlers);
        this.middlewares = MiddlewareRegistry.create();
        this.webSockets = WebSocketRegistry.create();
    }

    public static ApplicationContext create() {
        return new ApplicationContext(BeanContainer.create());
    }

    public static ApplicationContext create(BeanContainer beans) {
        return new ApplicationContext(beans);
    }

    public BeanContainer beans() {
        return beans;
    }

    public HandlerRegistry handlers() {
        return handlers;
    }

    public ExceptionHandlerRegistry exceptionHandlers() {
        return exceptionHandlers;
    }

    public MiddlewareRegistry middlewares() {
        return middlewares;
    }

    public WebSocketRegistry webSockets() {
        return webSockets;
    }

    public NativeCapabilityPlan capabilities() {
        NativeCapabilityPlan plan = capabilities;
        if (plan == null) {
            throw new IllegalStateException("ApplicationContext is not active");
        }
        return plan;
    }

    /** Publishes this context as the single native callback target for the process. */
    void activate(boolean standardRuntime) {
        if (closed.get()) {
            throw new IllegalStateException("ApplicationContext is already closed");
        }
        if (!active.compareAndSet(false, true)) {
            return;
        }
        capabilities = NativeCapabilityPlan.fromProperties(standardRuntime);
        BeanContainer.activate(beans);
        ExceptionHandlerRegistry.activate(exceptionHandlers);
        HandlerRegistry.activate(handlers);
        MiddlewareRegistry.activate(middlewares);
        WebSocketRegistry.activate(webSockets);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (active.compareAndSet(true, false)) {
            WebSocketRegistry.deactivate(webSockets);
            MiddlewareRegistry.deactivate(middlewares);
            HandlerRegistry.deactivate(handlers);
            ExceptionHandlerRegistry.deactivate(exceptionHandlers);
            BeanContainer.deactivate(beans);
        }
        handlers.releaseRetainedBuffers();
        capabilities = null;
        beans.shutdown();
    }
}

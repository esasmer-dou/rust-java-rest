package com.reactor.rust.app;

import java.util.Objects;
import java.util.function.Consumer;

/** Explicit, startup-only surface exposed to optional starter modules. */
public final class ApplicationFeatureContext {
    private final ApplicationContext application;
    private final Consumer<AutoCloseable> resources;

    ApplicationFeatureContext(ApplicationContext application, Consumer<AutoCloseable> resources) {
        this.application = Objects.requireNonNull(application, "application");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public ApplicationContext application() {
        return application;
    }

    public void handler(Object handler) {
        application.handlers().registerBean(Objects.requireNonNull(handler, "handler"));
    }

    /**
     * @deprecated General middleware chains are not part of the native hot path. Implement a
     * build-time selected {@code RequestGuardFactory} instead.
     */
    @Deprecated(forRemoval = true)
    public void middleware(com.reactor.rust.middleware.Middleware middleware) {
        Objects.requireNonNull(middleware, "middleware");
        throw new UnsupportedOperationException(
                "Legacy Middleware is not connected to the native request path; use RequestGuardFactory");
    }

    public void manage(AutoCloseable resource) {
        if (resource != null) resources.accept(resource);
    }
}

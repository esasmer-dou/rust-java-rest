package com.reactor.rust.app;

import com.reactor.rust.di.BeanContainer;

import java.util.Objects;
import java.util.function.Consumer;

/** Explicit startup-only context for infrastructure needed by generated application beans. */
public final class ApplicationBootstrapFeatureContext {
    private final ApplicationContext application;
    private final Consumer<AutoCloseable> resources;

    ApplicationBootstrapFeatureContext(ApplicationContext application, Consumer<AutoCloseable> resources) {
        this.application = Objects.requireNonNull(application, "application");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public ApplicationContext application() {
        return application;
    }

    public BeanContainer beans() {
        return application.beans();
    }

    public void manage(AutoCloseable resource) {
        if (resource != null) resources.accept(resource);
    }
}

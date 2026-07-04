package com.reactor.rust.app;

import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.config.RuntimeProfiles;
import com.reactor.rust.di.BeanContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RestApplication {

    private RestApplication() {}

    public static Builder builder() {
        return new Builder();
    }

    public static void start(String basePackage, Class<?>... handlerTypes) {
        builder()
                .scan(basePackage)
                .handlers(handlerTypes)
                .start();
    }

    public static void startHandlers(String shutdownThreadName, AutoCloseable closeable, Object... handlers) {
        builder()
                .shutdownThreadName(shutdownThreadName)
                .closeable(closeable)
                .handlerInstances(handlers)
                .start();
    }

    public static void disableRouteIndexValidationIfNotExplicit() {
        if (!PropertiesLoader.hasExternalOverride("reactor.startup.route-index.validate")) {
            System.setProperty("reactor.startup.route-index.validate", "false");
        }
        if (!PropertiesLoader.hasExternalOverride("reactor.startup.route-index.required")) {
            System.setProperty("reactor.startup.route-index.required", "false");
        }
    }

    public static void sleepForever() {
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class Builder {

        private final List<Class<?>> handlerTypes = new ArrayList<>();
        private final List<Object> handlerInstances = new ArrayList<>();
        private String basePackage;
        private BeanContainer container;
        private AutoCloseable closeable;
        private String shutdownThreadName = "reactor-rest-shutdown";
        private String portProperty = "server.port";
        private int defaultPort = 8080;
        private boolean loadProperties = true;
        private boolean applyRuntimeProfiles = true;
        private boolean disableRouteIndexValidation;

        private Builder() {}

        public Builder scan(String basePackage) {
            this.basePackage = requireText(basePackage, "basePackage");
            return this;
        }

        public Builder container(BeanContainer container) {
            this.container = Objects.requireNonNull(container, "container");
            return this;
        }

        public Builder handlers(Class<?>... handlerTypes) {
            if (handlerTypes != null) {
                for (Class<?> handlerType : handlerTypes) {
                    this.handlerTypes.add(Objects.requireNonNull(handlerType, "handlerType"));
                }
            }
            return this;
        }

        public Builder handlerInstances(Object... handlers) {
            if (handlers != null) {
                for (Object handler : handlers) {
                    this.handlerInstances.add(Objects.requireNonNull(handler, "handler"));
                }
            }
            return this;
        }

        public Builder closeable(AutoCloseable closeable) {
            this.closeable = closeable;
            return this;
        }

        public Builder shutdownThreadName(String shutdownThreadName) {
            this.shutdownThreadName = requireText(shutdownThreadName, "shutdownThreadName");
            return this;
        }

        public Builder portProperty(String portProperty, int defaultPort) {
            this.portProperty = requireText(portProperty, "portProperty");
            this.defaultPort = defaultPort;
            return this;
        }

        public Builder loadProperties(boolean loadProperties) {
            this.loadProperties = loadProperties;
            return this;
        }

        public Builder applyRuntimeProfiles(boolean applyRuntimeProfiles) {
            this.applyRuntimeProfiles = applyRuntimeProfiles;
            return this;
        }

        public Builder disableRouteIndexValidationIfNotExplicit(boolean disableRouteIndexValidation) {
            this.disableRouteIndexValidation = disableRouteIndexValidation;
            return this;
        }

        public void start() {
            if (loadProperties) {
                PropertiesLoader.load();
            }
            if (applyRuntimeProfiles) {
                RuntimeProfiles.apply();
            }
            if (disableRouteIndexValidation) {
                RestApplication.disableRouteIndexValidationIfNotExplicit();
            }

            AutoCloseable lifecycle = initializeHandlers();
            AutoCloseable shutdownCloseable = closeable == null ? lifecycle : closeBoth(lifecycle, closeable);
            startHttp(shutdownCloseable);
        }

        private AutoCloseable initializeHandlers() {
            BeanContainer activeContainer = container;
            if (basePackage != null || !handlerTypes.isEmpty()) {
                activeContainer = activeContainer == null ? BeanContainer.getInstance() : activeContainer;
                if (basePackage != null) {
                    activeContainer.scan(basePackage);
                    activeContainer.start();
                }
                HandlerRegistry registry = HandlerRegistry.getInstance();
                for (Class<?> handlerType : handlerTypes) {
                    registry.registerBean(activeContainer.getBean(handlerType));
                }
            }
            HandlerRegistry registry = HandlerRegistry.getInstance();
            for (Object handler : handlerInstances) {
                registry.registerBean(handler);
            }
            BeanContainer containerToClose = activeContainer;
            return containerToClose == null ? () -> {} : containerToClose::shutdown;
        }

        private void startHttp(AutoCloseable shutdownCloseable) {
            RouteScanner.scanAndRegister();
            NativeBridge.configureRuntimeFromProperties();
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> shutdown(shutdownCloseable),
                    shutdownThreadName
            ));
            NativeBridge.startHttpServer(PropertiesLoader.getInt(portProperty, defaultPort));
            sleepForever();
        }

        private static AutoCloseable closeBoth(AutoCloseable first, AutoCloseable second) {
            return () -> {
                try {
                    first.close();
                } finally {
                    second.close();
                }
            };
        }

        private static void shutdown(AutoCloseable closeable) {
            try {
                NativeBridge.stopHttpServer();
            } catch (UnsatisfiedLinkError ignored) {
                // Native library may be unavailable during failed startup.
            } finally {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // Shutdown is best effort.
                }
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}

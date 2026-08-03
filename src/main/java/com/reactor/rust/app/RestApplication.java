package com.reactor.rust.app;

import com.reactor.rust.annotations.ReactorApplication;
import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.config.RuntimeFootprintGate;
import com.reactor.rust.config.RuntimeProfilePlan;
import com.reactor.rust.config.RuntimeProfiles;
import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.exception.ExceptionHandlerRegistry;
import com.reactor.rust.memory.NativeIdleMemoryTrimmer;
import com.reactor.rust.startup.InstantOnCheckpoint;
import com.reactor.rust.startup.ApplicationDescriptors;
import com.reactor.rust.startup.StartupPrewarmer;
import com.reactor.rust.startup.StartupTimeline;
import com.reactor.rust.staticfiles.StaticFileScanner;
import com.reactor.rust.websocket.WebSocketRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class RestApplication {

    private RestApplication() {}

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Runs an application assembled from explicit modules using the minimal runtime lifecycle.
     * The builder remains available for advanced startup customization.
     */
    public static void run(Module... modules) {
        configureModules(builder(), modules).start();
    }

    /**
     * Runs an application with the framework-owned production feature lifecycle enabled.
     */
    public static void runStandard(Module... modules) {
        configureModules(builder().standardRuntimeFeatures(), modules).start();
    }

    /**
     * Runs a build-time indexed application without an application-owned module.
     */
    public static void run(Class<?> applicationType, String... args) {
        configureApplication(builder(), applicationType).start();
    }

    /** Starts a build-time indexed application and returns its lifecycle handle. */
    public static RunningApplication startAsync(Class<?> applicationType, String... args) {
        return configureApplication(builder(), applicationType).startAsync();
    }

    public static RunningApplication startAsync(Module... modules) {
        return configureModules(builder(), modules).startAsync();
    }

    public static RunningApplication startStandardAsync(Module... modules) {
        return configureModules(builder().standardRuntimeFeatures(), modules).startAsync();
    }

    @FunctionalInterface
    public interface Module {
        void configure(ModuleContext context);
    }

    /**
     * Explicit startup context for applications that create handlers from runtime properties.
     */
    public static final class ModuleContext {

        private final Builder builder;
        private final ManagedResources resources;

        private ModuleContext(Builder builder, ManagedResources resources) {
            this.builder = builder;
            this.resources = resources;
        }

        public Properties properties() {
            return PropertiesLoader.getAll();
        }

        public <T extends AutoCloseable> T manage(T resource) {
            T managed = Objects.requireNonNull(resource, "resource");
            resources.add(managed);
            return managed;
        }

        public ModuleContext handlers(Object... handlers) {
            builder.handlerInstances(handlers);
            return this;
        }

        public ModuleContext handlerTypes(Class<?>... handlerTypes) {
            builder.handlers(handlerTypes);
            return this;
        }

        public ModuleContext scan(String basePackage) {
            builder.scan(basePackage);
            return this;
        }

        public ModuleContext profile(RuntimeProfilePlan plan) {
            Objects.requireNonNull(plan, "plan").apply();
            return this;
        }
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

    private static Builder configureModules(Builder builder, Module... modules) {
        Objects.requireNonNull(modules, "modules");
        if (modules.length == 0) {
            throw new IllegalArgumentException("At least one application module is required");
        }
        for (Module module : modules) {
            builder.module(Objects.requireNonNull(module, "module"));
        }
        return builder;
    }

    private static Builder configureApplication(Builder builder, Class<?> applicationType) {
        Objects.requireNonNull(applicationType, "applicationType");
        ReactorApplication application = applicationType.getAnnotation(ReactorApplication.class);
        if (application == null) {
            throw new IllegalArgumentException(
                    "Application entry point must declare @ReactorApplication: " + applicationType.getName());
        }
        String packageName = applicationType.getPackageName();
        if (packageName.isBlank()) {
            throw new IllegalArgumentException("Application entry point must be declared in a named package");
        }
        String[] configuredPackages = application.scanBasePackages();
        if (configuredPackages.length == 0) {
            builder.scan(packageName);
        } else {
            for (String configuredPackage : configuredPackages) {
                builder.scan(configuredPackage);
            }
        }
        if (application.standardRuntime()) {
            builder.standardRuntimeFeatures();
        }
        return builder;
    }

    public static final class Builder {

        private final List<Class<?>> handlerTypes = new ArrayList<>();
        private final List<Object> handlerInstances = new ArrayList<>();
        private final List<AutoCloseable> closeables = new ArrayList<>();
        private final List<Module> modules = new ArrayList<>();
        private final List<String> basePackages = new ArrayList<>();
        private BeanContainer container;
        private String shutdownThreadName = "reactor-rest-shutdown";
        private String portProperty = "server.port";
        private int defaultPort = 8080;
        private boolean loadProperties = true;
        private boolean applyRuntimeProfiles = true;
        private boolean disableRouteIndexValidation;
        private boolean standardRuntimeFeatures;
        private boolean started;

        private Builder() {}

        public Builder scan(String basePackage) {
            String normalized = requireText(basePackage, "basePackage");
            if (!basePackages.contains(normalized)) {
                basePackages.add(normalized);
            }
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
            if (closeable != null) {
                this.closeables.add(closeable);
            }
            return this;
        }

        public Builder manage(AutoCloseable... resources) {
            if (resources != null) {
                for (AutoCloseable resource : resources) {
                    closeables.add(Objects.requireNonNull(resource, "resource"));
                }
            }
            return this;
        }

        public Builder module(Module module) {
            modules.add(Objects.requireNonNull(module, "module"));
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

        /**
         * Enables the framework-owned production feature lifecycle used by the complete sample.
         * Individual features remain controlled by their existing properties.
         */
        public Builder standardRuntimeFeatures() {
            this.standardRuntimeFeatures = true;
            return this;
        }

        public void start() {
            try (RunningApplication application = startAsync()) {
                try {
                    application.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        public synchronized RunningApplication startAsync() {
            if (started) {
                throw new IllegalStateException("Rest application builder can only start once");
            }
            started = true;
            if (standardRuntimeFeatures) {
                StartupTimeline.mark("main.enter");
            }
            if (loadProperties) {
                phase("properties.load", PropertiesLoader::load);
            }
            if (applyRuntimeProfiles) {
                phase("runtime.profile", RuntimeProfiles::apply);
            }
            if (standardRuntimeFeatures) {
                RuntimeFootprintGate.validate();
            }
            if (disableRouteIndexValidation) {
                RestApplication.disableRouteIndexValidationIfNotExplicit();
            }

            ManagedResources resources = new ManagedResources();
            closeables.forEach(resources::add);
            try {
                ModuleContext moduleContext = new ModuleContext(this, resources);
                for (Module module : modules) {
                    module.configure(moduleContext);
                }

                InitializedHandlers initialized = initializeHandlers();
                resources.add(initialized.lifecycle());
                phase("routes.register", RouteScanner::scanAndRegister);
                registerStandardRuntimeFeatures(initialized.container());
                phase("native.configure", NativeBridge::configureRuntimeFromProperties);
                if (standardRuntimeFeatures) {
                    StartupPrewarmer.prewarmIfEnabled();
                    InstantOnCheckpoint.checkpointIfEnabled();
                }

                RunningApplication application = startHttp(resources);
                try {
                    if (standardRuntimeFeatures) {
                        resources.add(NativeIdleMemoryTrimmer.startFromProperties());
                        StartupTimeline.ready();
                        FrameworkLogger.info("[RestApplication] Startup ready in "
                                + StartupTimeline.readyMillis() + " ms");
                    }
                    return application;
                } catch (RuntimeException | Error postStartFailure) {
                    application.close();
                    throw postStartFailure;
                }
            } catch (RuntimeException | Error startupFailure) {
                closeQuietly(resources);
                throw startupFailure;
            }
        }

        private InitializedHandlers initializeHandlers() {
            BeanContainer activeContainer = container;
            if (!basePackages.isEmpty() || !handlerTypes.isEmpty()) {
                activeContainer = activeContainer == null ? BeanContainer.getInstance() : activeContainer;
                if (!basePackages.isEmpty()) {
                    BeanContainer containerToStart = activeContainer;
                    phase("di.scan", () -> containerToStart.scan(basePackages.toArray(String[]::new)));
                    phase("di.configuration", () -> {
                        for (String basePackage : basePackages) {
                            ApplicationDescriptors.registerConfigurationBeans(containerToStart, basePackage);
                        }
                    });
                    phase("di.start", containerToStart::start);
                    phase("exceptions.register", () -> ExceptionHandlerRegistry.getInstance()
                            .scanAndRegister(containerToStart));
                    HandlerRegistry registry = HandlerRegistry.getInstance();
                    for (String basePackage : basePackages) {
                        ApplicationDescriptors.registerHandlers(containerToStart, registry, basePackage);
                    }
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
            AutoCloseable lifecycle = containerToClose == null ? () -> {} : containerToClose::shutdown;
            return new InitializedHandlers(activeContainer, lifecycle);
        }

        private void registerStandardRuntimeFeatures(BeanContainer activeContainer) {
            if (!standardRuntimeFeatures) {
                return;
            }
            if (PropertiesLoader.getBoolean("reactor.websocket.enabled", true)) {
                phase("websocket.register", Builder::registerWebSocketHandlers);
            }
            if (PropertiesLoader.getBoolean("reactor.static-files.enabled", true)) {
                if (activeContainer == null) {
                    throw new IllegalStateException(
                            "Static file discovery requires a BeanContainer or package scan");
                }
                phase("static_files.register", () -> StaticFileScanner.scanAndRegister(
                        activeContainer.getBeansOfType(Object.class)));
            }
        }

        private RunningApplication startHttp(ManagedResources resources) {
            return phase("http.start", () -> RunningApplication.start(
                    PropertiesLoader.getInt(portProperty, defaultPort),
                    shutdownThreadName,
                    resources));
        }

        private void phase(String name, Runnable action) {
            if (!standardRuntimeFeatures) {
                action.run();
                return;
            }
            try (StartupTimeline.Scope ignored = StartupTimeline.phase(name)) {
                action.run();
            }
        }

        private <T> T phase(String name, Supplier<T> action) {
            if (!standardRuntimeFeatures) {
                return action.get();
            }
            try (StartupTimeline.Scope ignored = StartupTimeline.phase(name)) {
                return action.get();
            }
        }

        private static void registerWebSocketHandlers() {
            try {
                WebSocketRegistry registry = WebSocketRegistry.getInstance();
                registry.scanAndRegister();
                for (String path : registry.getHandlerPaths()) {
                    NativeBridge.registerWebSocketRoute(path, path.hashCode());
                }
                FrameworkLogger.info("[RestApplication] WebSocket handlers registered: "
                        + registry.getHandlerPaths().size());
            } catch (UnsatisfiedLinkError unsupportedNativeRuntime) {
                FrameworkLogger.warn(
                        "[RestApplication] WebSocket is unavailable in the loaded native runtime");
            }
        }

        private static void shutdown(AutoCloseable closeable) {
            try {
                NativeBridge.stopHttpServer();
            } catch (RuntimeException | LinkageError failure) {
                FrameworkLogger.warn("[RestApplication] Native server shutdown failed: "
                        + failure.getMessage());
            } finally {
                try {
                    closeable.close();
                } catch (Exception failure) {
                    FrameworkLogger.warn("[RestApplication] Application resource shutdown failed: "
                            + failure.getMessage());
                }
            }
        }

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Preserve the startup exception as the primary failure.
            }
        }
    }

    public static final class RunningApplication implements AutoCloseable {

        private final ManagedResources applicationResources;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final Thread shutdownHook;

        private RunningApplication(String shutdownThreadName, ManagedResources applicationResources) {
            this.applicationResources = applicationResources;
            this.shutdownHook = new Thread(() -> shutdown(false), shutdownThreadName);
        }

        private static RunningApplication start(
                int port,
                String shutdownThreadName,
                ManagedResources applicationResources) {
            RunningApplication application = new RunningApplication(
                    shutdownThreadName,
                    applicationResources);
            Runtime.getRuntime().addShutdownHook(application.shutdownHook);
            try {
                NativeBridge.startHttpServer(port);
                return application;
            } catch (RuntimeException | Error startupFailure) {
                application.removeShutdownHook();
                try {
                    NativeBridge.stopHttpServer();
                } catch (RuntimeException | LinkageError ignored) {
                    // Preserve the startup failure as the primary error.
                }
                throw startupFailure;
            }
        }

        public void await() throws InterruptedException {
            stopped.await();
        }

        public boolean isRunning() {
            return !stopping.get();
        }

        @Override
        public void close() {
            shutdown(true);
        }

        private void shutdown(boolean removeHook) {
            if (!stopping.compareAndSet(false, true)) {
                awaitStoppedUninterruptibly();
                return;
            }
            if (removeHook) {
                removeShutdownHook();
            }
            try {
                Builder.shutdown(applicationResources);
            } finally {
                stopped.countDown();
            }
        }

        private void removeShutdownHook() {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown already started; the hook owns cleanup.
            }
        }

        private void awaitStoppedUninterruptibly() {
            boolean interrupted = false;
            while (stopped.getCount() != 0) {
                try {
                    stopped.await();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record InitializedHandlers(BeanContainer container, AutoCloseable lifecycle) {}

    private static final class ManagedResources implements AutoCloseable {

        private final List<AutoCloseable> resources = new ArrayList<>();
        private boolean closed;

        synchronized void add(AutoCloseable resource) {
            if (resource == null) {
                return;
            }
            if (closed) {
                Builder.closeQuietly(resource);
                return;
            }
            resources.add(resource);
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            Throwable failure = null;
            for (int index = resources.size() - 1; index >= 0; index--) {
                try {
                    resources.get(index).close();
                } catch (Exception | LinkageError closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            resources.clear();
            if (failure != null) {
                FrameworkLogger.warn("[RestApplication] Resource shutdown failed: "
                        + failure.getMessage());
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

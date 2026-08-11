package com.reactor.rust.app;

import com.reactor.rust.annotations.ReactorApplication;
import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.GeneratedRouteInvoker;
import com.reactor.rust.bridge.GeneratedRouteInvokers;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.config.NativeCapabilityPlan;
import com.reactor.rust.config.RuntimeFootprintGate;
import com.reactor.rust.config.RuntimeProfilePlan;
import com.reactor.rust.config.RuntimeProfiles;
import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.exception.ExceptionHandlerRegistry;
import com.reactor.rust.memory.NativeIdleMemoryTrimmer;
import com.reactor.rust.metrics.MetricsHandler;
import com.reactor.rust.metrics.Metrics;
import com.reactor.rust.health.HealthContributor;
import com.reactor.rust.health.HealthEndpoint;
import com.reactor.rust.health.HealthStarter;
import com.reactor.rust.startup.InstantOnCheckpoint;
import com.reactor.rust.startup.ApplicationDescriptors;
import com.reactor.rust.startup.StartupPrewarmer;
import com.reactor.rust.startup.StartupTimeline;
import com.reactor.rust.startup.StartupMode;
import com.reactor.rust.staticfiles.StaticFileScanner;
import com.reactor.rust.websocket.WebSocketRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;
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
        configureApplication(builder().strictAot(), applicationType).start();
    }

    /** Starts a build-time indexed application and returns its lifecycle handle. */
    public static RunningApplication startAsync(Class<?> applicationType, String... args) {
        return configureApplication(builder().strictAot(), applicationType).startAsync();
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
        if (application.metrics()) {
            builder.metrics();
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
        private ApplicationContext applicationContext;
        private String shutdownThreadName = "reactor-rest-shutdown";
        private String portProperty = "server.port";
        private int defaultPort = 8080;
        private boolean loadProperties = true;
        private boolean applyRuntimeProfiles = true;
        private boolean disableRouteIndexValidation;
        private boolean standardRuntimeFeatures;
        private boolean builtInMetrics;
        private boolean aotDefault;
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

        /** Uses an application-owned context. Primarily useful for isolated integration tests. */
        public Builder context(ApplicationContext applicationContext) {
            this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext");
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

        /** Enables the optional built-in metrics and diagnostics routes. */
        public Builder metrics() {
            this.builtInMetrics = true;
            return this;
        }

        /** Requires build-time generated application metadata and disables reflection fallback. */
        public Builder strictAot() {
            this.aotDefault = true;
            return this;
        }

        /** Explicit compatibility mode for applications not yet using the code generator. */
        public Builder compatibilityMode() {
            this.aotDefault = false;
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
            phase("metrics.configure", () -> Metrics.getInstance().configureCollection(
                    builtInMetrics || PropertiesLoader.getBoolean(
                            "reactor.metrics.collection-enabled",
                            false)));
            phase("startup.mode", () -> StartupMode.configure(aotDefault));
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

                InitializedHandlers initialized = initializeHandlers(resources);
                resources.add(initialized.context());
                registerApplicationFeatures(initialized.context(), resources);
                registerFrameworkHandlers(initialized.context());
                phase("routes.register", RouteScanner::scanAndRegister);
                registerStandardRuntimeFeatures(initialized.context());
                phase("native.configure", () -> NativeBridge.configureRuntimeFromProperties(
                        initialized.context().capabilities()));
                if (standardRuntimeFeatures) {
                    StartupPrewarmer.prewarmIfEnabled();
                    InstantOnCheckpoint.checkpointIfEnabled();
                }

                RunningApplication application = startHttp(resources, initialized.context());
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

        private InitializedHandlers initializeHandlers(ManagedResources resources) {
            if (applicationContext != null && container != null
                    && applicationContext.beans() != container) {
                throw new IllegalStateException("Configure either context or container, not both");
            }
            ApplicationContext activeContext = applicationContext != null
                    ? applicationContext
                    : ApplicationContext.create(container == null ? BeanContainer.create() : container);
            activeContext.activate(standardRuntimeFeatures);
            BeanContainer activeContainer = activeContext.beans();
            registerBootstrapFeatures(activeContext, resources);
            if (!basePackages.isEmpty() || !handlerTypes.isEmpty()) {
                if (!basePackages.isEmpty()) {
                    BeanContainer containerToStart = activeContainer;
                    phase("di.scan", () -> containerToStart.scan(basePackages.toArray(String[]::new)));
                    phase("di.configuration", () -> {
                        for (String basePackage : basePackages) {
                            ApplicationDescriptors.registerConfigurationBeans(containerToStart, basePackage);
                        }
                    });
                    phase("di.start", containerToStart::start);
                    phase("exceptions.register", () -> {
                        ExceptionHandlerRegistry exceptionRegistry = activeContext.exceptionHandlers();
                        exceptionRegistry.clear();
                        for (String basePackage : basePackages) {
                            ApplicationDescriptors.registerExceptionHandlers(
                                    containerToStart, exceptionRegistry, basePackage);
                        }
                        if (!StartupMode.isAot()) {
                            exceptionRegistry.scanAndRegisterFallback(containerToStart);
                        }
                    });
                    HandlerRegistry registry = activeContext.handlers();
                    for (String basePackage : basePackages) {
                        ApplicationDescriptors.registerHandlers(containerToStart, registry, basePackage);
                    }
                    phase("extensions.register", () -> {
                        for (String basePackage : basePackages) {
                            ApplicationDescriptors.registerExtensions(containerToStart, basePackage);
                        }
                    });
                }
                HandlerRegistry registry = activeContext.handlers();
                for (Class<?> handlerType : handlerTypes) {
                    registry.registerBean(activeContainer.getBean(handlerType));
                }
            }
            HandlerRegistry registry = activeContext.handlers();
            for (Object handler : handlerInstances) {
                registry.registerBean(handler);
            }
            return new InitializedHandlers(activeContext);
        }

        private void registerBootstrapFeatures(ApplicationContext context, ManagedResources resources) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = RestApplication.class.getClassLoader();
            List<ApplicationBootstrapFeature> features = ServiceLoader
                    .load(ApplicationBootstrapFeature.class, loader).stream()
                    .map(ServiceLoader.Provider::get)
                    .sorted(java.util.Comparator.comparingInt(ApplicationBootstrapFeature::order))
                    .toList();
            if (features.isEmpty()) return;
            ApplicationBootstrapFeatureContext featureContext =
                    new ApplicationBootstrapFeatureContext(context, resources::add);
            for (ApplicationBootstrapFeature feature : features) {
                phase("bootstrap." + feature.getClass().getSimpleName(), () -> feature.configure(featureContext));
            }
        }

        private void registerStandardRuntimeFeatures(ApplicationContext context) {
            if (!standardRuntimeFeatures) {
                return;
            }
            BeanContainer activeContainer = context.beans();
            if (context.capabilities().enabled(NativeCapabilityPlan.Capability.WEBSOCKET)) {
                phase("websocket.register", () -> registerWebSocketHandlers(context));
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

        private void registerFrameworkHandlers(ApplicationContext context) {
            if (builtInMetrics) {
                context.handlers().registerBean(new MetricsHandler());
            }
            if (!standardRuntimeFeatures) return;
            if (!PropertiesLoader.getBoolean("reactor.health.enabled", true)) return;
            List<HealthContributor> contributors = context.beans().getBeansOfType(HealthContributor.class);
            HealthEndpoint endpoint = HealthStarter.fromContributors(
                    PropertiesLoader.get("reactor.application.name", "reactor-application"),
                    contributors);
            registerHealthInvoker("health", (HealthEndpoint target) -> target.health());
            registerHealthInvoker("liveness", (HealthEndpoint target) -> target.liveness());
            registerHealthInvoker("readiness", (HealthEndpoint target) -> target.readiness());
            context.handlers().registerBean(endpoint);
        }

        private void registerApplicationFeatures(ApplicationContext context, ManagedResources resources) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = RestApplication.class.getClassLoader();
            List<ApplicationFeature> features = ServiceLoader.load(ApplicationFeature.class, loader).stream()
                    .map(ServiceLoader.Provider::get)
                    .sorted(java.util.Comparator.comparingInt(ApplicationFeature::order))
                    .toList();
            if (features.isEmpty()) return;
            ApplicationFeatureContext featureContext = new ApplicationFeatureContext(context, resources::add);
            for (ApplicationFeature feature : features) {
                phase("feature." + feature.getClass().getSimpleName(), () -> feature.configure(featureContext));
            }
        }

        private static void registerHealthInvoker(
                String methodName,
                java.util.function.Function<HealthEndpoint, Object> call) {
            GeneratedRouteInvokers.register(
                    HealthEndpoint.class,
                    methodName,
                    new Class<?>[0],
                    new GeneratedRouteInvoker() {
                        @Override public int arity() { return 0; }
                        @Override public Object invoke0(Object bean) { return call.apply((HealthEndpoint) bean); }
                    });
        }

        private RunningApplication startHttp(ManagedResources resources, ApplicationContext context) {
            return phase("http.start", () -> RunningApplication.start(
                    PropertiesLoader.getInt(portProperty, defaultPort),
                    shutdownThreadName,
                    resources,
                    context));
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

        private static void registerWebSocketHandlers(ApplicationContext context) {
            try {
                WebSocketRegistry registry = context.webSockets();
                registry.scanAndRegister(context.beans());
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
        private final ApplicationContext context;
        private volatile int port;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final Thread shutdownHook;

        private RunningApplication(
                String shutdownThreadName,
                ManagedResources applicationResources,
                ApplicationContext context) {
            this.applicationResources = applicationResources;
            this.context = context;
            this.shutdownHook = new Thread(() -> shutdown(false), shutdownThreadName);
        }

        private static RunningApplication start(
                int port,
                String shutdownThreadName,
                ManagedResources applicationResources,
                ApplicationContext context) {
            RunningApplication application = new RunningApplication(
                    shutdownThreadName,
                    applicationResources,
                    context);
            Runtime.getRuntime().addShutdownHook(application.shutdownHook);
            try {
                application.port = NativeBridge.startHttpServerAndGetPort(port);
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

        public ApplicationContext context() {
            return context;
        }

        /** Returns the actual bound port. This is especially useful when {@code server.port=0}. */
        public int port() {
            return port;
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

    private record InitializedHandlers(ApplicationContext context) {}

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

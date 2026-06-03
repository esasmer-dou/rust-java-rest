package com.reactor.rust.example;

import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.config.RuntimeProfiles;
import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.example.handler.BenchmarkHandler;
import com.reactor.rust.example.handler.FeatureHandler;
import com.reactor.rust.example.handler.FileUploadHandler;
import com.reactor.rust.example.handler.OrderHandler;
import com.reactor.rust.example.handler.UserHandler;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.startup.StartupPrewarmer;
import com.reactor.rust.startup.StartupTimeline;
import com.reactor.rust.startup.InstantOnCheckpoint;
import com.reactor.rust.metrics.MetricsHandler;
import com.reactor.rust.websocket.WebSocketRegistry;
import com.reactor.rust.staticfiles.StaticFileScanner;

/**
 * Main Application - Pure Java with Lightweight DI Container
 *
 * <p>Ultra-low latency HTTP server using Rust Hyper + JNI.</p>
 *
 * <h2>Architecture:</h2>
 * <ul>
 *   <li>Zero-overhead DI container (BeanContainer)</li>
 *   <li>Annotation-based component scanning</li>
 *   <li>Rust Hyper HTTP server via JNI</li>
 *   <li>Memory &lt; 50 MB target</li>
 * </ul>
 *
 * <h2>Constraints:</h2>
 * <ul>
 *   <li>Constraint #1: Memory &lt; 50 MB</li>
 *   <li>Constraint #4: Pure Java - NO reflection libraries at runtime</li>
 *   <li>Constraint #5: High Concurrency and Financial Standards</li>
 *   <li>Constraint #7: RECORD ZORUNLULUĞU - Only Records for DTOs</li>
 * </ul>
 *
 * <h2>DI Usage Example:</h2>
 * <pre>{@code
 * // 1. Get BeanContainer instance
 * BeanContainer container = BeanContainer.getInstance();
 *
 * // 2. Scan for @Component, @Service, @Configuration
 * container.scan("com.reactor.rust.example");
 *
 * // 3. Start container - resolves all dependencies
 * container.start();
 *
 * // 4. Get beans with zero-overhead O(1) lookup
 * OrderHandler handler = container.getBean(OrderHandler.class);
 * }</pre>
 */
public class ReactorRustHyperApplication {

    public static void main(String[] args) {
        StartupTimeline.mark("main.enter");
        FrameworkLogger.info("[JAVA] Starting Rust-Java REST Framework...");

        // 1. Load properties
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("properties.load")) {
            PropertiesLoader.load();
        }
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("runtime.profile")) {
            RuntimeProfiles.apply();
        }

        // 2. Initialize DI Container (ZERO runtime overhead)
        BeanContainer container = initDIContainer();

        // 3. Register handlers with DI FIRST (before route scanning)
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("handlers.register")) {
            registerHandlers(container);
        }

        // 4. Scan and register routes AFTER handlers are registered
        RouteScanner.scanAndRegister();

        // 5. Scan and register WebSocket handlers
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("websocket.register")) {
            registerWebSocketHandlers(container);
        }

        // 6. Scan and register static file handlers
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("static_files.register")) {
            StaticFileScanner.scanAndRegister(container.getBeansOfType(Object.class));
        }

        // 7. Configure native runtime before starting Hyper.
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("native.configure")) {
            NativeBridge.configureRuntimeFromProperties();
        }

        StartupPrewarmer.prewarmIfEnabled();

        FrameworkLogger.info("[JAVA] Context initialized.");

        // Optional OpenJ9/Semeru CRIU checkpoint. Must stay before HTTP sockets are opened.
        InstantOnCheckpoint.checkpointIfEnabled();

        // 8. Start HTTP server
        int port = PropertiesLoader.getInt("server.port", 8080);
        FrameworkLogger.info("[JAVA] Starting Rust Hyper server on port " + port + "...");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                NativeBridge.stopHttpServer();
            } catch (UnsatisfiedLinkError ignored) {
                // Native library may be unavailable during failed startup.
            } finally {
                container.shutdown();
            }
        }, "rust-hyper-shutdown"));
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("http.start")) {
            NativeBridge.startHttpServer(port);
        }
        StartupTimeline.ready();
        FrameworkLogger.info("[JAVA] Startup ready in " + StartupTimeline.readyMillis() + " ms");

        // 9. Keep JVM alive - Rust server runs in separate thread
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Initialize DI Container with component scanning.
     *
     * <p>Zero-overhead startup - all dependencies resolved at startup time.</p>
     *
     * @return initialized BeanContainer
     */
    private static BeanContainer initDIContainer() {
        BeanContainer container = BeanContainer.getInstance();

        // Scan for @Component, @Service, @Repository, @Configuration
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("di.scan")) {
            container.scan("com.reactor.rust.example");
        }

        // Start container - resolves all dependencies
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("di.start")) {
            container.start();
        }

        FrameworkLogger.info("[JAVA] DI Container started with " +
            container.getBeanNames().size() + " beans");

        return container;
    }

    /**
     * Register handlers with DI support.
     *
     * <p>Handlers are retrieved from BeanContainer with all dependencies already injected.</p>
     *
     * @param container initialized BeanContainer
     */
    private static void registerHandlers(BeanContainer container) {
        HandlerRegistry registry = HandlerRegistry.getInstance();

        // Get handlers from container (dependencies already injected by @Autowired)
        // Handlers are @Component annotated, so they're in the container
        OrderHandler orderHandler = container.getBean(OrderHandler.class);
        BenchmarkHandler benchmarkHandler = container.getBean(BenchmarkHandler.class);
        UserHandler userHandler = container.getBean(UserHandler.class);
        FeatureHandler featureHandler = container.getBean(FeatureHandler.class);
        FileUploadHandler fileUploadHandler = container.getBean(FileUploadHandler.class);
        MetricsHandler metricsHandler = new MetricsHandler();

        // Register with handler registry
        registry.registerBean(orderHandler);
        registry.registerBean(benchmarkHandler);
        registry.registerBean(userHandler);
        registry.registerBean(featureHandler);
        registry.registerBean(fileUploadHandler);
        registry.registerBean(metricsHandler);

        FrameworkLogger.info("[JAVA] Handlers registered with DI support");
    }

    /**
     * Register WebSocket handlers with Rust.
     *
     * @param container initialized BeanContainer
     */
    private static void registerWebSocketHandlers(BeanContainer container) {
        try {
            WebSocketRegistry wsRegistry = WebSocketRegistry.getInstance();

            // Scan for @WebSocket annotated beans and register them
            wsRegistry.scanAndRegister();

            // Register each WebSocket route with Rust
            for (String path : wsRegistry.getHandlerPaths()) {
                // Use path hash as handler ID for WebSocket routes
                int handlerId = path.hashCode();
                NativeBridge.registerWebSocketRoute(path, handlerId);
                FrameworkLogger.debug("[JAVA] WebSocket route registered: " + path + " -> handlerId=" + handlerId);
            }

            FrameworkLogger.info("[JAVA] WebSocket handlers registered: " + wsRegistry.getHandlerPaths().size());
        } catch (UnsatisfiedLinkError e) {
            FrameworkLogger.warn("[JAVA] WebSocket not supported by native library - skipping WebSocket registration");
        }
    }
}

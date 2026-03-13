package com.reactor.rust.example;

import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.example.handler.BenchmarkHandler;
import com.reactor.rust.example.handler.OrderHandler;

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
        System.out.println("[JAVA] Starting Rust-Java REST Framework...");

        // 1. Load properties
        PropertiesLoader.load();

        // 2. Initialize DI Container (ZERO runtime overhead)
        BeanContainer container = initDIContainer();

        // 3. Register handlers with DI FIRST (before route scanning)
        registerHandlers(container);

        // 4. Scan and register routes AFTER handlers are registered
        RouteScanner.scanAndRegister();

        System.out.println("[JAVA] Context initialized.");

        // 5. Start HTTP server
        int port = PropertiesLoader.getInt("server.port", 8080);
        System.out.println("[JAVA] Starting Rust Hyper server on port " + port + "...");
        NativeBridge.startHttpServer(port);

        // 6. Keep JVM alive - Rust server runs in separate thread
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
        container.scan("com.reactor.rust.example");

        // Start container - resolves all dependencies
        container.start();

        System.out.println("[JAVA] DI Container started with " +
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

        // Register with handler registry
        registry.registerBean(orderHandler);
        registry.registerBean(benchmarkHandler);

        System.out.println("[JAVA] Handlers registered with DI support");
    }
}

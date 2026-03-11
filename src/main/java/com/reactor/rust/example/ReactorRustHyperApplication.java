package com.reactor.rust.example;

import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.example.handler.BenchmarkHandler;
import com.reactor.rust.example.handler.OrderHandler;
import com.reactor.rust.example.service.OrderService;

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
 *   <li>Memory < 50 MB target</li>
 * </ul>
 *
 * <h2>Constraints:</h2>
 * <ul>
 *   <li>Constraint #1: Memory < 50 MB</li>
 *   <li>Constraint #4: Pure Java - NO reflection libraries at runtime</li>
 *   <li>Constraint #5: High Concurrency & Financial Standards</li>
 *   <li>Constraint #7: RECORD ZORUNLULUĞU - Only Records for DTOs</li>
 * </ul>
 */
public class ReactorRustHyperApplication {

    public static void main(String[] args) {
        System.out.println("[JAVA] Starting Rust-Java REST Framework...");

        // 1. Load properties
        PropertiesLoader.load();

        // 2. Initialize DI Container (ZERO runtime overhead)
        initDIContainer();

        // 3. Scan and register routes
        RouteScanner.scanAndRegister();

        System.out.println("[JAVA] Context initialized.");

        // 4. Start HTTP server
        int port = PropertiesLoader.getInt("server.port", 8080);
        System.out.println("[JAVA] Starting Rust Hyper server on port " + port + "...");
        NativeBridge.startHttpServer(port);

        // 5. Keep JVM alive - Rust server runs in separate thread
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
     */
    private static void initDIContainer() {
        BeanContainer container = BeanContainer.getInstance();

        // Scan for @Component, @Service, @Repository, @Configuration
        container.scan("com.reactor.rust.example");

        // Start container - resolves all dependencies
        container.start();

        // Register handlers with injected dependencies
        HandlerRegistry registry = HandlerRegistry.getInstance();

        // Get OrderHandler with injected OrderService
        OrderService orderService = container.getBean(OrderService.class);

        // Register handlers
        OrderHandler orderHandler = new OrderHandler();
        registry.registerBean(orderHandler);

        BenchmarkHandler benchmarkHandler = new BenchmarkHandler();
        registry.registerBean(benchmarkHandler);

        System.out.println("[JAVA] DI Container started with " +
            container.getBeanNames().size() + " beans");
    }
}

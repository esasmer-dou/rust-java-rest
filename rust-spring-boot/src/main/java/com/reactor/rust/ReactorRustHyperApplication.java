package com.reactor.rust;

import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.handler.OrderHandler;

/**
 * Main Application - Pure Java, No Spring Framework
 *
 * Zero-overhead HTTP server using Rust Hyper + JNI.
 * Constraint #1: Memory < 50 MB
 * Constraint #4: Pure Java - NO reflection libraries
 * Constraint #5: High Concurrency & Financial Standards
 */
public class ReactorRustHyperApplication {

    public static void main(String[] args) {
        System.out.println("[JAVA] Starting Rust-Spring Hyper Server (No Framework)...");

        // Load properties (Constraint #8)
        PropertiesLoader.load();

        // Initialize handlers manually (no Spring DI)
        initHandlers();

        // Scan and register routes
        RouteScanner.scanAndRegister();

        System.out.println("[JAVA] Context initialized.");

        // Start the HTTP server
        int port = PropertiesLoader.getInt("server.port", 8080);
        System.out.println("[JAVA] Starting Rust Hyper server on port " + port + "...");
        NativeBridge.startHttpServer(port);

        // Keep JVM alive - Rust server runs in separate thread
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Initialize handlers manually (no Spring @Component scanning)
     * This gives us zero-overhead startup and predictable initialization
     */
    private static void initHandlers() {
        HandlerRegistry registry = HandlerRegistry.getInstance();

        // Register OrderHandler
        OrderHandler orderHandler = new OrderHandler();
        registry.registerBean(orderHandler);
        System.out.println(">>> [HandlerRegistry] bean registered = " + orderHandler.getClass().getName());
    }
}

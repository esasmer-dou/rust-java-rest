package com.reactor.rust.example.config;

import com.reactor.rust.cors.CorsConfig;
import com.reactor.rust.di.annotation.Bean;
import com.reactor.rust.di.annotation.Configuration;
import com.reactor.rust.staticfiles.StaticFileConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application Configuration - @Bean definitions.
 *
 * <p>Demonstrates @Configuration + @Bean pattern for programmatic bean registration.</p>
 */
@Configuration
public class AppConfiguration {

    /**
     * Create a thread pool for async operations.
     */
    @Bean
    public ExecutorService taskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Create application metadata.
     */
    @Bean("appMetadata")
    public AppMetadata appMetadata() {
        return new AppMetadata("rust-java-rest", "2.0.0");
    }

    /**
     * Configure CORS (Cross-Origin Resource Sharing).
     * Allows all origins, methods, and headers for development.
     */
    @Bean
    public CorsConfig corsConfig() {
        // For production, use specific origins:
        // return new CorsConfig(true, "https://myapp.com", "GET,POST,PUT,DELETE", "Authorization,Content-Type", null, true, 3600);
        return new CorsConfig(); // Default: allow all
    }

    /**
     * Configure static file serving.
     * Serves files from /static/** URL pattern.
     */
    @Bean
    public StaticFileConfig staticFileConfig() {
        // Default config: /static/** -> classpath:static/
        return new StaticFileConfig();
    }

    /**
     * Simple metadata record.
     */
    public record AppMetadata(String name, String version) {}
}

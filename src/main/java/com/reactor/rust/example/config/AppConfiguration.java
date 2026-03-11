package com.reactor.rust.example.config;

import com.reactor.rust.di.annotation.Bean;
import com.reactor.rust.di.annotation.Configuration;

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
        return new AppMetadata("rust-java-rest", "1.0.0");
    }

    /**
     * Simple metadata record.
     */
    public record AppMetadata(String name, String version) {}
}

package com.reactor.rust.example.config;

import com.reactor.rust.cors.CorsConfig;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.di.annotation.Bean;
import com.reactor.rust.di.annotation.Configuration;
import com.reactor.rust.di.annotation.PreDestroy;
import com.reactor.rust.dubbo.DubboConsumerClient;
import com.reactor.rust.dubbo.DubboReferenceSpec;
import com.reactor.rust.dubbo.DubboConsumers;
import com.reactor.rust.dubbo.NativeDubboConsumerClient;
import com.reactor.rust.dubbo.NativeDubboConsumers;
import com.reactor.rust.example.dubbo.DirectNestedCatalogServiceClient;
import com.reactor.rust.example.dubbo.NativeNestedCatalogServiceClient;
import com.reactor.rust.dubbo.sample.NestedCatalogService;
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

    private volatile DubboConsumerClient dubboConsumerClient;
    private volatile NativeDubboConsumerClient nativeDubboConsumerClient;

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
     * Single shared Dubbo consumer client for the application lifecycle.
     */
    @Bean
    public DubboConsumerClient dubboConsumerClient() {
        return dubboEnabled() && !nativeDubboTransport() ? getOrCreateDubboConsumerClient() : null;
    }

    @Bean
    public NativeDubboConsumerClient nativeDubboConsumerClient() {
        return dubboEnabled() && nativeDubboTransport() ? getOrCreateNativeDubboConsumerClient() : null;
    }

    /**
     * Dubbo proxy created once at startup and reused by HTTP handlers.
     */
    @Bean
    public NestedCatalogService nestedCatalogService() {
        if (!dubboEnabled()) {
            return null;
        }
        DubboReferenceSpec<NestedCatalogService> spec = DubboReferenceSpec.builder(NestedCatalogService.class)
                .timeoutMs(800)
                .retries(0)
                .check(false)
                .lazy(true)
                .connections(1)
                .build();
        if (nativeDubboTransport()) {
            return new NativeNestedCatalogServiceClient(getOrCreateNativeDubboConsumerClient()
                    .method(spec, "getNestedCatalogJson", byte[].class));
        }
        return new DirectNestedCatalogServiceClient(getOrCreateDubboConsumerClient()
                .method(spec, "getNestedCatalogJson", byte[].class));
    }

    @PreDestroy
    public void closeDubboConsumer() {
        DubboConsumerClient client = dubboConsumerClient;
        if (client != null) {
            client.close();
        }
        NativeDubboConsumerClient nativeClient = nativeDubboConsumerClient;
        if (nativeClient != null) {
            nativeClient.close();
        }
    }

    private static boolean dubboEnabled() {
        return PropertiesLoader.getBoolean("reactor.dubbo.enabled", true);
    }

    private static boolean nativeDubboTransport() {
        return "native".equalsIgnoreCase(PropertiesLoader.get("reactor.dubbo.transport", "native"));
    }

    private DubboConsumerClient getOrCreateDubboConsumerClient() {
        DubboConsumerClient client = dubboConsumerClient;
        if (client == null) {
            synchronized (this) {
                client = dubboConsumerClient;
                if (client == null) {
                    client = DubboConsumers.create(PropertiesLoader.getAll());
                    dubboConsumerClient = client;
                }
            }
        }
        return client;
    }

    private NativeDubboConsumerClient getOrCreateNativeDubboConsumerClient() {
        NativeDubboConsumerClient client = nativeDubboConsumerClient;
        if (client == null) {
            synchronized (this) {
                client = nativeDubboConsumerClient;
                if (client == null) {
                    client = NativeDubboConsumers.create(PropertiesLoader.getAll());
                    nativeDubboConsumerClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Simple metadata record.
     */
    public record AppMetadata(String name, String version) {}
}

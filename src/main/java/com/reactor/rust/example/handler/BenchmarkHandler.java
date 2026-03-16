package com.reactor.rust.example.handler;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.di.annotation.Autowired;
import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.example.config.AppConfiguration;
import com.reactor.rust.json.DslJsonService;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Benchmark Handler - Spring Boot ile karşılaştırma için
 *
 * <p>Aynı endpoint'ler: /api/v1/echo ve /api/v1/candidates</p>
 *
 * <h2>DI Example:</h2>
 * <ul>
 *   <li>@Component marks this as a bean</li>
 *   <li>@Autowired injects beans from @Configuration</li>
 *   <li>ExecutorService comes from AppConfiguration.taskExecutor()</li>
 *   <li>AppMetadata comes from AppConfiguration.appMetadata()</li>
 * </ul>
 */
@Component
public class BenchmarkHandler {

    // DI: ExecutorService from @Configuration
    @Autowired(required = false)
    private ExecutorService taskExecutor;

    // DI: AppMetadata from @Configuration @Bean
    @Autowired(required = false)
    private AppConfiguration.AppMetadata appMetadata;

    /**
     * POST /api/v1/echo - Request body'yi geri döner.
     * Spring Boot equivalent: {@code ResponseEntity<?> echo(@RequestBody OrderRequest request)}
     */
    @RustRoute(
            method = "POST",
            path = "/api/v1/echo",
            requestType = BenchmarkOrderRequest.class,
            responseType = BenchmarkOrderRequest.class
    )
    public int echo(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        // Parse request
        BenchmarkOrderRequest request = DslJsonService.parse(body, BenchmarkOrderRequest.class);

        // DI Example: Use injected metadata if available
        if (appMetadata != null) {
            System.out.println("[BenchmarkHandler] App: " + appMetadata.name() + " v" + appMetadata.version());
        }

        // Echo back - same as Spring Boot example
        return DslJsonService.writeToBuffer(request, out, offset);
    }

    /**
     * GET /api/v1/candidates - 19 item'lı örnek order döner.
     * Spring Boot equivalent: {@code ResponseEntity<?> fetchCandidates()}
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/candidates",
            requestType = Void.class,
            responseType = BenchmarkOrderRequest.class
    )
    public int candidates(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        // Create address
        BenchmarkAddress address = new BenchmarkAddress("Ankara", "Ataturk Cd.");

        // Create customer
        BenchmarkCustomer customer = new BenchmarkCustomer("mustafa customer a.ş", "mustafa@gmai.com");

        // Create 19 items (same as Spring Boot example)
        List<BenchmarkItem> items = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            items.add(new BenchmarkItem("test" + i, 12.89 + i));
        }

        // Create order request
        BenchmarkOrderRequest request = new BenchmarkOrderRequest(
                "ORD-1001",
                350.75,
                true,
                address,
                customer,
                items
        );

        // DI Example: Use injected executor for async processing (if available)
        if (taskExecutor != null) {
            taskExecutor.submit(() -> {
                System.out.println("[BenchmarkHandler] Async processing on virtual thread: " + Thread.currentThread());
            });
        }

        return DslJsonService.writeToBuffer(request, out, offset);
    }

    /**
     * GET /api/v1/heavy - 100 item'lı ağır payload döner.
     * Benchmark için büyük response testi.
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/heavy",
            requestType = Void.class,
            responseType = HeavyResponse.class
    )
    public int heavy(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        // Parse query param for item count (default 100)
        int itemCount = 100;
        if (query != null && query.contains("items=")) {
            try {
                String countStr = query.split("items=")[1].split("&")[0];
                itemCount = Integer.parseInt(countStr);
                if (itemCount < 1) itemCount = 100;
                if (itemCount > 1000) itemCount = 1000;
            } catch (Exception ignored) {}
        }

        // Create heavy list with large items
        List<HeavyItem> items = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            items.add(new HeavyItem(
                    "ITEM-" + i + "-" + System.nanoTime(),
                    "Detailed description for item number " + i + " with some additional text to increase payload size",
                    99.99 + (i * 0.01),
                    i % 5 == 0,
                    new HeavyMetadata(
                            "category-" + (i % 10),
                            "warehouse-" + (i % 3),
                            System.currentTimeMillis()
                    )
            ));
        }

        HeavyResponse response = new HeavyResponse(
                "HEAVY-" + System.currentTimeMillis(),
                "Heavy payload response with " + itemCount + " items",
                itemCount,
                System.currentTimeMillis(),
                items
        );

        return DslJsonService.writeToBuffer(response, out, offset);
    }

    // ==================== Heavy DTOs ====================

    @CompiledJson
    public record HeavyItem(
            String id,
            String description,
            double price,
            boolean available,
            HeavyMetadata metadata
    ) {}

    @CompiledJson
    public record HeavyMetadata(
            String category,
            String warehouse,
            long timestamp
    ) {}

    @CompiledJson
    public record HeavyResponse(
            String requestId,
            String message,
            int itemCount,
            long timestamp,
            List<HeavyItem> items
    ) {}

    // ==================== DTOs (Records) ====================

    @CompiledJson
    public record BenchmarkAddress(
            String city,
            String street
    ) {}

    @CompiledJson
    public record BenchmarkCustomer(
            String name,
            String email
    ) {}

    @CompiledJson
    public record BenchmarkItem(
            String name,
            double price
    ) {}

    @CompiledJson
    public record BenchmarkOrderRequest(
            String orderId,
            double amount,
            boolean paid,
            BenchmarkAddress address,
            BenchmarkCustomer customer,
            List<BenchmarkItem> items
    ) {}
}

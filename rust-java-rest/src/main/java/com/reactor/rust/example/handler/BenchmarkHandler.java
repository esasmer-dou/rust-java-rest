package com.reactor.rust.example.handler;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.json.DslJsonService;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Benchmark Handler - Spring Boot ile karşılaştırma için
 * Aynı endpoint'ler: /api/v1/echo ve /api/v1/candidates
 */
public class BenchmarkHandler {

    /**
     * POST /api/v1/echo - Request body'yi geri döner
     * Spring Boot: ResponseEntity<?> echo(@RequestBody OrderRequest request)
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

        // Echo back - same as Spring Boot example
        return DslJsonService.writeToBuffer(request, out, offset);
    }

    /**
     * GET /api/v1/candidates - 19 item'lı örnek order döner
     * Spring Boot: ResponseEntity<?> fetchCandidates()
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

        return DslJsonService.writeToBuffer(request, out, offset);
    }

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

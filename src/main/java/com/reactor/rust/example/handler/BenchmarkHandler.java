package com.reactor.rust.example.handler;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.DirectQueryInt;
import com.reactor.rust.annotations.RawRequestData;
import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.http.FileResponse;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.json.DslJsonService;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Benchmark Handler - Spring Boot ile karşılaştırma için
 *
 * <p>Aynı endpoint'ler: /api/v1/echo ve /api/v1/candidates</p>
 *
 * <h2>DI Example:</h2>
 * <ul>
 *   <li>@Component marks this as a bean</li>
 *   <li>Hot benchmark endpoints avoid per-request logging and background task submission</li>
 * </ul>
 */
@Component
public class BenchmarkHandler {

    private static final Object EXPORT_FILE_LOCK = new Object();
    private static final Path SAMPLE_EXPORT_FILE =
            Paths.get("target", "exports", "sample-export.csv").toAbsolutePath().normalize();
    private static final RawResponse PRECOMPUTED_HEAVY_100 =
            RawResponse.registeredJson(DslJsonService.toBytes(createHeavyResponse(100)));

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
    @RawRequestData
    public int echo(
            ByteBuffer out,
            int offset,
            ByteBuffer body,
            int bodyLen,
            String pathParams,
            String query,
            String headers
    ) {
        BenchmarkOrderRequest request = BenchmarkOrderRequestJsonParser.parse(body, bodyLen);

        // Echo back - same as Spring Boot example
        return BenchmarkOrderRequestJsonWriter.INSTANCE.write(request, out, offset);
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

        return BenchmarkOrderRequestJsonWriter.INSTANCE.write(request, out, offset);
    }

    /**
     * GET /api/v1/heavy - object-graph-free heavy payload.
     *
     * <p>Bu endpoint artık DTO listesi kurmaz; JSON'u direkt native response buffer'a yazar.</p>
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/heavy",
            requestType = Void.class,
            responseType = HeavyResponse.class
    )
    @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
    public int heavy(
            ByteBuffer out,
            int offset,
            int itemCount
    ) {
        long now = System.currentTimeMillis();
        return HeavyResponseDirectWriter.write(out, offset, itemCount, now, System.nanoTime());
    }

    /**
     * GET /api/v1/heavy/dto - legacy DTO graph path kept for regression comparison.
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/heavy/dto",
            requestType = Void.class,
            responseType = HeavyResponse.class
    )
    public int heavyDto(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        int itemCount = parseItemCount(query);
        HeavyResponse response = createDynamicHeavyResponse(itemCount);
        return DslJsonService.writeToBuffer(response, out, offset);
    }

    /**
     * GET /api/v1/heavy/rust - selected DTO serialized by Rust into the same direct buffer.
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/heavy/rust",
            requestType = Void.class,
            responseType = HeavyResponse.class
    )
    @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
    public int heavyRust(
            ByteBuffer out,
            int offset,
            int itemCount
    ) {
        return NativeBridge.writeHeavyJsonRust(out, offset, itemCount, System.currentTimeMillis());
    }

    /**
     * GET /api/v1/heavy/cache - bounded native cache path for repeated dynamic payloads.
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/heavy/cache",
            requestType = Void.class,
            responseType = RawResponse.class
    )
    @RawRequestData(query = true)
    public RawResponse heavyCache(
            ByteBuffer out,
            int offset,
            ByteBuffer body,
            int bodyLen,
            String pathParams,
            String query,
            String headers
    ) {
        int itemCount = parseItemCount(query);
        String cacheKey = "heavy:items=" + itemCount;
        int nativeId = NativeBridge.lookupDynamicResponse(cacheKey);
        if (nativeId > 0) {
            return RawResponse.nativeJson(nativeId);
        }

        byte[] payload = DslJsonService.toBytes(createHeavyResponse(itemCount));
        nativeId = NativeBridge.registerDynamicResponse(
                cacheKey,
                payload,
                "Content-Type: application/json\n",
                200,
                300_000L
        );
        return nativeId > 0 ? RawResponse.nativeJson(nativeId) : RawResponse.json(payload);
    }

    /**
     * GET /api/v1/heavy/raw - Precomputed heavy JSON response.
     *
     * <p>This represents the read-heavy/cached response class where the framework
     * should beat Spring most clearly: no per-request DTO graph and no per-request
     * JSON serialization.</p>
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/heavy/raw",
            requestType = Void.class,
            responseType = RawResponse.class
    )
    public RawResponse heavyRaw() {
        return PRECOMPUTED_HEAVY_100;
    }

    /**
     * GET /api/v1/export/file - Büyük export/static response için Rust-native stream yolu.
     *
     * <p>Java sadece dosya path + headers döner; dosya byte'ları JNI frame'e yazılmaz.</p>
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/export/file",
            requestType = Void.class,
            responseType = FileResponse.class
    )
    public FileResponse exportFile() {
        Path exportFile = ensureSampleExportFile();
        return FileResponse.download(exportFile, "sample-export.csv", "text/csv")
                .header("Cache-Control", "no-store");
    }

    private static Path ensureSampleExportFile() {
        try {
            if (Files.isRegularFile(SAMPLE_EXPORT_FILE) && Files.size(SAMPLE_EXPORT_FILE) > 0) {
                return SAMPLE_EXPORT_FILE;
            }
        } catch (IOException ignored) {
            // Regenerate below.
        }

        synchronized (EXPORT_FILE_LOCK) {
            try {
                if (Files.isRegularFile(SAMPLE_EXPORT_FILE) && Files.size(SAMPLE_EXPORT_FILE) > 0) {
                    return SAMPLE_EXPORT_FILE;
                }

                Files.createDirectories(SAMPLE_EXPORT_FILE.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(
                        SAMPLE_EXPORT_FILE,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )) {
                    writer.write("id,customer,amount,status,description\n");
                    for (int i = 0; i < 4096; i++) {
                        writer.write(i + ",customer-" + (i % 128) + "," + (1000 + i)
                                + ",PAID,export row generated for native file streaming\n");
                    }
                }
                return SAMPLE_EXPORT_FILE;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create sample export file", e);
            }
        }
    }

    private static HeavyResponse createHeavyResponse(int itemCount) {
        List<HeavyItem> items = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            items.add(new HeavyItem(
                    "ITEM-" + i,
                    "Detailed description for item number " + i + " with some additional text to increase payload size",
                    99.99 + (i * 0.01),
                    i % 5 == 0,
                    new HeavyMetadata(
                            "category-" + (i % 10),
                            "warehouse-" + (i % 3),
                            1_700_000_000_000L + i
                    )
            ));
        }

        return new HeavyResponse(
                "HEAVY-PRECOMPUTED-100",
                "Precomputed heavy payload response with " + itemCount + " items",
                itemCount,
                1_700_000_000_000L,
                items
        );
    }

    private static HeavyResponse createDynamicHeavyResponse(int itemCount) {
        List<HeavyItem> items = new ArrayList<>(itemCount);
        long now = System.currentTimeMillis();
        long nanos = System.nanoTime();
        for (int i = 0; i < itemCount; i++) {
            items.add(new HeavyItem(
                    "ITEM-" + i + "-" + (nanos + i),
                    "Detailed description for item number " + i + " with some additional text to increase payload size",
                    99.99 + (i * 0.01),
                    i % 5 == 0,
                    new HeavyMetadata(
                            "category-" + (i % 10),
                            "warehouse-" + (i % 3),
                            now
                    )
            ));
        }

        return new HeavyResponse(
                "HEAVY-" + now,
                "Heavy payload response with " + itemCount + " items",
                itemCount,
                now,
                items
        );
    }

    private static int parseItemCount(String query) {
        if (query == null || query.isEmpty()) {
            return 100;
        }

        int key = query.indexOf("items=");
        if (key < 0) {
            return 100;
        }
        int pos = key + 6;
        int value = 0;
        boolean hasDigit = false;
        while (pos < query.length()) {
            char ch = query.charAt(pos++);
            if (ch < '0' || ch > '9') {
                break;
            }
            hasDigit = true;
            value = value * 10 + (ch - '0');
            if (value > 1000) {
                return 1000;
            }
        }
        if (!hasDigit || value < 1) {
            return 100;
        }
        return value;
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

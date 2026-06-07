package com.reactor.rust.example.handler;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.BenchmarkOnlyRoute;
import com.reactor.rust.annotations.DirectQueryInt;
import com.reactor.rust.annotations.NativeStaticFileRoute;
import com.reactor.rust.annotations.NativeStaticRoute;
import com.reactor.rust.annotations.RawRequestData;
import com.reactor.rust.annotations.RouteAdmission;
import com.reactor.rust.annotations.RouteWorkload;
import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.async.AsyncHandlerExecutor;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.http.FileResponse;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.json.DslJsonService;
import com.reactor.rust.json.JsonBodyProducer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

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
    private static final int LARGE_EXPORT_BYTES = 8 * 1024 * 1024;
    private static final Path SAMPLE_EXPORT_FILE =
            Paths.get("target", "exports", "sample-export.csv").toAbsolutePath().normalize();
    private static final Path LARGE_EXPORT_FILE =
            Paths.get("target", "exports", "large-export.bin").toAbsolutePath().normalize();
    private static volatile FileResponse sampleExportResponse;
    private static volatile FileResponse largeExportResponse;
    private static volatile RawResponse sampleExportStaticResponse;
    private static final RawResponse PRECOMPUTED_CANDIDATES_DIRECT =
            RawResponse.registeredJson(createSampleOrderBytes());
    private static final RawResponse PRECOMPUTED_HEAVY_100 =
            RawResponse.registeredJson(createHeavyResponseBytes(100, 1_700_000_000_000L, 1_700_000_000_000L));

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
     * POST /api/v1/echo/raw - transport/copy echo path, no JSON parse and no business mapping.
     */
    @RustRoute(
            method = "POST",
            path = "/api/v1/echo/raw",
            requestType = BenchmarkOrderRequest.class,
            responseType = BenchmarkOrderRequest.class
    )
    @RawRequestData
    public int echoRaw(
            ByteBuffer out,
            int offset,
            ByteBuffer body,
            int bodyLen,
            String pathParams,
            String query,
            String headers
    ) {
        if (body == null || bodyLen <= 0) {
            return 0;
        }
        int safeLen = Math.min(bodyLen, body.capacity());
        if (safeLen > out.capacity() - offset) {
            return -safeLen;
        }
        ByteBuffer duplicate = body.duplicate();
        duplicate.position(0);
        duplicate.limit(safeLen);
        out.position(offset);
        out.put(duplicate);
        return safeLen;
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
     * GET /api/v1/candidates/direct - small JSON path without Java object graph or metadata strings.
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/candidates/direct",
            requestType = Void.class,
            responseType = RawResponse.class
    )
    @NativeStaticRoute
    public RawResponse candidatesDirect() {
        return PRECOMPUTED_CANDIDATES_DIRECT;
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
    @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-direct")
    @RouteAdmission(maxConcurrent = 96, queueTimeoutMs = 75)
    public int heavy(
            ByteBuffer out,
            int offset,
            int itemCount
    ) {
        long now = System.currentTimeMillis();
        return HeavyResponseDirectWriter.write(out, offset, itemCount, now, System.nanoTime());
    }

    /**
     * GET /api/v1/heavy/dto - optimized DTO-shaped JSON for hot heavy routes.
     *
     * <p>The response contract is still the {@link HeavyResponse} JSON shape, but the route does
     * not allocate the {@code HeavyResponse -> HeavyItem -> HeavyMetadata} object graph per request.
     * This is the recommended production shape once a DTO endpoint becomes hot.</p>
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/heavy/dto",
            requestType = Void.class,
            responseType = JsonBodyProducer.class
    )
    @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
    @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-producer")
    @RouteAdmission(maxConcurrent = 96, queueTimeoutMs = 125)
    public JsonBodyProducer heavyDto(int itemCount) {
        return new HeavyJsonProducer(
                itemCount,
                System.currentTimeMillis(),
                System.nanoTime()
        );
    }

    /**
     * GET /api/v1/heavy/dto/async - opt-in async producer path.
     *
     * <p>This is not the default CPU-bound JSON recommendation. Use it when the producer waits on
     * remote/blocking work and the service has measured that freeing JNI workers beats async handoff
     * overhead.</p>
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/heavy/dto/async",
            requestType = Void.class,
            responseType = JsonBodyProducer.class
    )
    @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
    @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-producer")
    @RouteAdmission(maxConcurrent = 96, queueTimeoutMs = 125)
    public CompletionStage<JsonBodyProducer> heavyDtoAsync(int itemCount) {
        long timestamp = System.currentTimeMillis();
        long nanosBase = System.nanoTime();
        return AsyncHandlerExecutor.getInstance().submit(() -> (JsonBodyProducer) new HeavyJsonProducer(
                itemCount,
                timestamp,
                nanosBase
        ));
    }

    /**
     * GET /api/v1/heavy/dto/legacy - real DTO graph path kept for apples-to-apples regression
     * comparison and documentation.
     *
     * <p>Use this when the goal is to measure ordinary Java object graph + DSL-JSON behavior. Do
     * not use it as the hot-route benchmark once the route is known to dominate RSS/p99.</p>
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/heavy/dto/legacy",
            requestType = Void.class,
            responseType = HeavyResponse.class
    )
    @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-legacy")
    @RouteAdmission(maxConcurrent = 48, queueTimeoutMs = 100)
    @BenchmarkOnlyRoute("legacy DTO graph comparison")
    public int heavyDtoLegacy(
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
     * GET /api/v1/heavy/producer - public producer-response API for object-graph-free JSON.
     *
     * <p>This is the user-facing shape for hot dynamic JSON when direct handler signatures are too
     * low-level but building a DTO list is too expensive.</p>
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/heavy/producer",
            requestType = Void.class,
            responseType = JsonBodyProducer.class
    )
    @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
    @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-producer-conservative")
    @RouteAdmission(maxConcurrent = 80, queueTimeoutMs = 150)
    public JsonBodyProducer heavyProducer(int itemCount) {
        return new HeavyJsonProducer(
                itemCount,
                System.currentTimeMillis(),
                System.nanoTime()
        );
    }

    @RustRoute(
            method = "GET",
            path = "/api/v1/heavy/producer/async",
            requestType = Void.class,
            responseType = JsonBodyProducer.class
    )
    @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
    @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-producer-conservative")
    @RouteAdmission(maxConcurrent = 80, queueTimeoutMs = 150)
    public CompletionStage<JsonBodyProducer> heavyProducerAsync(int itemCount) {
        long timestamp = System.currentTimeMillis();
        long nanosBase = System.nanoTime();
        return AsyncHandlerExecutor.getInstance().submit(() -> (JsonBodyProducer) new HeavyJsonProducer(
                itemCount,
                timestamp,
                nanosBase
        ));
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
    @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-native")
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
    @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
    @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-cache")
    public RawResponse heavyCache(int itemCount) {
        String cacheKey = "heavy:items=" + itemCount;
        int nativeId = NativeBridge.lookupDynamicResponse(cacheKey);
        if (nativeId > 0) {
            return RawResponse.nativeJson(nativeId);
        }

        long now = System.currentTimeMillis();
        byte[] payload = createHeavyResponseBytes(itemCount, now, System.nanoTime());
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
    @NativeStaticRoute
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
    @NativeStaticFileRoute
    public FileResponse exportFile() {
        FileResponse response = sampleExportResponse;
        if (response != null) {
            return response;
        }

        synchronized (EXPORT_FILE_LOCK) {
            response = sampleExportResponse;
            if (response == null) {
                Path exportFile = ensureSampleExportFile();
                response = FileResponse.download(exportFile, "sample-export.csv", "text/csv")
                        .header("Cache-Control", "no-store");
                sampleExportResponse = response;
            }
            return response;
        }
    }

    /**
     * GET /api/v1/export/file-large - Stream bulkhead benchmark için büyük dosya yolu.
     *
     * <p>Dosya içerik olarak sabit üretilir; benchmark'ın amacı business CPU değil,
     * Rust-native file stream backpressure ve 503 davranışını ölçmektir.</p>
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/export/file-large",
            requestType = Void.class,
            responseType = FileResponse.class
    )
    @NativeStaticFileRoute
    public FileResponse exportLargeFile() {
        FileResponse response = largeExportResponse;
        if (response != null) {
            return response;
        }

        synchronized (EXPORT_FILE_LOCK) {
            response = largeExportResponse;
            if (response == null) {
                Path exportFile = ensureLargeExportFile();
                response = FileResponse.download(exportFile, "large-export.bin", "application/octet-stream")
                        .header("Cache-Control", "no-store");
                largeExportResponse = response;
            }
            return response;
        }
    }

    /**
     * GET /api/v1/export/static - Küçük/static export için native registered response.
     *
     * <p>Bu yol dosyayı her request'te açmaz. Dosya bir kez üretilip Rust native
     * response registry'ye taşınır; request hot path sadece native response id döner.</p>
     */
    @RustRoute(
            method = "GET",
            path = "/api/v1/export/static",
            requestType = Void.class,
            responseType = RawResponse.class
    )
    @NativeStaticRoute
    public RawResponse exportStatic() {
        RawResponse response = sampleExportStaticResponse;
        if (response != null) {
            return response;
        }

        synchronized (EXPORT_FILE_LOCK) {
            response = sampleExportStaticResponse;
            if (response == null) {
                Path exportFile = ensureSampleExportFile();
                try {
                    response = RawResponse.registered(
                            Files.readAllBytes(exportFile),
                            Map.of(
                                    "Content-Type", "text/csv",
                                    "Content-Disposition", "attachment; filename=\"sample-export.csv\"",
                                    "Cache-Control", "no-store"
                            ),
                            200
                    );
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot register sample export file", e);
                }
                sampleExportStaticResponse = response;
            }
            return response;
        }
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

    private static Path ensureLargeExportFile() {
        try {
            if (Files.isRegularFile(LARGE_EXPORT_FILE) && Files.size(LARGE_EXPORT_FILE) == LARGE_EXPORT_BYTES) {
                return LARGE_EXPORT_FILE;
            }
        } catch (IOException ignored) {
            // Regenerate below.
        }

        synchronized (EXPORT_FILE_LOCK) {
            try {
                if (Files.isRegularFile(LARGE_EXPORT_FILE) && Files.size(LARGE_EXPORT_FILE) == LARGE_EXPORT_BYTES) {
                    return LARGE_EXPORT_FILE;
                }

                Files.createDirectories(LARGE_EXPORT_FILE.getParent());
                byte[] chunk = new byte[64 * 1024];
                for (int i = 0; i < chunk.length; i++) {
                    chunk[i] = (byte) ('A' + (i % 26));
                }

                try (OutputStream out = Files.newOutputStream(
                        LARGE_EXPORT_FILE,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )) {
                    int remaining = LARGE_EXPORT_BYTES;
                    while (remaining > 0) {
                        int write = Math.min(chunk.length, remaining);
                        out.write(chunk, 0, write);
                        remaining -= write;
                    }
                }
                return LARGE_EXPORT_FILE;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create large export file", e);
            }
        }
    }

    private static byte[] createSampleOrderBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
        int written = BenchmarkOrderRequestJsonWriter.INSTANCE.writeSampleOrder(buffer, 0);
        if (written < 0) {
            int required = -written;
            buffer = ByteBuffer.allocate(required);
            written = BenchmarkOrderRequestJsonWriter.INSTANCE.writeSampleOrder(buffer, 0);
        }
        if (written < 0) {
            throw new IllegalStateException("Precomputed candidates/direct JSON exceeded buffer: " + -written);
        }
        byte[] bytes = new byte[written];
        buffer.position(0);
        buffer.get(bytes, 0, written);
        return bytes;
    }

    private static byte[] createHeavyResponseBytes(int itemCount, long timestamp, long nanosBase) {
        int capacity = Math.max(32 * 1024, itemCount * 256);
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        int written = HeavyResponseDirectWriter.write(buffer, 0, itemCount, timestamp, nanosBase);
        if (written < 0) {
            int required = -written;
            buffer = ByteBuffer.allocate(required);
            written = HeavyResponseDirectWriter.write(buffer, 0, itemCount, timestamp, nanosBase);
        }
        if (written < 0) {
            throw new IllegalStateException("Heavy JSON exceeded direct byte buffer: " + -written);
        }
        byte[] bytes = new byte[written];
        buffer.position(0);
        buffer.get(bytes, 0, written);
        return bytes;
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

    private static final class HeavyJsonProducer implements JsonBodyProducer {
        private final int itemCount;
        private final long timestamp;
        private final long nanosBase;

        private HeavyJsonProducer(int itemCount, long timestamp, long nanosBase) {
            this.itemCount = itemCount;
            this.timestamp = timestamp;
            this.nanosBase = nanosBase;
        }

        @Override
        public int write(ByteBuffer out, int offset) {
            return HeavyResponseDirectWriter.write(out, offset, itemCount, timestamp, nanosBase);
        }
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

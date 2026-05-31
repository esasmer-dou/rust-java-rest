# Rust-Java REST Framework

[![Version](https://img.shields.io/badge/version-3.1.0--rc5-blue.svg)](https://github.com/esasmer-dou/rust-java-rest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Profile](https://img.shields.io/badge/profile-low--rss-green.svg)]()
[![Status](https://img.shields.io/badge/status-performance--preview-orange.svg)]()

Low-latency REST framework where Rust handles HTTP I/O and Java keeps the application code.

## v3.1.0-rc5 - Low-RSS File Streaming and Benchmark Visibility

The programming model stays familiar: handlers, services, components, and business logic are Java.
Rust runs the HTTP I/O plane, native response paths, file streaming, overload protection, and selected
serialization-heavy fast paths.

Treat `v3.1.0-rc5` as a measured performance preview. It is ready for pilots and controlled production
trials, especially for small JSON, raw/precomputed JSON, direct writer, native cache, and file response
paths. Endpoints that build large Java DTO graphs still need route-level tuning when RSS or p99 is
critical.

### What's New in rc5

- `@NativeStaticFileRoute` can register immutable file routes once and let Rust serve runtime requests.
- Small immutable files can be inlined in native memory with `reactor.rust.static-file.inline-max-bytes`.
- Large immutable files stay disk-backed and are protected by `reactor.rust.static-file.max-concurrent-streams`.
- File stream chunking is explicit with `reactor.rust.file-stream.chunk-bytes`.
- Benchmark reports now separate echo raw/parse, small JSON legacy/direct, dynamic DTO, direct writer,
  Rust writer, raw JSON, native cache, static file, and large stream paths.
- Benchmark harness can append JVM property overrides with `-FrameworkJavaOptsAppend`.
- Native ABI is `19`; use the DLL/SO shipped with this package.
- The UTF-8 fixes from `v3.1.0-rc4` remain in place for response bodies, path variables, request params,
  cookies, middleware query helpers, and WebSocket path/query maps.

### Verification

Validated locally with:

```bash
mvn -q test
mvn -q -DskipTests package
cargo test
```

Latest benchmark evidence:

- General low-RSS benchmark: `benchmark/results/current_full_20260531_090441/summary.md`
- Large file stream matrix: `benchmark/results/stream_matrix_{32,64,128,256}_20260531_085157/summary.md`

### Start Here

Use the framework like a lightweight Java REST runtime first. Add the dependency, write handlers and
services in Java, and return record DTOs for normal JSON APIs. Move to the faster response paths only
when the route shape benefits from them.

Quick decision map:

| Your endpoint | Start with | Move to this when needed | Main tuning knobs |
|---------------|------------|--------------------------|-------------------|
| Normal JSON API | Java record request/response DTOs | Direct primitive bindings for hot params | `reactor.runtime.profile`, body/response limits |
| Small read-heavy JSON | `RawResponse.json(...)` | `RawResponse.registeredJson(...)` + `@NativeStaticRoute` | `reactor.rust.native-cache.*` |
| Large file/export | `FileResponse` | `@NativeStaticFileRoute` for immutable files | `file-stream.chunk-bytes`, `static-file.max-concurrent-streams` |
| Hot generated JSON | record DTO | `JsonBufferWriter` or `DirectJsonWriterRegistry` | `json.direct-writer-enabled`, writer buffer caps |
| WebSocket push | `WebSocketSession.sendText(...)` | Tune bounded outbound queues | `websocket.outbound-queue-capacity`, `websocket.max-frame-bytes` |
| Very low RSS service | `low-rss` profile | Lower stream/queue/pool limits route by route | max connections, in-flight bytes, response pool caps |

Profile quick choice:

| Profile | Use when | What to expect |
|---------|----------|----------------|
| `low-rss` | Memory is the priority and overload can return controlled `503` | Lowest practical RSS, stricter queues, fail-fast under pressure |
| `balanced` | External RPC, heavier handlers, or higher concurrency need smoother p99 | More worker/queue headroom, higher RSS than `low-rss` |
| `throughput` | Dedicated high-throughput service or benchmark profile | More retained buffers/workers, higher memory budget |
| `micro-rss` / `ultra-low-rss` | Experiments or very small services | Aggressive memory limits; not ideal for high fanout downloads |

Recommended first production-like settings:

```properties
reactor.runtime.profile=low-rss
reactor.rust.http.max-request-body-bytes=1048576
reactor.rust.http.max-response-body-bytes=8388608
reactor.rust.http.max-inflight-response-bytes=16777216
reactor.rust.http.max-connections=1024
reactor.rust.file-stream.chunk-bytes=65536
reactor.rust.static-file.inline-max-bytes=524288
reactor.rust.static-file.max-concurrent-streams=64
reactor.rust.log.level=error
reactor.rust.java.log.level=warn
```

If one route needs larger bodies or more file concurrency, tune that scenario deliberately. Avoid making
global limits large just because one endpoint needs extra room.

### Response Path Playbook

Pick the simplest path that fits the route. Do not move every endpoint to the fastest-looking API.
The fast paths remove specific costs, and they also make the route more explicit.

| Route situation | Use this | Annotation/API | Runtime flow | Main effect |
|-----------------|----------|----------------|--------------|-------------|
| Small JSON | Java record DTO | `@GetMapping` / `@PostMapping` or `@RustRoute` with `responseType = MyRecord.class` | Rust receives HTTP, Java handler returns a record, DSL-JSON serializes, Rust writes bytes | Best default for normal REST APIs |
| Raw/precomputed JSON | `RawResponse.json(...)` or `RawResponse.text(...)` | route `responseType = RawResponse.class` | Java returns already serialized bytes, framework skips DTO serialization | Avoids building/serializing a DTO when JSON already exists |
| Native cache JSON | `RawResponse.registeredJson(...)`, `RawResponse.nativeJson(id)`, `NativeBridge.registerDynamicResponse(...)` | optional `@NativeStaticRoute` for immutable routes | Body is stored in Rust native memory and later requests return a small native id | Removes repeated Java-to-Rust body transfer on cache hits |
| Direct JSON writer | `JsonBufferWriter` or generated direct writer | `@RustRoute`, optional `@DirectQuery*` / `@DirectPath*` for hot scalar params | Java writes JSON directly into the response buffer | Reduces DTO graph allocation and serializer temporary buffers |
| Dynamic DTO | Java records plus DSL-JSON | `responseType = MyRecord.class` | Java business code creates a record/list graph, DSL-JSON serializes it | Easiest business model, but object graph cost stays in Java |

#### 1. Small JSON: normal REST default

Use this for CRUD, simple query endpoints, command responses, health-style JSON, and most business
APIs. Your request/response contracts should be Java records. Handlers and services remain classes.

```java
public record ProductResponse(long id, String name, boolean active) {}

@GetMapping(value = "/products/{id}", requestType = Void.class, responseType = ProductResponse.class)
public ProductResponse product(@PathVariable("id") String id) {
    return productService.find(Long.parseLong(id));
}
```

What you get: simple code, stable JSON contracts, and good latency for ordinary payloads. What it does
not remove: Java still creates the record object and DSL-JSON still serializes it. If this route becomes
hot and scalar query/path parsing shows up in measurements, add direct primitive binding only for that
hot parameter. Query params use `@DirectQueryInt`, `@DirectQueryLong`, `@DirectQueryBoolean`,
`@DirectQueryDouble`, or `@DirectQueryShort`. Path params use the matching `@DirectPath*` annotation.

```java
@RustRoute(method = "GET", path = "/products", requestType = Void.class, responseType = ProductResponse.class)
@DirectQueryLong(value = "id", min = 1)
public int product(ByteBuffer out, int offset, long id) {
    ProductResponse response = productService.find(id);
    return DslJsonService.writeToBuffer(response, out, offset);
}
```

#### 2. Raw/precomputed JSON: JSON already exists

Use this when another layer already gives you JSON bytes: Redis value, external RPC response, generated
read model, metrics output, configuration payload, or a precomputed report summary. Do not parse that
JSON into a DTO just to serialize it again.

```java
@GetMapping(value = "/catalog/raw", requestType = Void.class, responseType = RawResponse.class)
public RawResponse catalogRaw() {
    byte[] json = catalogReadModel.getJsonBytes();
    return RawResponse.json(json);
}
```

Effect: removes DTO allocation and DSL-JSON serialization for this route. It does not automatically
cache every response; Java still returns the bytes for the current request. Use it when the payload is
already trustworthy JSON and your service owns the content type.

#### 3. Native cache JSON: repeated read-heavy response

Use this only when the same JSON response is expected to be reused many times with a clear key and a
clear invalidation or TTL rule. Good examples: feature flags, product configuration, public catalog
snapshots, tenant-independent lookup data, and expensive read models refreshed every few minutes.

For immutable responses, register once and let Rust serve the route directly:

```java
private static final RawResponse PRODUCT_CONFIG =
        RawResponse.registeredJson("""
        {"currency":"TRY","taxIncluded":true}
        """.getBytes(StandardCharsets.UTF_8));

@GetMapping(value = "/product-config", requestType = Void.class, responseType = RawResponse.class)
@NativeStaticRoute
public RawResponse productConfig() {
    return PRODUCT_CONFIG;
}
```

For dynamic-but-repeatable responses, use an explicit bounded native cache key:

```java
@GetMapping(value = "/catalog/cache", requestType = Void.class, responseType = RawResponse.class)
public RawResponse catalogCached() {
    String key = "catalog:v1";
    int nativeId = NativeBridge.lookupDynamicResponse(key);
    if (nativeId > 0) {
        return RawResponse.nativeJson(nativeId);
    }

    byte[] payload = catalogReadModel.renderJson();
    int registeredId = NativeBridge.registerDynamicResponse(
            key,
            payload,
            "Content-Type: application/json\n",
            200,
            300_000L
    );
    return registeredId > 0 ? RawResponse.nativeJson(registeredId) : RawResponse.json(payload);
}
```

Effect: cache hits avoid rebuilding the payload and avoid moving the full body from Java to Rust again.
Cost: cached bodies live in native memory, so keep `reactor.rust.native-cache.*` bounded. Do not use this
for user-specific, highly unique, authorization-sensitive, or constantly changing responses.

#### 4. Direct JSON writer: hot fixed-shape JSON

Use this when a benchmark shows DTO graph allocation, temporary serializer buffers, or p99 latency on a
hot route. The response shape should be stable and easy to test. The Java handler still owns business
logic, but it writes the JSON directly into the native response buffer.

```java
@RustRoute(method = "GET", path = "/stats", requestType = Void.class, responseType = StatsResponse.class)
@DirectQueryInt(value = "limit", defaultValue = 10, min = 1, max = 100)
public int stats(ByteBuffer out, int offset, int limit) {
    return JsonBufferWriter.reusable(out, offset)
            .beginObject()
            .fieldString("status", "ok")
            .comma()
            .fieldInt("limit", limit)
            .endObject()
            .result();
}

public record StatsResponse(String status, int limit) {}
```

Effect: avoids building a response DTO graph for the hot path and avoids serializer-owned temporary
buffers. Trade-off: this is more manual than returning a record. Keep golden JSON tests for direct
writers and use this only where measurements justify it.

#### 5. Dynamic DTO: business-shaped response

Use this when the response is naturally a business object graph: nested domain response, validation
result, search result, screen model, or anything where readability and correctness are more important
than micro-optimizing one route.

```java
public record CatalogResponse(String category, List<ProductResponse> products) {}

@GetMapping(value = "/catalog", requestType = Void.class, responseType = CatalogResponse.class)
public CatalogResponse catalog(@RequestParam("category") String category) {
    return catalogService.buildCatalog(category);
}
```

Effect: easiest implementation and the right default for real business APIs. Cost: every request can
allocate the Java object graph. If the route becomes a top p99/RSS contributor, move only that route to
direct writer, raw/precomputed JSON, or native cache depending on the actual access pattern.

#### Selection Rule

| If you are thinking... | Choose |
|------------------------|--------|
| "This is a normal API response." | Small JSON or dynamic DTO with records |
| "I already have JSON bytes." | Raw/precomputed JSON |
| "The same JSON repeats often and can expire by key/TTL." | Native cache JSON |
| "This one route is hot and has a fixed JSON shape." | Direct JSON writer |
| "The response is complex business data and not proven hot." | Dynamic DTO |

### DTO, Runtime Class, and Response Model Decision Guide

This framework has a strict design rule for application data contracts:

```text
JSON request DTO and JSON response DTO = Java record
Runtime behavior object = Java class is allowed
Pre-serialized or native response = RawResponse/FileResponse/direct writer, not DTO
```

The rule is easy to misunderstand. "Record-only" does not mean every Java type in your application
must be a record. It means objects that represent HTTP JSON input/output should be immutable,
constructor-based records. Classes are still the right tool for handlers, services, repositories,
configuration, adapters, pools, clients, lifecycle owners, and other objects that hold behavior or
resources.

Why this matters:

- Records give a stable JSON contract: fields are explicit in the canonical constructor.
- Records avoid JavaBean setter/proxy style programming.
- Records fit DSL-JSON compile-time serialization better than mutable POJOs.
- Records make request/response objects immutable, easier to validate, and easier to reason about.
- Runtime classes are still needed for DI, stateful resources, connection pools, clients, and lifecycle.

#### Decision Table

| Use case | Recommended | Also OK | Avoid |
|----------|-------------|---------|-------|
| HTTP JSON request body | `record OrderCreateRequest(...)` | `byte[]` plus direct custom parser for a hot route | Mutable POJO with setters |
| HTTP JSON response body | `record OrderResponse(...)` | `RawResponse` if JSON is already serialized | Returning entity/service classes as JSON |
| Read-heavy cached JSON | `RawResponse.registeredJson(...)` | `RawResponse.json(byte[])` | Rebuilding the same DTO graph on every request |
| Large file/export | `FileResponse` | `@NativeStaticFileRoute` for immutable files | `byte[]` or huge `String` in Java heap |
| Hot predictable JSON | `JsonBufferWriter` or generated direct writer | Record DTO for normal traffic | Reflection-heavy generic object graph |
| Handler/controller | `class OrderHandler` | `final class` with constructor/DI fields | Record handler with hidden runtime state |
| Service/business component | `class OrderService` | Interface + class implementation | DTO record with methods and runtime state |
| Repository/client/pool | `class OrderRepository`, `class RpcClientAdapter` | Final class with explicit close lifecycle | Record holding connections/resources |
| Config value object | `record ServerLimits(...)` | Properties-backed class for loader behavior | Static mutable global config everywhere |

#### Use Case 1: Normal Business REST Endpoint

Use records for request and response DTOs. Use classes for the handler and service.

```java
@Request
@CompiledJson
public record CreateOrderRequest(
        String customerId,
        BigDecimal amount
) {}

@Response
@CompiledJson
public record CreateOrderResponse(
        String orderId,
        String status
) {}

@Service
public final class OrderService {
    public CreateOrderResponse create(CreateOrderRequest request) {
        return new CreateOrderResponse("ORD-1001", "ACCEPTED");
    }
}

@Component
@RequestMapping("/orders")
public final class OrderHandler {

    @Autowired
    private OrderService orderService;

    @PostMapping(
            value = "/create",
            requestType = CreateOrderRequest.class,
            responseType = CreateOrderResponse.class
    )
    public ResponseEntity<CreateOrderResponse> create(@RequestBody CreateOrderRequest request) {
        return ResponseEntity.ok(orderService.create(request));
    }
}
```

Here the records are the HTTP contract. `OrderHandler` and `OrderService` are classes because they
represent behavior and dependencies.

#### Use Case 2: Database Read Model

The data returned by the repository can be a record. The repository itself should be a class because it
owns database access and lifecycle.

```java
public record CustomerRow(
        long id,
        String customerNo,
        String fullName
) {}

@Repository
public final class CustomerRepository implements AutoCloseable {
    public List<CustomerRow> findCustomers() {
        // Query DB and map rows to immutable records.
        return List.of(new CustomerRow(1, "CUST-1001", "Mustafa Korkmaz"));
    }

    @Override
    public void close() {
        // Close pool/session resources here.
    }
}
```

Do not make `CustomerRepository` a record. It is not a JSON contract; it is a runtime component.

#### Use Case 3: Already Serialized JSON

If another system, cache, RPC provider, or native code already returns JSON bytes, do not deserialize
those bytes into a DTO just to serialize them again.

```java
@GetMapping(value = "/catalog", requestType = Void.class, responseType = RawResponse.class)
public RawResponse catalog() {
    byte[] json = catalogRpcClient.fetchCatalogJson();
    return RawResponse.json(json);
}
```

This path intentionally bypasses DTO serialization. It is correct for pre-serialized JSON.

#### Use Case 4: Cached or Mostly Static JSON

When the same response is returned many times, register it once and return the native response id.
This avoids copying the body from Java to Rust on every request.

```java
private static final RawResponse PRODUCT_CONFIG =
        RawResponse.registeredJson("""
        {"currency":"TRY","taxIncluded":true}
        """.getBytes(StandardCharsets.UTF_8));

@GetMapping(value = "/product-config", requestType = Void.class, responseType = RawResponse.class)
public RawResponse productConfig() {
    return RawResponse.nativeJson(PRODUCT_CONFIG.getNativeId());
}
```

For a truly immutable route, add `@NativeStaticRoute`. The handler is called once during startup;
runtime requests are served directly by Rust without entering the Java handler or JNI queue.

```java
private static final RawResponse PRODUCT_CONFIG =
        RawResponse.registeredJson("""
        {"currency":"TRY","taxIncluded":true}
        """.getBytes(StandardCharsets.UTF_8));

@GetMapping(value = "/product-config", requestType = Void.class, responseType = RawResponse.class)
@NativeStaticRoute
public RawResponse productConfig() {
    return PRODUCT_CONFIG;
}
```

Use `@NativeStaticRoute` only for immutable/precomputed responses. Do not use it for user-specific,
time-dependent, authorization-dependent, or database-backed responses unless the full response is
intentionally static.

#### Use Case 5: Large File or Export

Large files should not be carried through Java heap as a DTO, `String`, or `byte[]`.

```java
@GetMapping(value = "/exports/customers", requestType = Void.class, responseType = FileResponse.class)
public FileResponse exportCustomers() {
    return FileResponse.download(
            Path.of("/data/exports/customers.csv"),
            "customers.csv",
            "text/csv; charset=utf-8");
}
```

Rust streams the file body. Java only decides which file to return.

#### Use Case 6: Hot JSON Endpoint Without DTO Graph

For a very hot endpoint with predictable JSON, use a direct writer. This is an explicit performance
path, not the default style for all endpoints.

```java
@RustRoute(
        method = "GET",
        path = "/api/v1/stats",
        requestType = Void.class,
        responseType = StatsResponse.class
)
public int stats(ByteBuffer out, int offset) {
    JsonBufferWriter json = JsonBufferWriter.wrap(out, offset);
    json.beginObject()
            .fieldString("status", "ok")
            .comma()
            .fieldLong("activeUsers", 1250)
            .endObject();
    return json.result();
}

public record StatsResponse(String status, long activeUsers) {}
```

The `responseType` records the route contract. The actual hot path writes directly into the native
buffer.

#### What Not To Do

Avoid mutable DTO classes:

```java
public class BadOrderResponse {
    public String orderId;
    public String status;
}
```

Avoid returning business/runtime objects as JSON:

```java
@GetMapping(value = "/orders/{id}", responseType = OrderService.class)
public OrderService bad() {
    return orderService;
}
```

Avoid turning everything into records:

```java
// Wrong role: this object owns behavior and resources, so class is correct.
public record BadRepository(DataSource dataSource) {}
```

Production rule: if the type is part of the HTTP JSON contract, make it a record. If it owns behavior,
resource lifecycle, DI state, client pools, or runtime wiring, make it a class.

#### 1. Install once, native runtime included

What it does: adds the Java framework and the matching Rust native runtime with one Maven dependency.
For normal usage you do not copy DLL/SO files manually. The JAR already contains:
`native/windows-x64/rust_hyper.dll` and `native/linux-x64/librust_hyper.so`.

How to use it:

```xml
<dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-rest</artifactId>
    <version>3.1.0-rc5</version>
</dependency>
```

Only use a manual native path when you intentionally want to test or deploy a custom native build:

```bash
java -Drust.lib.path=/opt/native/librust_hyper.so -jar app.jar
```

If Java loads an old native library, startup fails early with an ABI mismatch instead of failing later
under traffic.

#### 2. Use `RawResponse` when the response is already JSON/text/bytes

What it does: returns a payload directly without creating a response DTO and serializing it again.

Use it when the response is precomputed, cached, simple, or already serialized. Do not use it for every
dynamic endpoint by default; normal DTO responses are still fine for regular business APIs.

Example:

```java
@GetMapping(value = "/health/raw", requestType = Void.class, responseType = RawResponse.class)
public RawResponse health() {
    return RawResponse.text("{\"status\":\"ok\"}", "application/json; charset=utf-8");
}
```

For read-heavy payloads that repeat often, register once in Rust and return the native id:

```java
private static final RawResponse CACHED_CONFIG =
        RawResponse.registeredJson("""
        {"feature":"enabled","version":"3.1.0-rc5"}
        """.getBytes(StandardCharsets.UTF_8));

@GetMapping(value = "/config", requestType = Void.class, responseType = RawResponse.class)
public RawResponse config() {
    return RawResponse.nativeJson(CACHED_CONFIG.getNativeId());
}
```

#### 3. Use `FileResponse` for files, exports, and static downloads

What it does: Java returns the file path and headers; Rust streams the file to the socket. The file body
does not move through Java heap or a JNI response frame.

Use it for large downloads, generated exports, reports, static files, and any response where loading the
whole file into a Java `byte[]` would hurt memory.

For high-concurrency downloads, tune the native stream chunk explicitly:

```properties
reactor.rust.file-stream.chunk-bytes=65536
```

Smaller chunks protect RSS; larger chunks can reduce read/frame overhead. Do not raise this blindly for
all services. Measure p99 and RSS with your expected download size and concurrency.

If the same file is always served by the route, add `@NativeStaticFileRoute`. The handler is called once
at startup; runtime requests do not enter the Java handler or JNI response frame.
Rust also caches the file length and parsed response headers at registration time, so this path avoids
per-request file metadata lookup and header re-parsing. Treat the file as immutable until restart.
Files at or below `reactor.rust.static-file.inline-max-bytes` are also loaded into native memory once,
which removes disk I/O from the request path. Low-RSS defaults to `524288` bytes; larger files stay
streamed from disk.
Disk-backed streams are protected by `reactor.rust.static-file.max-concurrent-streams`. If this
bulkhead is full the framework returns `503`, which is intentional overload protection for file
descriptors, native I/O workers, RSS, and p99.

```java
@RustRoute(
        method = "GET",
        path = "/reports/static-daily",
        requestType = Void.class,
        responseType = FileResponse.class
)
@NativeStaticFileRoute
public FileResponse staticDailyReport() {
    return FileResponse.download(
            Path.of("/data/reports/daily.csv"),
            "daily.csv",
            "text/csv; charset=utf-8");
}
```

Do not use `@NativeStaticFileRoute` when authorization, tenant, query parameters, or database state decide
which file should be returned. Keep those routes on normal `FileResponse`.

Example:

```java
@GetMapping(value = "/reports/daily", requestType = Void.class, responseType = FileResponse.class)
public FileResponse dailyReport() {
    Path file = Path.of("/data/reports/daily.csv");
    return FileResponse.download(file, "daily.csv", "text/csv")
            .header("Cache-Control", "no-store");
}
```

#### 4. Use per-route body limits instead of one global oversized limit

What it does: keeps default memory limits tight while allowing specific routes to accept or return
larger bodies.

Use it when one endpoint legitimately needs bigger request or response bodies. Avoid raising global
limits just because one route needs them.

Example:

```java
@PostMapping(value = "/upload", requestType = UploadRequest.class, responseType = UploadResult.class)
@MaxRequestBodySize(8 * 1024 * 1024)
@MaxResponseSize(2 * 1024 * 1024)
public UploadResult upload(@RequestBody UploadRequest request) {
    return uploadService.save(request);
}
```

#### 5. Use `JsonBufferWriter` for hot JSON endpoints

What it does: lets a handler write JSON directly into the native response buffer. This avoids building a
large Java DTO graph and avoids an extra serializer-owned `byte[]` for selected hot paths.

Use it for endpoints that are called very frequently or produce heavy JSON in a predictable shape. Keep
normal DTO handlers for ordinary business endpoints where maintainability is more important than the
last allocation.

Example:

```java
@RustRoute(
        method = "GET",
        path = "/api/v1/heavy",
        requestType = Void.class,
        responseType = HeavyResponse.class
)
@DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
public int heavy(ByteBuffer out, int offset, int items) {
    JsonBufferWriter json = JsonBufferWriter.wrap(out, offset);
    json.beginObject()
            .fieldInt("items", items)
            .comma()
            .fieldString("status", "ok")
            .endObject();
    return json.result();
}
```

`@DirectQueryInt` means Rust parses `?items=100` and passes a primitive `int` to Java. That avoids
allocating and parsing query strings on this hot route. The same direct primitive path is available
for query/path `int`, `long`, `boolean`, `double`, and `short` values:

```java
@DirectQueryDouble(value = "amount", defaultValue = 0.0, min = 0.0, max = 1_000_000.0)
public int quote(ByteBuffer out, int offset, double amount) {
    return JsonBufferWriter.reusable(out, offset)
            .beginObject()
            .fieldDouble("amount", amount)
            .endObject()
            .result();
}

@GetMapping(value = "/stores/{code}", requestType = Void.class, responseType = StoreResponse.class)
@DirectPathShort(value = "code", min = 1, max = 999)
public int store(ByteBuffer out, int offset, short code) {
    return JsonBufferWriter.reusable(out, offset)
            .beginObject()
            .fieldInt("code", code)
            .endObject()
            .result();
}
```

Use direct primitive annotations only for hot, simple routes. If a route has many parameters or complex
validation rules, prefer a request record or a normal annotated handler.

#### 5.1 Use generated/direct JSON writers for selected DTOs

What it does: if a `DirectJsonWriter` is registered for an exact DTO class, the framework writes that
DTO directly into the response `ByteBuffer` before trying DSL-JSON. This removes the serializer-owned
temporary `byte[]` on DTOs where you deliberately provide a generated/manual writer.

Manual registration example:

```java
public record CityResponse(String city, int plate) {}

public enum CityResponseWriter implements DirectJsonWriter<CityResponse> {
    INSTANCE;

    @Override
    public int write(CityResponse value, ByteBuffer out, int offset) {
        return JsonBufferWriter.reusable(out, offset)
                .beginObject()
                .fieldString("city", value.city())
                .comma()
                .fieldInt("plate", value.plate())
                .endObject()
                .result();
    }
}

public final class AppBootstrap {
    static {
        DirectJsonWriterRegistry.register(CityResponse.class, CityResponseWriter.INSTANCE);
    }
}
```

Build-time generators can expose writers through `DirectJsonWriterProvider` and `META-INF/services`.
Keep providers exact-class based. Broad reflection-based writers should be avoided because they bring
back the allocation and branch cost this path is meant to remove.

Property:

```properties
reactor.rust.json.direct-writer-enabled=true
```

#### 6. WebSocket sends are now bounded and production-safe

What it does: `WebSocketSession.sendText(...)`, `sendBinary(...)`, and `close(...)` now use a Rust-side
session registry with bounded outbound queues.

Use it the same way as before, but expect slow consumers to be rejected/closed instead of allowing
unbounded memory growth.

Example:

```java
@OnMessage
public void onMessage(WebSocketSession session, String message) {
    String roomId = session.getPathParams().get("roomId");
    WebSocketBroadcaster.getInstance().broadcastToRoom(roomId, message, session);
}
```

Tune the queue and frame limits when you know your WebSocket traffic profile:

```properties
reactor.rust.websocket.max-frame-bytes=1048576
reactor.rust.websocket.outbound-queue-capacity=1024
reactor.rust.websocket.send-timeout-ms=5000
```

#### 7. Built-in observability is available immediately

What it does: exposes the metrics needed to understand latency, memory, backpressure, and native
runtime behavior.

Use it during benchmark runs, container tuning, and production readiness checks.

```bash
curl http://localhost:8080/metrics
curl http://localhost:8080/metrics/summary
curl http://localhost:8080/diagnostics/memory
curl http://localhost:8080/diagnostics/native/trim
```

`/metrics/reset` is useful for controlled benchmark runs, but should be protected or disabled in a real
production deployment.

#### 8. Runtime tuning is now explicit

What it does: lets you choose between low RSS, throughput, and stricter overload behavior without
changing Java business code.

Start conservative for production-like services:

```properties
reactor.rust.http.max-request-body-bytes=1048576
reactor.rust.http.max-response-body-bytes=8388608
reactor.rust.http.max-inflight-response-bytes=67108864
reactor.rust.http.max-connections=1024
reactor.rust.jni.queue-capacity=1024
reactor.rust.file-stream.chunk-bytes=65536
reactor.rust.static-file.inline-max-bytes=524288
reactor.rust.static-file.max-concurrent-streams=64
reactor.rust.log.level=error
reactor.rust.java.log.level=warn
```

If you increase per-request limits, also cap total in-flight bytes. Raising only
`max-response-body-bytes` can improve one endpoint but damage RSS under concurrency.

Important low-RSS properties:

| Property | What it controls | Low-RSS guidance |
|----------|------------------|------------------|
| `reactor.rust.http.max-request-body-bytes` | Per-request body cap | Keep small by default; override per route for uploads |
| `reactor.rust.http.max-response-body-bytes` | Per-response body cap for framed responses | Do not use this for files; use `FileResponse` |
| `reactor.rust.http.max-inflight-response-bytes` | Total response bytes allowed in flight | Must be lowered when pod memory is tight |
| `reactor.rust.http.max-connections` | Admission limit for HTTP connections | Keep headroom above expected concurrency |
| `reactor.rust.file-stream.chunk-bytes` | Disk file stream read chunk | `32768`-`65536` for low RSS, higher only after measurement |
| `reactor.rust.static-file.inline-max-bytes` | Max immutable file size pinned in native memory | Keep small; large files should stream |
| `reactor.rust.static-file.max-concurrent-streams` | Large file stream bulkhead | `32` or `64` is safer for low-RSS services |
| `reactor.rust.response-pool.*-capacity` | Native response buffer retention | Smaller caps reduce RSS retention |
| `reactor.rust.native-cache.max-bytes` | Native response cache memory cap | Use only for explicit read-heavy payloads |

### Use Case Tuning Recipes

Use these as starting points, not fixed production values. Run your own benchmark with expected payload
size, concurrency, CPU limit, and pod memory limit.

#### Normal CRUD / Business JSON

Recommended shape:

- Use record request/response DTOs.
- Keep handlers and services as classes.
- Use `low-rss` for memory-sensitive services, `balanced` if the handler does blocking RPC or database work.

```properties
reactor.runtime.profile=low-rss
reactor.rust.http.max-request-body-bytes=1048576
reactor.rust.http.max-response-body-bytes=8388608
reactor.rust.http.max-inflight-response-bytes=16777216
reactor.rust.jni.queue-capacity=512
```

When to change it: if p99 rises because the Java handler blocks on external systems, move to `balanced`
or add route-level bulkhead/backpressure before increasing global queues.

#### Read-Heavy Config / Lookup JSON

Recommended shape:

- If JSON is already bytes, return `RawResponse.json(...)`.
- If the response is immutable until restart, use `RawResponse.registeredJson(...)` and `@NativeStaticRoute`.
- Keep native cache bounded; do not cache every dynamic response by default.

```properties
reactor.rust.native-cache.max-entries=256
reactor.rust.native-cache.max-bytes=4194304
reactor.rust.native-cache.ttl-ms=300000
```

When to change it: raise cache bytes only when hit ratio is high and RSS after idle stays inside the pod
budget.

#### Large Export / File Download

Recommended shape:

- Use `FileResponse`; do not return a large `byte[]` or `String`.
- Use `@NativeStaticFileRoute` only when the route always serves the same file identity and headers.
- Keep large file fanout bounded. A `503` under overload is healthier than unbounded RSS/p99 growth.

```properties
reactor.rust.file-stream.chunk-bytes=65536
reactor.rust.static-file.inline-max-bytes=524288
reactor.rust.static-file.max-concurrent-streams=64
```

When to change it: lower `max-concurrent-streams` for very large files or small pods. Raise it only for
download-focused services after measuring p99, `503` rate, file descriptors, and RSS.

#### Hot JSON Without DTO Graph

Recommended shape:

- Start with record DTOs.
- Move only hot routes to `JsonBufferWriter`, `DirectJsonWriterRegistry`, or Rust writer.
- Keep the response contract as a record even when the hot path writes directly into the buffer.

```properties
reactor.rust.json.direct-writer-enabled=true
reactor.rust.json.writer-initial-bytes=4096
reactor.rust.json.writer-retain-max-bytes=65536
```

When to change it: if dynamic DTO allocation dominates p99/RSS, direct writer is usually the next step
before increasing heap.

#### WebSocket Push

Recommended shape:

- Use the existing Java `WebSocketSession` API.
- Let Rust enforce bounded outbound queues.
- Size the queue from expected fanout and max frame size.

```properties
reactor.rust.websocket.max-frame-bytes=1048576
reactor.rust.websocket.outbound-queue-capacity=1024
reactor.rust.websocket.send-timeout-ms=5000
```

When to change it: for slow consumers, prefer smaller queues and predictable close behavior over large
queues that hide memory growth.

### Container Benchmark Snapshot

Profile: `low-rss`, CPU limit `2`, Rust-Java memory limit `96m`, Spring Boot memory limit `512m`,
OpenJ9/Semeru 21, concurrency `512/1000`, duration `10s`, warmup `2s`, repeat `1`, randomized order.
This is the latest rc5 working-tree benchmark. Use repeat `3` plus idle/soak before promoting a stable
release.

| Endpoint | Rust-Java RPS | Spring Boot RPS | Ratio | Rust P99 | Spring P99 | Rust Max Mem | Spring Max Mem |
|----------|--------------:|----------------:|------:|---------:|-----------:|-------------:|---------------:|
| candidates c512 | 12,347 | 2,533 | 4.87x | 126ms | 694ms | 80 MiB | 392 MiB |
| candidates c1000 | 13,897 | 1,204 | 11.54x | 289ms | 1.94s | 94 MiB | 310 MiB |
| echo parse c512 | 16,896 | 3,844 | 4.39x | 98ms | 338ms | 90 MiB | 426 MiB |
| echo parse c1000 | 10,970 | 3,904 | 2.81x | 768ms | 614ms | 92 MiB | 423 MiB |
| heavy100 raw c512 | 11,517 | 5,761 | 2.00x | 142ms | 289ms | 91 MiB | 422 MiB |
| heavy100 raw c1000 | 15,247 | 5,050 | 3.02x | 254ms | 517ms | 93 MiB | 444 MiB |
| heavy100 dynamic DTO c512 | 2,812 | 2,193 | 1.28x | 309ms | 515ms | 92 MiB | 420 MiB |
| heavy100 dynamic DTO c1000 | 8,750 | 2,488 | 3.52x | 526ms | 633ms | 76 MiB | 422 MiB |

Rust-Java-only optimized paths in the same run:

| Endpoint | Class | Rust-Java RPS | Rust P99 | Rust Max Mem |
|----------|-------|--------------:|---------:|-------------:|
| candidates direct c512 | small-json-direct | 14,174 | 129ms | 88 MiB |
| echo raw c512 | echo-raw | 13,369 | 118ms | 83 MiB |
| heavy100 direct writer c512 | direct-json-writer | 5,071 | 217ms | 78 MiB |
| heavy100 Rust writer c512 | rust-json-writer | 8,128 | 177ms | 80 MiB |
| heavy100 native cache c512 | native-cache-json | 14,970 | 102ms | 79 MiB |
| export file stream c512 | file-stream | 1,987 | 1.09s | 95 MiB |

Benchmark run id: `current_full_20260531_090441`. Treat this as a low-RSS regression signal, not a
universal marketing claim. At c1000, some low-RSS routes intentionally return `503` when admission or
stream pressure is above the configured budget. Raise profile limits only after measuring RSS and p99.

Large file stream matrix, 8 MiB file, inline disabled:

| Max Streams | C | RPS | P99 | 503 Rate | RSS After | Max Mem |
|------------:|--:|----:|----:|---------:|----------:|--------:|
| 32 | 256 | 1,272 | 1.36s | 97.14% | 14 MiB | 92 MiB |
| 32 | 512 | 2,922 | 860ms | 98.66% | 20 MiB | 82 MiB |
| 64 | 512 | 2,176 | 1.98s | 98.28% | 27 MiB | 94 MiB |
| 128 | 512 | 1,749 | 3.16s | 97.07% | 42 MiB | 84 MiB |
| 256 | 512 | 1,318 | 6.94s | 96.74% | 58 MiB | 94 MiB |

Interpretation: for low-RSS services, `32` or `64` max concurrent streams is the safer starting point.
Higher stream limits accept more file work but worsen p99 and RSS. Returning `503` under overload is
intentional protection, not a correctness failure.

### New Optimizations

| Optimization | Impact |
|--------------|--------|
| **Bounded JNI worker queue** | Predictable overload behavior instead of unbounded blocking/allocation |
| **Direct response buffer writers** | Avoid DTO graph and serializer-owned byte[] for selected hot endpoints |
| **Primitive direct route API** | Query ints can be parsed in Rust and passed as primitives through JNI |
| **Generated-style JSON parser/writer prototype** | Echo path avoids generic reflection/map-style parsing |
| **Response pool and native memory diagnostics** | Lower RSS retention risk and measurable native memory behavior |
| **Raw/File/native response paths** | Large/static/read-heavy responses avoid carrying Java body bytes per request |
| **Native static file inline and stream bulkhead** | Small immutable files can be native-inlined; large streams are bounded and fail fast with 503 |
| **Benchmark endpoint class separation** | Dynamic DTO, direct writer, raw JSON, file stream, and cache paths are measured separately |
| **Timeout/keep-alive/header/body limits** | Production safety knobs for slow clients and bounded resource usage |
| **Low-RSS / throughput / micro-RSS profiles** | Runtime can be tuned by workload instead of one-size-fits-all config |

Release notes: `RELEASE_NOTES.md`. Performance guide: `PERFORMANCE_GUIDE.md`.

---

## What's New in v3.0.0

### 1. WebSocket Support 🆕

Full WebSocket support with annotation-based handlers:

```java
@Component
@WebSocket("/ws/chat/{roomId}")
public class ChatWebSocketHandler {

    @OnOpen
    public void onOpen(WebSocketSession session) {
        String roomId = session.getPathParams().get("roomId");
        session.sendText("{\"type\":\"connected\",\"roomId\":\"" + roomId + "\"}");
    }

    @OnMessage
    public void onMessage(WebSocketSession session, String message) {
        // Broadcast to all sessions in room
        WebSocketBroadcaster.getInstance()
            .broadcastToRoom("room1", message);
    }

    @OnClose
    public void onClose(WebSocketSession session) {
        System.out.println("Session closed: " + session.getId());
    }

    @OnError
    public void onError(WebSocketSession session, String error) {
        System.err.println("Error: " + error);
    }
}
```

**Features:**
- Path parameters (`/ws/chat/{roomId}`)
- Room management and broadcasting
- Binary and text messages
- Session lifecycle callbacks

### 2. Async Handlers (CompletableFuture) 🆕

Non-blocking handlers with virtual threads (Java 21+):

```java
@PostMapping(value = "/order/create", requestType = OrderRequest.class)
public CompletableFuture<ResponseEntity<OrderResponse>> createAsync(
        @RequestBody OrderRequest request) {

    return orderService.createOrderAsync(request)
        .thenApply(order -> ResponseEntity.ok(
            new OrderResponse(order.getId(), "Created")
        ))
        .exceptionally(ex -> ResponseEntity.status(500).body(
            new OrderResponse(-1, "Error: " + ex.getMessage())
        ));
}

// Combine multiple async calls
@GetMapping(value = "/order/{id}/full")
public CompletableFuture<ResponseEntity<FullOrderResponse>> getFullOrder(
        @PathVariable("id") String orderId) {

    CompletableFuture<Order> orderFuture = orderService.getOrderAsync(orderId);
    CompletableFuture<List<Payment>> paymentsFuture = paymentService.getPaymentsAsync(orderId);

    return CompletableFuture.allOf(orderFuture, paymentsFuture)
        .thenApply(v -> new FullOrderResponse(orderFuture.join(), paymentsFuture.join()));
}
```

### 3. Static File Serving 🆕

Production-ready static file serving with caching:

```java
@Component
@StaticFiles(
    path = "/static",
    location = "static",
    cacheMaxAge = 3600,
    indexFile = "index.html"
)
public class StaticFileConfig {}
```

```
GET /static/css/style.css    → classpath:/static/css/style.css
GET /static/js/app.js        → classpath:/static/js/app.js
GET /static/                 → classpath:/static/index.html
```

**Features:**
- 20+ MIME types supported
- Automatic file caching (< 1MB files)
- Multiple static locations
- Cache-Control headers

### 4. Phase 5 Performance Optimizations 🆕

| Optimization | Before | After | Improvement |
|--------------|--------|-------|-------------|
| Annotation lookup | ~200ns | ~5ns | **40x faster** |
| Parameter map lookup | O(n) | O(1) | **Robin-Hood hashing** |
| Header encoding | String allocation | Zero-copy | **No GC pressure** |
| Error responses | allocation | pre-allocated | **Zero allocation** |

---

## v2.0.0 Features (Included in v3.0.0)

All v2.0.0 features are included:
- Zero-overhead Dependency Injection (@Service, @Autowired, @PostConstruct)
- Spring Boot-like annotations (@GetMapping, @PostMapping, etc.)
- ResponseEntity<T> return type support
- Automatic parameter resolution (@PathVariable, @RequestParam, @HeaderParam, @RequestBody)

---

## Installation

### 1. Add Repository

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/esasmer-dou/rust-java-rest</url>
    </repository>
</repositories>
```

### 2. Add Dependency

```xml
<dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-rest</artifactId>
    <version>3.1.0-rc5</version>
</dependency>
```

> **Note:** GitHub Packages requires authentication. Add your GitHub token to `~/.m2/settings.xml`:
>
> ```xml
> <settings>
>     <servers>
>         <server>
>             <id>github</id>
>             <username>GITHUB_USERNAME</username>
>             <password>GITHUB_TOKEN</password>
>         </server>
>     </servers>
> </settings>
> ```
>
> To create a token: GitHub → Settings → Developer settings → Personal access tokens → Generate new token (classic)
> Required scope: `read:packages`

### 3. Add DSL-JSON Annotation Processor

```xml
<build>
    <plugins>
        <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.12.1</version>
            <configuration>
                <release>21</release>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.dslplatform</groupId>
                        <artifactId>dsl-json</artifactId>
                        <version>2.0.2</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

---

## Usage

### Step 1: Create Request DTO

```java
import com.reactor.rust.annotations.Request;
import com.dslplatform.json.CompiledJson;

@Request
@CompiledJson
public record OrderRequest(
    String orderId,
    double amount
) {}
```

### Step 2: Create Response DTO

```java
import com.reactor.rust.annotations.Response;
import com.dslplatform.json.CompiledJson;

@Response
@CompiledJson
public record OrderResponse(
    int status,
    String message
) {}
```

### Step 3: Create Handler

#### New Style (Recommended) - Annotation-Based Parameters

```java
import com.reactor.rust.annotations.*;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.http.HttpStatus;
import com.reactor.rust.http.MediaType;

@RequestMapping("/order")
public class OrderHandler {

    @PostMapping(value = "/create", requestType = OrderRequest.class, responseType = OrderResponse.class)
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<OrderResponse> create(
            @RequestBody OrderRequest request,
            @HeaderParam("X-Request-ID") String requestId) {

        // Business logic
        System.out.println("Order: " + request.orderId());
        System.out.println("Request ID: " + requestId);

        // Return ResponseEntity
        OrderResponse response = new OrderResponse(1, "Success");
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/{id}", responseType = OrderResponse.class)
    public ResponseEntity<OrderResponse> getById(@PathVariable("id") String id) {
        OrderResponse response = new OrderResponse(1, "Found: " + id);
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/search", responseType = OrderResponse.class)
    public ResponseEntity<OrderResponse> search(@RequestParam("status") String status) {
        OrderResponse response = new OrderResponse(1, "Status: " + status);
        return ResponseEntity.ok(response);
    }
}
```

#### Old Style - ByteBuffer Signature (Backward Compatible)

```java
import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.json.DslJsonService;
import java.nio.ByteBuffer;

public class OrderHandler {

    @RustRoute(
        method = "POST",
        path = "/order/create",
        requestType = OrderRequest.class,
        responseType = OrderResponse.class
    )
    public int create(ByteBuffer out, int offset, byte[] body) {
        // Parse JSON to object
        OrderRequest request = DslJsonService.parse(body, OrderRequest.class);

        // Business logic
        System.out.println("Order: " + request.orderId());

        // Create response
        OrderResponse response = new OrderResponse(1, "Success");

        // Serialize to buffer
        return DslJsonService.writeToBuffer(response, out, offset);
    }
}
```

### Step 4: Main Class

```java
import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;

public class Application {
    public static void main(String[] args) throws InterruptedException {
        // Register handlers
        HandlerRegistry registry = HandlerRegistry.getInstance();
        registry.registerBean(new OrderHandler());

        // Scan routes
        RouteScanner.scanAndRegister();

        // Start server
        NativeBridge.startHttpServer(8080);
        System.out.println("Server running: http://localhost:8080");

        // Keep JVM alive
        Thread.sleep(Long.MAX_VALUE);
    }
}
```

### Step 5: Run

```bash
mvn clean package -DskipTests
java -cp target/rust-java-rest-2.0.0.jar:target/lib/* Application
```

### Step 6: Test

```bash
curl -X POST http://localhost:8080/order/create \
  -H "Content-Type: application/json" \
  -H "X-Request-ID: REQ-001" \
  -d '{"orderId":"ORD-001", "amount":150.50}'
```

---

## HTTP Method Annotations

The framework supports Spring Boot-like HTTP method annotations:

### @GetMapping

```java
@GetMapping(value = "/product/{id}", responseType = ProductResponse.class)
public ResponseEntity<ProductResponse> getById(@PathVariable("id") String id) {
    return ResponseEntity.ok(productService.find(id));
}
```

### @PostMapping

```java
@PostMapping(value = "/product/add", requestType = ProductRequest.class, responseType = ProductResponse.class)
@ResponseStatus(HttpStatus.CREATED)
public ResponseEntity<ProductResponse> add(@RequestBody ProductRequest request) {
    return ResponseEntity.created(productService.save(request));
}
```

### @PutMapping

```java
@PutMapping(value = "/product/update", requestType = ProductRequest.class, responseType = ProductResponse.class)
public ResponseEntity<ProductResponse> update(@RequestBody ProductRequest request) {
    return ResponseEntity.ok(productService.update(request));
}
```

### @PatchMapping

```java
@PatchMapping(value = "/product/price", requestType = PriceUpdateRequest.class, responseType = ProductResponse.class)
public ResponseEntity<ProductResponse> updatePrice(@RequestBody PriceUpdateRequest request) {
    return ResponseEntity.ok(productService.updatePrice(request));
}
```

### @DeleteMapping

```java
@DeleteMapping(value = "/product/{id}", responseType = ProductResponse.class)
public ResponseEntity<ProductResponse> delete(@PathVariable("id") String id) {
    productService.delete(id);
    return ResponseEntity.ok(new ProductResponse(1, "Deleted"));
}
```

### @RequestMapping (Class-Level)

```java
@RequestMapping("/api/v1")
public class ApiHandler {

    @GetMapping(value = "/products", responseType = ProductListResponse.class)
    public ResponseEntity<ProductListResponse> getAllProducts() {
        // GET /api/v1/products
        return ResponseEntity.ok(productService.getAll());
    }
}
```

---

## Parameter Annotations

### @PathVariable - Path Parameter

```java
@GetMapping(value = "/order/{id}", responseType = OrderResponse.class)
public ResponseEntity<OrderResponse> getById(@PathVariable("id") String orderId) {
    return ResponseEntity.ok(orderService.find(orderId));
}

@GetMapping(value = "/order/{id}/item/{itemId}", responseType = ItemResponse.class)
public ResponseEntity<ItemResponse> getItem(
        @PathVariable("id") String orderId,
        @PathVariable("itemId") String itemId) {
    // GET /order/ORD-001/item/ITEM-123
    return ResponseEntity.ok(orderService.findItem(orderId, itemId));
}
```

### @RequestParam - Query Parameter

```java
@GetMapping(value = "/order/search", responseType = OrderListResponse.class)
public ResponseEntity<OrderListResponse> search(
        @RequestParam("status") String status,
        @RequestParam(value = "page", defaultValue = "1") int page) {
    // GET /order/search?status=pending&page=2
    return ResponseEntity.ok(orderService.search(status, page));
}

@GetMapping(value = "/product/list", responseType = ProductListResponse.class)
public ResponseEntity<ProductListResponse> list(
        @RequestParam(value = "sort", required = false) String sort,
        @RequestParam(value = "limit", defaultValue = "10") int limit) {
    // GET /product/list?sort=price&limit=20
    return ResponseEntity.ok(productService.list(sort, limit));
}
```

### @HeaderParam - Header Value

```java
@PostMapping(value = "/order/create", requestType = OrderRequest.class, responseType = OrderResponse.class)
public ResponseEntity<OrderResponse> create(
        @RequestBody OrderRequest request,
        @HeaderParam("X-Request-ID") String requestId,
        @HeaderParam("Authorization") String token) {
    // Get X-Request-ID and Authorization headers
    return ResponseEntity.ok(orderService.create(request, requestId, token));
}
```

### @RequestBody - Request Body

```java
@PostMapping(value = "/product/add", requestType = ProductRequest.class, responseType = ProductResponse.class)
public ResponseEntity<ProductResponse> add(@RequestBody ProductRequest request) {
    // Body is automatically deserialized to ProductRequest
    return ResponseEntity.ok(productService.save(request));
}
```

### @CookieValue - Cookie Value

```java
@GetMapping(value = "/user/info", responseType = UserResponse.class)
public ResponseEntity<UserResponse> getInfo(@CookieValue("sessionId") String sessionId) {
    // Get sessionId from cookie
    return ResponseEntity.ok(userService.findBySession(sessionId));
}
```

---

## ResponseEntity Usage

ResponseEntity provides a type-safe wrapper for HTTP responses:

```java
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.http.HttpStatus;

// 200 OK
@GetMapping(value = "/product/{id}", responseType = ProductResponse.class)
public ResponseEntity<ProductResponse> getById(@PathVariable("id") String id) {
    return ResponseEntity.ok(productService.find(id));
}

// 201 Created
@PostMapping(value = "/product/add", requestType = ProductRequest.class, responseType = ProductResponse.class)
public ResponseEntity<ProductResponse> add(@RequestBody ProductRequest request) {
    return ResponseEntity.created(productService.save(request));
}

// 404 Not Found
@GetMapping(value = "/product/{id}", responseType = ProductResponse.class)
public ResponseEntity<ProductResponse> getById(@PathVariable("id") String id) {
    ProductResponse product = productService.find(id);
    if (product == null) {
        return ResponseEntity.notFound();
    }
    return ResponseEntity.ok(product);
}

// 400 Bad Request
@PostMapping(value = "/product/add", requestType = ProductRequest.class, responseType = ProductResponse.class)
public ResponseEntity<ProductResponse> add(@RequestBody ProductRequest request) {
    if (request.name() == null || request.name().isEmpty()) {
        return ResponseEntity.badRequest();
    }
    return ResponseEntity.ok(productService.save(request));
}

// Custom Status
@DeleteMapping(value = "/product/{id}", responseType = Void.class)
public ResponseEntity<Void> delete(@PathVariable("id") String id) {
    productService.delete(id);
    return ResponseEntity.status(HttpStatus.NO_CONTENT);
}
```

---

## @ResponseStatus Annotation

Used to specify HTTP status code for handler methods:

```java
import com.reactor.rust.annotations.ResponseStatus;
import com.reactor.rust.http.HttpStatus;

@PostMapping(value = "/order/create", requestType = OrderRequest.class, responseType = OrderResponse.class)
@ResponseStatus(201)  // or HttpStatus.CREATED = 201
public ResponseEntity<OrderResponse> create(@RequestBody OrderRequest request) {
    return ResponseEntity.ok(orderService.create(request));
}

@DeleteMapping(value = "/order/{id}", responseType = Void.class)
@ResponseStatus(204)  // or HttpStatus.NO_CONTENT = 204
public ResponseEntity<Void> delete(@PathVariable("id") String id) {
    orderService.delete(id);
    return null;
}
```

---

## HttpStatus Enum

Enum for common HTTP status codes:

```java
import com.reactor.rust.http.HttpStatus;

// Usage
HttpStatus.OK           // 200
HttpStatus.CREATED      // 201
HttpStatus.NO_CONTENT   // 204
HttpStatus.BAD_REQUEST  // 400
HttpStatus.UNAUTHORIZED // 401
HttpStatus.FORBIDDEN    // 403
HttpStatus.NOT_FOUND    // 404
HttpStatus.INTERNAL_SERVER_ERROR // 500

// With ResponseEntity
return ResponseEntity.status(HttpStatus.CREATED);
```

---

## MediaType Constants

Constants for content types:

```java
import com.reactor.rust.http.MediaType;

MediaType.APPLICATION_JSON   // "application/json"
MediaType.TEXT_PLAIN         // "text/plain"
MediaType.TEXT_HTML          // "text/html"
MediaType.APPLICATION_XML    // "application/xml"
MediaType.TEXT_CSV           // "text/csv"
MediaType.APPLICATION_OCTET_STREAM // "application/octet-stream"

// Usage
@PostMapping(value = "/order/create", requestType = OrderRequest.class, responseType = OrderResponse.class)
public ResponseEntity<OrderResponse> create(
        @RequestBody OrderRequest request,
        @HeaderParam("Content-Type") String contentType) {

    if (contentType == null || !contentType.contains(MediaType.APPLICATION_JSON)) {
        return ResponseEntity.badRequest();
    }

    return ResponseEntity.ok(orderService.create(request));
}
```

---

## Old Style (V4 Signature) - Backward Compatible

If you don't want annotation-based parameters, you can continue using the old V4 signature:

### Body Only

```java
@RustRoute(
    method = "POST",
    path = "/order/create",
    requestType = OrderRequest.class,
    responseType = OrderResponse.class
)
public int create(ByteBuffer out, int offset, byte[] body) {
    OrderRequest request = DslJsonService.parse(body, OrderRequest.class);
    OrderResponse response = new OrderResponse(1, "Success");
    return DslJsonService.writeToBuffer(response, out, offset);
}
```

### Path Parameter

```java
@RustRoute(
    method = "GET",
    path = "/order/{id}",
    requestType = Void.class,
    responseType = OrderResponse.class
)
public int getById(ByteBuffer out, int offset, byte[] body, String pathParams) {
    // pathParams = "id=ORD-001"
    String id = getParam(pathParams, "id");
    OrderResponse response = new OrderResponse(1, "Found: " + id);
    return DslJsonService.writeToBuffer(response, out, offset);
}

// Helper method
private String getParam(String params, String key) {
    if (params == null) return null;
    for (String pair : params.split("&")) {
        String[] kv = pair.split("=", 2);
        if (kv[0].equals(key)) return kv[1];
    }
    return null;
}
```

### Path + Query Parameters

```java
@RustRoute(
    method = "GET",
    path = "/order/search",
    requestType = Void.class,
    responseType = OrderResponse.class
)
public int search(ByteBuffer out, int offset, byte[] body,
                 String pathParams, String queryString) {
    // queryString = "status=pending&page=1"
    String status = getParam(queryString, "status");
    OrderResponse response = new OrderResponse(1, "Status: " + status);
    return DslJsonService.writeToBuffer(response, out, offset);
}
```

### Full Signature (Path + Query + Headers)

```java
@RustRoute(
    method = "POST",
    path = "/order/create",
    requestType = OrderRequest.class,
    responseType = OrderResponse.class
)
public int create(ByteBuffer out, int offset, byte[] body,
                 String pathParams, String queryString, String headers) {
    // headers = "Content-Type=application/json&X-Request-ID=REQ-001"
    String requestId = getParam(headers, "X-Request-ID");

    OrderRequest request = DslJsonService.parse(body, OrderRequest.class);
    OrderResponse response = orderService.create(request, requestId);
    return DslJsonService.writeToBuffer(response, out, offset);
}
```

---

## Handler Method Signatures

### New Style (Annotation-Based)

| Signature | Description |
|-----------|-------------|
| `ResponseEntity<T> method(@PathVariable String id)` | Path parameter |
| `ResponseEntity<T> method(@RequestParam String q)` | Query parameter |
| `ResponseEntity<T> method(@RequestBody Request req)` | Request body |
| `ResponseEntity<T> method(@HeaderParam String h)` | Header |
| `T method(...)` | Automatically serialized |

### Old Style (V4 - ByteBuffer)

| Need | Signature |
|------|-----------|
| Body only | `int method(ByteBuffer out, int offset, byte[] body)` |
| Path parameter | `int method(ByteBuffer out, int offset, byte[] body, String pathParams)` |
| Path + Query | `int method(ByteBuffer out, int offset, byte[] body, String pathParams, String queryString)` |
| Full signature | `int method(ByteBuffer out, int offset, byte[] body, String pathParams, String queryString, String headers)` |

---

## Supported Platforms

| Platform | Native Library | Status |
|----------|----------------|--------|
| Linux x64 | `librust_hyper.so` | Supported |
| Windows x64 | `rust_hyper.dll` | Supported |
| macOS x64 | `librust_hyper.dylib` | Coming Soon |
| macOS ARM64 | `librust_hyper.dylib` | Coming Soon |

---

## Native Library Usage

The framework requires a native library for the Rust Hyper HTTP server. This library is **automatically embedded in the JAR** and loaded at runtime.

### Automatic Loading (Default)

```java
// Native library is loaded automatically - no action required
NativeBridge.startHttpServer(8080);
```

### Manual Loading

```bash
# Specify custom library path
java -Drust.lib.path=/path/to/rust_hyper.dll -jar myapp.jar

# Or use java.library.path
java -Djava.library.path=/path/to/native/dir -jar myapp.jar
```

### Native Library Files

| Platform | File | Location (in JAR) |
|----------|------|-------------------|
| Windows x64 | `rust_hyper.dll` | `native/windows-x64/` |
| Linux x64 | `librust_hyper.so` | `native/linux-x64/` |

---

## Docker

The framework provides ultra-minimal Docker images optimized for production.

### Image Sizes (v3.0.0)

| Image | Size | Base | Runtime Memory | Description |
|-------|------|------|----------------|-------------|
| `rust-java-rest:ultra` | **149MB** | Debian slim | **28 MB** | Ultra-low memory (v3.0.0) |
| `ghcr.io/esasmer-dou/rust-java-rest:3.1.0-rc5` | Debian slim | low-rss profile | RC / performance preview |
| `rust-java-rest:minimal` | **74MB** | Distroless | ~35 MB | Minimal (v2.0.0) |
| `rust-java-rest:optimized` | **136MB** | Debian slim | ~35 MB | With curl |

### Pull from GitHub Container Registry

```bash
# Ultra-low memory image (v3.0.0) - RECOMMENDED
docker pull ghcr.io/esasmer-dou/rust-java-rest:3.1.0-rc5
docker run -p 8080:8080 --memory=128m ghcr.io/esasmer-dou/rust-java-rest:3.1.0-rc5

# Legacy minimal image (v2.0.0)
docker pull ghcr.io/esasmer-dou/rust-java-rest:2.0.0
docker run -p 8080:8080 --memory=40m ghcr.io/esasmer-dou/rust-java-rest:2.0.0
```

### Build Options

**Option 1: Ultra-Low Memory (v3.0.0) - 149MB image, 28MB runtime**
```bash
docker build -t rust-java-rest:ultra -f src/main/resources/container/Dockerfile.ultra .
docker run -d -p 8080:8080 --memory=50m --name rust-java rust-java-rest:ultra
```

**Option 2: Minimal (Distroless + jlink) - 74MB**
```bash
docker build -t rust-java-rest:minimal -f src/main/resources/container/Dockerfile.minimal .
```

**Option 3: Standard (Debian + jlink) - 136MB**
```bash
docker build -t rust-java-rest:optimized -f src/main/resources/container/Dockerfile.optimized .
```

### Run

```bash
# With 50MB memory limit (v3.0.0 - recommended)
docker run -d -p 8080:8080 --memory=50m --name rust-java-app rust-java-rest:ultra

# With 40MB memory limit (v2.0.0)
docker run -d -p 8080:8080 --memory=40m --name rust-java-app rust-java-rest:minimal
```

### Dockerfile Features

| Feature | Ultra (v3.0.0) | Minimal | Optimized |
|---------|----------------|---------|-----------|
| Base Image | Debian slim | Distroless | Debian slim |
| Image Size | 149MB | 74MB | 136MB |
| **Runtime Memory** | **28 MB** | ~35 MB | ~35 MB |
| jlink JRE | ~25MB | 35MB | 35MB |
| Health Check | curl | External | curl |
| Memory Limit | 50MB | 40MB | 40MB |
| Non-root User | Yes | Yes | Yes |
| Multi-stage Build | Yes (4 stages) | Yes | Yes |

### JVM Settings (Ultra-Minimal)

```bash
# v3.0.0 Ultra-low memory settings
-Xms4m                          # Minimum heap (4MB)
-Xmx24m                         # Maximum heap (24MB)
-XX:+UseSerialGC                # Lowest memory GC
-XX:MaxMetaspaceSize=20m        # Metaspace limit (reduced)
-XX:ReservedCodeCacheSize=8m    # Code cache limit
-XX:+TieredCompilation          # Fast startup
-XX:TieredStopAtLevel=1         # C1 compiler only
-XX:CICompilerCount=1           # Single compiler thread
-XX:+UseCompressedOops          # Memory optimization
-XX:+UseCompressedClassPointers # Memory optimization
-XX:+UseStringDeduplication     # String deduplication
-Xss256k                        # Thread stack size
```

### Docker Compose

```yaml
version: '3.8'
services:
  rust-java-rest:
    image: rust-java-rest:2.0.0
    ports:
      - "8080:8080"
    deploy:
      resources:
        limits:
          memory: 40M
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 10s
      timeout: 3s
      retries: 3
```

---

## Requirements

- Java 21+
- Maven 3.8+

---

## Project Structure

```
com.myapp/
├── Application.java           # Main class
├── dto/
│   ├── OrderRequest.java
│   └── OrderResponse.java
└── handler/
    └── OrderHandler.java
```

---

## Annotation Summary

### REST API Annotations

| Annotation | Description |
|------------|-------------|
| `@RequestMapping` | Class-level base path |
| `@GetMapping` | GET request handler |
| `@PostMapping` | POST request handler |
| `@PutMapping` | PUT request handler |
| `@PatchMapping` | PATCH request handler |
| `@DeleteMapping` | DELETE request handler |
| `@PathVariable` | Get path parameter |
| `@RequestParam` | Get query parameter |
| `@HeaderParam` | Get header value |
| `@RequestBody` | Deserialize request body |
| `@CookieValue` | Get cookie value |
| `@ResponseStatus` | Specify HTTP status code |
| `@RustRoute` | Legacy annotation (V4 signature) |
| `@Request` | Mark Request DTO |
| `@Response` | Mark Response DTO |

### DI Annotations

| Annotation | Description |
|------------|-------------|
| `@Component` | Mark general component |
| `@Service` | Business logic service |
| `@Repository` | Data access layer |
| `@Configuration` | Configuration class |
| `@Bean` | Bean-producing method |
| `@Autowired` | Dependency injection |
| `@PostConstruct` | Initialization callback |
| `@PreDestroy` | Cleanup callback |
| `@Primary` | Primary bean |
| `@Qualifier` | Bean selection |

### WebSocket Annotations (v3.0.0) 🆕

| Annotation | Description |
|------------|-------------|
| `@WebSocket` | Mark WebSocket handler class |
| `@OnOpen` | Connection opened callback |
| `@OnMessage` | Message received handler |
| `@OnClose` | Connection closed callback |
| `@OnError` | Error handler |

### Static Files Annotation (v3.0.0) 🆕

| Annotation | Description |
|------------|-------------|
| `@StaticFiles` | Configure static file serving |

---

## Dependency Injection (DI)

The framework provides zero-overhead Dependency Injection similar to Spring Boot. All dependencies are resolved at startup, NO runtime reflection.

### DI Annotations

| Annotation | Description |
|------------|-------------|
| `@Component` | General component |
| `@Service` | Business logic service |
| `@Repository` | Data access layer |
| `@Configuration` | Configuration class |
| `@Bean` | Bean-producing method |
| `@Autowired` | Dependency injection |
| `@PostConstruct` | Initialization callback |
| `@PreDestroy` | Cleanup callback |
| `@Primary` | Primary bean |
| `@Qualifier` | Bean selection |

### Define Service

```java
import com.reactor.rust.di.annotation.Service;
import com.reactor.rust.di.annotation.Autowired;
import com.reactor.rust.di.annotation.PostConstruct;

@Service
public class OrderService {

    @Autowired(required = false)
    private NotificationService notificationService;

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        System.out.println("[OrderService] Initialized");
    }

    public Order createOrder(OrderRequest request) {
        Order order = new Order(generateId(), request);
        orders.put(order.id(), order);

        if (notificationService != null) {
            notificationService.notify("Order created: " + order.id());
        }

        return order;
    }
}
```

### @Configuration and @Bean

```java
import com.reactor.rust.di.annotation.Configuration;
import com.reactor.rust.di.annotation.Bean;

@Configuration
public class AppConfiguration {

    @Bean
    public ExecutorService taskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean("appMetadata")
    public AppMetadata appMetadata() {
        return new AppMetadata("my-app", "1.0.0");
    }

    public record AppMetadata(String name, String version) {}
}
```

### Use Service in Handler

```java
import com.reactor.rust.di.annotation.Autowired;

@RequestMapping("/order")
public class OrderHandler {

    @Autowired
    private OrderService orderService;  // Automatically injected

    @PostMapping(value = "/create", requestType = OrderRequest.class, responseType = OrderResponse.class)
    public ResponseEntity<OrderResponse> create(@RequestBody OrderRequest request) {
        // orderService is automatically injected
        Order order = orderService.createOrder(request);
        return ResponseEntity.ok(new OrderResponse(1, "OK"));
    }
}
```

### Using DI Container

```java
import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.RouteScanner;
import com.reactor.rust.bridge.NativeBridge;

public class Application {
    public static void main(String[] args) throws InterruptedException {
        // 1. Initialize DI Container
        BeanContainer container = BeanContainer.getInstance();

        // 2. Component scanning
        container.scan("com.myapp");

        // 3. Start container (all dependencies resolved)
        container.start();

        // 4. Scan routes
        RouteScanner.scanAndRegister();

        // 5. Register handlers
        HandlerRegistry registry = HandlerRegistry.getInstance();
        registry.registerBean(new OrderHandler());

        // 6. Start server
        NativeBridge.startHttpServer(8080);

        System.out.println("Server running: http://localhost:8080");
        Thread.sleep(Long.MAX_VALUE);
    }
}
```

### Lifecycle Callbacks

```java
import com.reactor.rust.di.annotation.Service;
import com.reactor.rust.di.annotation.PostConstruct;
import com.reactor.rust.di.annotation.PreDestroy;

@Service
public class NotificationService {

    private ExecutorService executor;

    @PostConstruct
    public void init() {
        // Initialization
        executor = Executors.newSingleThreadExecutor();
        System.out.println("[NotificationService] Ready");
    }

    @PreDestroy
    public void cleanup() {
        // Cleanup
        executor.shutdown();
        System.out.println("[NotificationService] Shutdown");
    }

    public void notify(String message) {
        executor.submit(() -> sendNotification(message));
    }
}
```

### @Primary and @Qualifier

When multiple beans implement the same interface, use `@Primary` to mark the default and `@Qualifier` to select a specific implementation.

#### Define Interface with Multiple Implementations

```java
// Payment interface
public interface PaymentService {
    String processPayment(String orderId, double amount);
    String getPaymentMethod();
}

// Primary implementation (default)
@Service
@Primary  // <-- Makes this the default when multiple candidates exist
public class CreditCardPaymentService implements PaymentService {
    @Override
    public String processPayment(String orderId, double amount) {
        return "CC-" + System.currentTimeMillis();
    }

    @Override
    public String getPaymentMethod() {
        return "CREDIT_CARD";
    }
}

// Alternative implementation
@Service
public class PayPalPaymentService implements PaymentService {
    @Override
    public String processPayment(String orderId, double amount) {
        return "PP-" + System.currentTimeMillis();
    }

    @Override
    public String getPaymentMethod() {
        return "PAYPAL";
    }
}

// Another alternative
@Service
public class BankTransferPaymentService implements PaymentService {
    @Override
    public String processPayment(String orderId, double amount) {
        return "BT-" + System.currentTimeMillis();
    }

    @Override
    public String getPaymentMethod() {
        return "BANK_TRANSFER";
    }
}
```

#### Use in Handler

```java
@Component
public class PaymentHandler {

    // @Primary injection - gets CreditCardPaymentService by default
    @Autowired
    private PaymentService paymentService;

    // @Qualifier injection - gets specific implementation
    @Autowired
    @Qualifier("payPalPaymentService")
    private PaymentService payPalService;

    @Autowired
    @Qualifier("bankTransferPaymentService")
    private PaymentService bankService;

    @PostMapping(value = "/payment/process", requestType = PaymentRequest.class, responseType = PaymentResponse.class)
    public ResponseEntity<PaymentResponse> processPayment(@RequestBody PaymentRequest request) {
        // Uses @Primary (CreditCardPaymentService)
        String txId = paymentService.processPayment(request.orderId(), request.amount());
        return ResponseEntity.ok(new PaymentResponse(txId, paymentService.getPaymentMethod(), "SUCCESS"));
    }

    @PostMapping(value = "/payment/paypal", requestType = PaymentRequest.class, responseType = PaymentResponse.class)
    public ResponseEntity<PaymentResponse> processPayPal(@RequestBody PaymentRequest request) {
        // Uses @Qualifier("payPalPaymentService")
        String txId = payPalService.processPayment(request.orderId(), request.amount());
        return ResponseEntity.ok(new PaymentResponse(txId, payPalService.getPaymentMethod(), "SUCCESS"));
    }

    @GetMapping(value = "/payment/methods", responseType = PaymentMethodsResponse.class)
    public ResponseEntity<PaymentMethodsResponse> getPaymentMethods() {
        // Access all implementations
        return ResponseEntity.ok(new PaymentMethodsResponse(List.of(
            new PaymentMethodInfo("credit-card", paymentService.getPaymentMethod(), true),
            new PaymentMethodInfo("paypal", payPalService.getPaymentMethod(), false),
            new PaymentMethodInfo("bank-transfer", bankService.getPaymentMethod(), false)
        )));
    }
}
```

#### Bean Naming Convention

Bean names default to camelCase class name:
- `CreditCardPaymentService` -> `creditCardPaymentService`
- `PayPalPaymentService` -> `payPalPaymentService`
- `BankTransferPaymentService` -> `bankTransferPaymentService`

You can also specify a custom name with `@Service("customName")`.

### DI Performance Characteristics

| Metric | Value |
|--------|-------|
| Bean Lookup | O(1) ConcurrentHashMap |
| Lookup Time | ~0.4 microseconds |
| Memory Overhead | ~50-100 bytes/bean |
| Runtime Reflection | **NONE** |

### DI vs Spring Boot Comparison

| Feature | Rust-Java REST | Spring Boot |
|---------|----------------|-------------|
| Startup Time | ~100ms | ~2-5s |
| Memory Overhead | ~1-2 MB | ~30-50 MB |
| Bean Lookup | O(1) direct | O(1) + proxy |
| Runtime Reflection | No | Yes |
| AOP Support | No | Yes |
| Proxy Overhead | No | Yes |

---

## WebSocket Support (v3.0.0) 🆕

Full WebSocket support with annotation-based handlers.

### WebSocket Handler

```java
import com.reactor.rust.websocket.annotation.WebSocket;
import com.reactor.rust.websocket.annotation.OnOpen;
import com.reactor.rust.websocket.annotation.OnMessage;
import com.reactor.rust.websocket.annotation.OnClose;
import com.reactor.rust.websocket.annotation.OnError;
import com.reactor.rust.websocket.WebSocketSession;

@Component
@WebSocket("/ws/echo")
public class EchoWebSocketHandler {

    @OnOpen
    public void onOpen(WebSocketSession session) {
        System.out.println("Session opened: " + session.getId());
        session.sendText("{\"type\":\"connected\",\"sessionId\":\"" + session.getId() + "\"}");
    }

    @OnMessage
    public void onMessage(WebSocketSession session, String message) {
        session.sendText("{\"type\":\"echo\",\"message\":\"" + escapeJson(message) + "\"}");
    }

    @OnClose
    public void onClose(WebSocketSession session) {
        System.out.println("Session closed: " + session.getId());
    }

    @OnError
    public void onError(WebSocketSession session, String error) {
        System.err.println("Error: " + error);
    }
}
```

### Chat Room with Path Parameters

```java
@Component
@WebSocket("/ws/chat/{roomId}")
public class ChatWebSocketHandler {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(WebSocketSession session) {
        String roomId = session.getPathParams().get("roomId");
        rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
        broadcast(roomId, "{\"type\":\"join\",\"sessionId\":\"" + session.getId() + "\"}");
    }

    @OnMessage
    public void onMessage(WebSocketSession session, String message) {
        String roomId = session.getPathParams().get("roomId");
        broadcast(roomId, "{\"type\":\"message\",\"text\":\"" + escapeJson(message) + "\"}");
    }

    private void broadcast(String roomId, String message) {
        for (WebSocketSession s : rooms.get(roomId)) {
            s.sendText(message);
        }
    }
}
```

### WebSocket Broadcasting API

```java
import com.reactor.rust.websocket.WebSocketBroadcaster;

WebSocketBroadcaster broadcaster = WebSocketBroadcaster.getInstance();

// Broadcast to all sessions
broadcaster.broadcast("{\"type\":\"notification\",\"text\":\"Hello all!\"}");

// Broadcast to specific room
broadcaster.broadcastToRoom("room1", "{\"type\":\"message\",\"text\":\"Hello room1!\"}");

// Broadcast excluding sender
broadcaster.broadcast(message, excludeSessionId);

// Broadcast binary data
broadcaster.broadcastBinary(data);
broadcaster.broadcastBinaryToRoom("room1", data);

// Room management
broadcaster.joinRoom(sessionId, "room1");
broadcaster.leaveRoom(sessionId, "room1");
broadcaster.getSessionsInRoom("room1");
```

### JavaScript Client

```javascript
// Echo
const ws = new WebSocket('ws://localhost:8080/ws/echo');
ws.onopen = () => ws.send('Hello!');
ws.onmessage = (e) => console.log(e.data);

// Chat room
const chat = new WebSocket('ws://localhost:8080/ws/chat/room1');
chat.onopen = () => chat.send('Hi everyone!');
chat.onmessage = (e) => console.log(e.data);
```

---

## Async Handlers (CompletableFuture) (v3.0.0) 🆕

Support for non-blocking async handlers with virtual threads (Java 21+).

### Async Service

```java
import java.util.concurrent.CompletableFuture;

@Service
public class OrderService {

    @Autowired
    private PaymentService paymentService;

    public CompletableFuture<Order> createOrderAsync(OrderRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            // This runs on a virtual thread (Java 21+)
            Order order = new Order(generateId(), request);

            // Process payment (blocking call)
            paymentService.process(order);

            return order;
        });
    }
}
```

### Async Handler

```java
@RequestMapping("/order")
public class OrderHandler {

    @Autowired
    private OrderService orderService;

    @PostMapping(value = "/create", requestType = OrderRequest.class, responseType = OrderResponse.class)
    public CompletableFuture<ResponseEntity<OrderResponse>> createAsync(
            @RequestBody OrderRequest request) {

        return orderService.createOrderAsync(request)
            .thenApply(order -> ResponseEntity.ok(
                new OrderResponse(order.getId(), "Created", order.getAmount())
            ))
            .exceptionally(ex -> ResponseEntity.status(500).body(
                new OrderResponse(-1, "Error: " + ex.getMessage(), 0)
            ));
    }

    // Multiple async calls combined
    @GetMapping(value = "/{id}/full", responseType = FullOrderResponse.class)
    public CompletableFuture<ResponseEntity<FullOrderResponse>> getFullOrder(
            @PathVariable("id") String orderId) {

        CompletableFuture<Order> orderFuture = orderService.getOrderAsync(orderId);
        CompletableFuture<List<Payment>> paymentsFuture = paymentService.getPaymentsAsync(orderId);

        return CompletableFuture.allOf(orderFuture, paymentsFuture)
            .thenApply(v -> ResponseEntity.ok(new FullOrderResponse(
                orderFuture.join(),
                paymentsFuture.join()
            )));
    }
}
```

### AsyncHandlerExecutor API

```java
import com.reactor.rust.async.AsyncHandlerExecutor;

AsyncHandlerExecutor executor = AsyncHandlerExecutor.getInstance();

// Submit async task
CompletableFuture<Order> future = executor.submit(() -> {
    return db.query("SELECT * FROM orders WHERE id = ?", id);
});

// Submit with timeout (5 seconds)
CompletableFuture<Order> future = executor.submit(() -> {
    return externalApi.call();
}, 5000);
```

---

## Static File Serving (v3.0.0) 🆕

Production-ready static file serving with caching and MIME type detection.

### Basic Configuration

```java
import com.reactor.rust.annotations.StaticFiles;

@Component
@StaticFiles(path = "/static", location = "static")
public class StaticFileConfig {}
```

This serves files from `classpath:/static/` at `/static/*`:

```
GET /static/css/style.css    → classpath:/static/css/style.css
GET /static/js/app.js        → classpath:/static/js/app.js
GET /static/                 → classpath:/static/index.html (default)
GET /static/images/logo.png  → classpath:/static/images/logo.png
```

### Full Configuration Options

```java
@Component
@StaticFiles(
    path = "/public",
    location = "public",
    directoryListing = false,    // Enable directory listing (default: false)
    cacheMaxAge = 3600,          // Cache max-age in seconds (default: 3600)
    indexFile = "index.html"     // Index file for directories (default: "index.html")
)
public class PublicStaticFiles {}
```

### Multiple Static Locations

```java
@Component
@StaticFiles(path = "/assets", location = "assets")
public class AssetsConfig {}

@Component
@StaticFiles(path = "/uploads", location = "uploads", cacheMaxAge = 0)
public class UploadsConfig {}

@Component
@StaticFiles(path = "/", location = "public", indexFile = "index.html")
public class RootStaticFiles {}
```

### Supported MIME Types

| Extension | MIME Type |
|-----------|-----------|
| .html, .htm | text/html |
| .css | text/css |
| .js | application/javascript |
| .json | application/json |
| .png | image/png |
| .jpg, .jpeg | image/jpeg |
| .gif | image/gif |
| .svg | image/svg+xml |
| .ico | image/x-icon |
| .webp | image/webp |
| .woff, .woff2 | font/woff, font/woff2 |
| .ttf | font/ttf |
| .mp4 | video/mp4 |
| .webm | video/webm |
| .mp3 | audio/mpeg |
| .pdf | application/pdf |
| .xml | application/xml |
| .txt | text/plain |

### File Caching

Small files (< 1MB) are automatically cached in memory.

```java
// Clear cache programmatically (development mode)
StaticFileRegistry.getInstance().clearCache();
```

---

## Performance Benchmarks

Comprehensive load testing comparing **Rust-Java REST Framework** vs **Spring Boot**.

### Test Environment

| Configuration | Value |
|---------------|-------|
| **Platform** | Windows 10 x64 |
| **JDK** | OpenJDK 21 |
| **Memory Limit** | 40 MB (framework), 200 MB (Spring Boot) |
| **Endpoint** | `/api/v1/candidates` (JSON response with 19 nested objects) |
| **Warmup** | 500 requests |

### RPS Comparison (Requests Per Second)

Higher is better.

| Concurrency | Rust-Java REST | Spring Boot | Improvement |
|-------------|----------------|-------------|-------------|
| 10 | **2,937 RPS** | ~1,150 RPS | **155% faster** |
| 50 | **2,299 RPS** | ~980 RPS | **135% faster** |
| 100 | **3,626 RPS** | ~850 RPS | **326% faster** |
| 1000 | **2,738 RPS** | ~400 RPS | **585% faster** |

### Latency Comparison (Milliseconds)

Lower is better.

| Concurrency | Rust-Java (avg) | Rust-Java (P99) | Spring Boot (avg) | Spring Boot (P99) |
|-------------|-----------------|-----------------|-------------------|-------------------|
| 10 | **3.34 ms** | 16.61 ms | ~15 ms | ~50 ms |
| 50 | **21.49 ms** | 342.63 ms | ~75 ms | ~200 ms |
| 100 | **26.81 ms** | 223.46 ms | ~120 ms | ~350 ms |
| 1000 | **285.51 ms** | 1650.88 ms | ~800 ms | ~2500 ms |

### Memory Footprint

Lower is better.

| Metric | Rust-Java REST | Spring Boot | Improvement |
|--------|----------------|-------------|-------------|
| **Docker Image** | **74 MB** | ~300 MB | **75% smaller** |
| **Heap at Startup** | **~4 MB** | ~50 MB | **92% less** |
| **Heap under Load** | **~27 MB** | ~94 MB | **71% less** |
| **Max Memory Config** | **40 MB** | 200 MB | **80% less** |

### GC Statistics

Lower is better.

| Metric | Rust-Java REST | Spring Boot |
|--------|----------------|-------------|
| **GC Algorithm** | Serial GC | G1GC |
| **GC Pauses/sec** | ~0.1 | ~2-5 |
| **Pause Duration** | <1ms | 10-50ms |
| **Object Allocation** | Minimal (ThreadLocal reuse) | High (wrappers, proxies) |

### Success Rate

| Concurrency | Rust-Java REST | Spring Boot |
|-------------|----------------|-------------|
| 10 | **100%** | 100% |
| 50 | **100%** | ~99.8% |
| 100 | **100%** | ~99.5% |
| 1000 | **100%** | ~95% |

### Performance Under Constraints

Running with strict 40MB memory limit:

```bash
# Rust-Java REST - Works perfectly
docker run --memory=40m -p 8080:8080 ghcr.io/esasmer-dou/rust-java-rest:2.0.0

# Spring Boot - OOM Killed
docker run --memory=40m -p 8081:8080 spring-boot-app
# Error: java.lang.OutOfMemoryError: Java heap space
```

### Benchmark Methodology

```java
// Using Java 21 HttpClient with concurrent requests
ExecutorService executor = Executors.newFixedThreadPool(concurrency);
for (int i = 0; i < requests; i++) {
    executor.submit(() -> {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
    });
}
```

### Key Performance Factors

1. **Rust Hyper HTTP Server**: Zero-copy network I/O, async Tokio runtime
2. **DSL-JSON Serialization**: Compile-time code generation, no runtime reflection
3. **ThreadLocal Buffer Pools**: Eliminates per-request allocations
4. **Minimal GC Pressure**: Object reuse patterns, primitive types where possible
5. **Direct ByteBuffer**: Zero-copy JNI boundary crossing

---

## License

MIT

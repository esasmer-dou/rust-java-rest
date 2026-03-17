# Rust-Java REST Framework

[![Version](https://img.shields.io/badge/version-3.0.0-blue.svg)](https://github.com/esasmer-dou/rust-java-rest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Memory](https://img.shields.io/badge/memory-28MB-green.svg)]()
[![Latency](https://img.shields.io/badge/latency-5ms-brightgreen.svg)]()

Ultra-fast REST API framework combining Rust Hyper HTTP server with Java handlers.

## v3.0.0 - Ultra Low Latency & Memory Optimization

This release focuses on **extreme performance optimization**:
- **Sub-10ms latency** (5-8ms average)
- **Sub-30MB memory** (28.99 MB container memory)
- **Zero-allocation** per-request processing

### Performance (v3.0.0 vs Spring Boot)

| Metric | Rust-Java REST | Spring Boot | Improvement |
|--------|----------------|-------------|-------------|
| Memory | **28 MB** | ~94 MB | 70% less |
| Latency (avg) | **5-8 ms** | ~144 ms | 95% faster |
| RPS (100 conn) | **3,626** | ~850 | 4x faster |
| Docker Image | **149 MB** | ~300 MB | 50% smaller |
| Per-request alloc | **0 bytes** | ~2 KB | 100% reduction |

### New Optimizations (Phase 5)

| Optimization | Impact |
|--------------|--------|
| **MethodMetadata Cache** | Annotation lookup: 200ns → 5ns |
| **FastMapV2 (Robin-Hood)** | O(n) → O(1) lookup |
| **Zero-Copy Headers (Rust)** | No String allocation |
| **ThreadLocal Buffer Pools** | Zero allocation parsing |
| **Pre-allocated Error Bytes** | Fast error responses |

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
    <version>3.0.1</version>
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
| `ghcr.io/esasmer-dou/rust-java-rest:3.0.0` | **149MB** | Debian slim | **28 MB** | GitHub Registry |
| `rust-java-rest:minimal` | **74MB** | Distroless | ~35 MB | Minimal (v2.0.0) |
| `rust-java-rest:optimized` | **136MB** | Debian slim | ~35 MB | With curl |

### Pull from GitHub Container Registry

```bash
# Ultra-low memory image (v3.0.0) - RECOMMENDED
docker pull ghcr.io/esasmer-dou/rust-java-rest:3.0.0
docker run -p 8080:8080 --memory=50m ghcr.io/esasmer-dou/rust-java-rest:3.0.0

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

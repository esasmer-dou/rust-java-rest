# Rust-Java REST Framework v3.0.1

> **Note:** v3.0.1 fixes native library updates. See v3.0.0 below for full feature list.

---

# Rust-Java REST Framework v3.0.0

## Major Release: Ultra Low Latency & Complete Feature Set

This release combines **extreme performance optimization** with a **complete REST API feature set** including WebSocket support, async handlers, and dependency injection.

---

## Maven Dependency

```xml
<dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-rest</artifactId>
    <version>3.0.0</version>
</dependency>
```

### GitHub Packages Repository

Add to your `pom.xml` or `settings.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/esasmer-dou/rust-java-rest</url>
    </repository>
</repositories>
```

### Gradle

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/esasmer-dou/rust-java-rest")
    }
}

dependencies {
    implementation 'com.reactor:rust-java-rest:3.0.0'
}
```

---

## Performance Improvements

### Latency Reduction (Phase 5 Optimizations)

| Endpoint | v2.0.0 | v3.0.0 | Improvement |
|----------|--------|--------|-------------|
| GET /health | 8-12ms | **5-8ms** | **33-40% faster** |
| POST /order/create | 8-15ms | **6-11ms** | **25-35% faster** |
| Concurrent (10 req) | 8-15ms | **4-6ms** | **50% faster** |

### Memory Footprint

| Metric | v2.0.0 | v3.0.0 | Improvement |
|--------|--------|--------|-------------|
| Container Memory | 27-35 MB | **26-29 MB** | **15% less** |
| JRE Size | 35 MB | **~25 MB** | **28% smaller** |
| Per-request Allocation | ~2 KB | **~0 bytes** | **100% reduction** |

---

## Feature Overview

| Feature | Description |
|---------|-------------|
| **REST API** | Spring Boot-like annotations (@GetMapping, @PostMapping, etc.) |
| **Dependency Injection** | Zero-overhead DI (@Component, @Service, @Autowired) |
| **WebSocket** | Full WebSocket support with rooms and broadcasting |
| **Async Handlers** | CompletableFuture support with virtual threads |
| **Static Files** | Production-ready static file serving with caching |
| **Zero-Copy** | Direct ByteBuffer JNI, no intermediate allocations |
| **Ultra-Low Memory** | Docker container under 50MB |

---

## 1. WebSocket Support

### Echo Server Example

```java
@Component
@WebSocket("/ws/echo")
public class EchoWebSocketHandler {

    @OnOpen
    public void onOpen(WebSocketSession session) {
        System.out.println("Session opened: " + session.getId());
        session.sendText("{\"type\":\"connected\",\"sessionId\":" + session.getId() + "}");
    }

    @OnMessage
    public void onMessage(WebSocketSession session, String message) {
        // Echo back the message
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
        // Get path parameter
        String roomId = session.getPathParams().get("roomId");

        // Add to room
        rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);

        // Broadcast join
        broadcast(roomId, "{\"type\":\"join\",\"sessionId\":" + session.getId() + "}");
    }

    @OnMessage
    public void onMessage(WebSocketSession session, String message) {
        String roomId = session.getPathParams().get("roomId");

        // Broadcast to room
        broadcast(roomId, "{\"type\":\"message\",\"text\":\"" + escapeJson(message) + "\"}");
    }

    @OnClose
    public void onClose(WebSocketSession session) {
        String roomId = session.getPathParams().get("roomId");

        // Remove from room
        rooms.get(roomId).remove(session);

        // Broadcast leave
        broadcast(roomId, "{\"type\":\"leave\",\"sessionId\":" + session.getId() + "}");
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
// Get broadcaster instance
WebSocketBroadcaster broadcaster = WebSocketBroadcaster.getInstance();

// Broadcast to all sessions
broadcaster.broadcast("{\"type\":\"notification\",\"text\":\"Hello all!\"}");

// Broadcast to specific room
broadcaster.broadcastToRoom("room1", "{\"type\":\"message\",\"text\":\"Hello room1!\"}");

// Broadcast excluding sender
broadcaster.broadcast(message, excludeSessionId);
broadcaster.broadcastToRoom("room1", message, senderSession);

// Broadcast binary data
broadcaster.broadcastBinary(data);
broadcaster.broadcastBinaryToRoom("room1", data);

// Broadcast with filter
broadcaster.broadcastFiltered(message, session -> {
    return session.getPathParams().get("role").equals("admin");
});

// Room management
broadcaster.joinRoom(sessionId, "room1");
broadcaster.leaveRoom(sessionId, "room1");
broadcaster.getSessionsInRoom("room1");
broadcaster.getRoomNames();
```

### WebSocket Client (JavaScript)

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

## 2. Async Handlers (CompletableFuture)

### Async Handler with Virtual Threads

```java
@Service
public class OrderService {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private NotificationService notificationService;

    // Async method returning CompletableFuture
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

### Handler Using Async Service

```java
@RequestMapping("/order")
public class OrderHandler {

    @Autowired
    private OrderService orderService;

    // Async handler - returns CompletableFuture
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
        CompletableFuture<List<Shipment>> shipmentsFuture = shipmentService.getShipmentsAsync(orderId);

        // Combine all futures
        return CompletableFuture.allOf(orderFuture, paymentsFuture, shipmentsFuture)
            .thenApply(v -> {
                FullOrderResponse response = new FullOrderResponse(
                    orderFuture.join(),
                    paymentsFuture.join(),
                    shipmentsFuture.join()
                );
                return ResponseEntity.ok(response);
            });
    }
}
```

### AsyncHandlerExecutor API

```java
AsyncHandlerExecutor executor = AsyncHandlerExecutor.getInstance();

// Submit async task
CompletableFuture<Order> future = executor.submit(() -> {
    // Blocking operation
    return db.query("SELECT * FROM orders WHERE id = ?", id);
});

// Submit with timeout
CompletableFuture<Order> future = executor.submit(() -> {
    return externalApi.call();
}, 5000); // 5 second timeout

// Get the executor for custom use
Executor ex = executor.getExecutor();
```

---

## 3. Dependency Injection (Zero-Overhead)

### Define Services

```java
// Service with dependency
@Service
public class OrderService {

    @Autowired(required = false)
    private NotificationService notificationService;

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        System.out.println("[OrderService] Initialized");
    }

    public Order create(OrderRequest request) {
        Order order = new Order(generateId(), request);
        orders.put(order.id(), order);

        if (notificationService != null) {
            notificationService.notify("Order created: " + order.id());
        }
        return order;
    }

    @PreDestroy
    public void cleanup() {
        orders.clear();
        System.out.println("[OrderService] Cleanup complete");
    }
}
```

### Multiple Implementations with @Primary and @Qualifier

```java
// Interface
public interface PaymentService {
    String processPayment(String orderId, double amount);
}

// Primary implementation (default)
@Service
@Primary
public class CreditCardPaymentService implements PaymentService {
    @Override
    public String processPayment(String orderId, double amount) {
        return "CC-" + System.currentTimeMillis();
    }
}

// Alternative implementations
@Service
public class PayPalPaymentService implements PaymentService {
    @Override
    public String processPayment(String orderId, double amount) {
        return "PP-" + System.currentTimeMillis();
    }
}

@Service
public class BankTransferPaymentService implements PaymentService {
    @Override
    public String processPayment(String orderId, double amount) {
        return "BT-" + System.currentTimeMillis();
    }
}

// Use in handler
@Component
public class PaymentHandler {

    @Autowired
    private PaymentService paymentService;  // Gets @Primary (CreditCard)

    @Autowired
    @Qualifier("payPalPaymentService")
    private PaymentService paypalService;  // Gets PayPalPaymentService

    @Autowired
    @Qualifier("bankTransferPaymentService")
    private PaymentService bankService;  // Gets BankTransferPaymentService
}
```

### @Configuration with @Bean

```java
@Configuration
public class AppConfig {

    @Bean
    public ExecutorService taskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean("appMetadata")
    public AppMetadata appMetadata() {
        return new AppMetadata("my-app", "3.0.0");
    }

    public record AppMetadata(String name, String version) {}
}
```

---

## 4. REST API Annotations

### HTTP Method Mappings

```java
@RequestMapping("/api/v1")
public class ApiController {

    @GetMapping(value = "/products", responseType = ProductListResponse.class)
    public ResponseEntity<ProductListResponse> getAllProducts() {
        return ResponseEntity.ok(productService.getAll());
    }

    @GetMapping(value = "/products/{id}", responseType = ProductResponse.class)
    public ResponseEntity<ProductResponse> getProductById(@PathVariable("id") String id) {
        Product product = productService.find(id);
        if (product == null) {
            return ResponseEntity.notFound();
        }
        return ResponseEntity.ok(product);
    }

    @PostMapping(value = "/products", requestType = ProductRequest.class, responseType = ProductResponse.class)
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ProductResponse> createProduct(@RequestBody @Valid ProductRequest request) {
        return ResponseEntity.created(productService.save(request));
    }

    @PutMapping(value = "/products/{id}", requestType = ProductRequest.class, responseType = ProductResponse.class)
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable("id") String id,
            @RequestBody ProductRequest request) {
        return ResponseEntity.ok(productService.update(id, request));
    }

    @DeleteMapping(value = "/products/{id}", responseType = Void.class)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteProduct(@PathVariable("id") String id) {
        productService.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT);
    }
}
```

### Parameter Annotations

```java
@PostMapping(value = "/orders", requestType = OrderRequest.class, responseType = OrderResponse.class)
public ResponseEntity<OrderResponse> createOrder(
        @RequestBody @Valid OrderRequest request,                    // JSON body
        @PathVariable("storeId") String storeId,                     // Path param: /stores/{storeId}/orders
        @RequestParam("priority") String priority,                   // Query param: ?priority=high
        @RequestParam(value = "notes", required = false) String notes, // Optional query param
        @HeaderParam("X-Request-ID") String requestId,               // Header
        @HeaderParam("Authorization") String token,                   // Auth header
        @CookieValue("sessionId") String sessionId                    // Cookie
) {
    // All parameters are automatically resolved
    return ResponseEntity.ok(orderService.create(request, storeId, priority));
}
```

### Query Parameters with Defaults

```java
@GetMapping(value = "/products/search", responseType = ProductListResponse.class)
public ResponseEntity<ProductListResponse> searchProducts(
        @RequestParam("q") String query,                              // Required
        @RequestParam(value = "page", defaultValue = "1") int page,   // Default value
        @RequestParam(value = "size", defaultValue = "20") int size,  // Default value
        @RequestParam(value = "sort", required = false) String sort   // Optional
) {
    // GET /products/search?q=laptop&page=2&size=50&sort=price
    return ResponseEntity.ok(productService.search(query, page, size, sort));
}
```

---

## 5. Phase 5 Optimizations

### MethodMetadata Cache
Pre-computed annotation metadata at startup eliminates runtime reflection.

```java
// Before: Runtime annotation lookup (~200ns)
Parameter param = method.getParameters()[i];
PathVariable pv = param.getAnnotation(PathVariable.class);

// After: Cached lookup (~5ns) - 40x faster
MethodMetadata metadata = MethodMetadata.getOrCreate(method, reqType, respType);
ParamInfo info = metadata.paramInfos[i];
```

### FastMapV2 with Robin-Hood Hashing
O(1) average lookup for HTTP parameters and headers.

```java
// ThreadLocal pool - zero allocation
private static final ThreadLocal<FastMapV2> PARAM_MAP_POOL =
    ThreadLocal.withInitial(FastMapV2::new);

// Usage
FastMapV2 params = PARAM_MAP_POOL.get();
params.clear();
parseParamsFast(params, queryString);
String value = params.get("key");  // O(1) average
```

### Zero-Copy Header Encoding (Rust)
Direct byte encoding eliminates String allocation.

```rust
// Before: String allocation
fn encode_headers(headers: &HeaderMap) -> String

// After: Zero-copy byte encoding
fn encode_headers_zero_copy(headers: &HeaderMap) -> Vec<u8>
```

### Pre-Allocated Error Responses

```java
private static final byte[] ERROR_PREFIX = "{\"error\":\"".getBytes(UTF_8);
private static final byte[] ERROR_SUFFIX = "\"}".getBytes(UTF_8);

public static int writeErrorToBuffer(String message, ByteBuffer out, int offset) {
    out.position(offset);
    out.put(ERROR_PREFIX);
    out.put(escapeJson(message).getBytes(UTF_8));
    out.put(ERROR_SUFFIX);
    return offset + ERROR_PREFIX.length + message.length() + ERROR_SUFFIX.length;
}
```

---

## 6. Docker - Ultra Low Memory

### Build

```bash
docker build -t rust-java-rest:ultra -f src/main/resources/container/Dockerfile.ultra .
```

### Run with 50MB Memory Limit

```bash
docker run -d -p 8080:8080 --memory=50m --name rust-java rust-java-rest:ultra
```

### Container Stats

| Metric | Value |
|--------|-------|
| Memory Usage | **28.99 MB** |
| CPU (idle) | ~6% |
| Image Size | 149 MB |

### JVM Options

```bash
-Xms4m -Xmx24m
-XX:+UseSerialGC
-XX:MaxMetaspaceSize=20m
-XX:ReservedCodeCacheSize=8m
-XX:+TieredCompilation -XX:TieredStopAtLevel=1
-Xss256k
```

---

## 7. Static File Serving

### Basic Configuration

```java
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

### Multiple Static File Locations

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

Small files (< 1MB) are automatically cached in memory for better performance.

```java
// Clear cache programmatically (useful for development)
StaticFileRegistry.getInstance().clearCache();
```

---

## 8. Test Strategy (Rule #18)

| Test Type | Where |
|-----------|-------|
| **Load/Benchmark/Stress** | Docker Container |
| **Functional/Unit** | Local (mvn test) |

```bash
# Local - Functional Tests
mvn test

# Docker - Load Tests
docker run -d -p 8080:8080 --memory=50m rust-java-rest:ultra
wrk -t4 -c100 -d30s http://localhost:8080/health
```

---

## New Files

| File | Description |
|------|-------------|
| `bridge/MethodMetadata.java` | Pre-computed annotation metadata cache |
| `util/FastMapV2.java` | Robin-Hood hashing for O(1) lookup |
| `async/AsyncHandlerExecutor.java` | Virtual thread executor for async handlers |
| `websocket/WebSocketBroadcaster.java` | Room management and broadcasting |
| `websocket/WebSocketSession.java` | WebSocket session wrapper |
| `websocket/annotation/WebSocket.java` | @WebSocket annotation |
| `websocket/annotation/OnOpen.java` | @OnOpen lifecycle callback |
| `websocket/annotation/OnMessage.java` | @OnMessage handler |
| `websocket/annotation/OnClose.java` | @OnClose lifecycle callback |
| `websocket/annotation/OnError.java` | @OnError handler |
| `staticfiles/StaticFileRegistry.java` | Static file serving registry |
| `staticfiles/StaticFileScanner.java` | Scanner for @StaticFiles annotation |
| `annotations/StaticFiles.java` | @StaticFiles annotation |
| `container/Dockerfile.ultra` | Ultra-low memory Docker image |
| `CHANGELOG.md` | Detailed change history |

---

## Benchmark Results

### GET /health (100 sequential requests)

| Percentile | Latency |
|------------|---------|
| p50 | 5.5ms |
| p75 | 6.0ms |
| p90 | 6.7ms |
| p99 | 21.8ms |
| **Avg** | **5.8ms** |

### POST /order/create (50 requests)

| Percentile | Latency |
|------------|---------|
| p50 | 6.6ms |
| p90 | 8.0ms |
| p99 | 28.0ms |
| **Avg** | **7.0ms** |

---

## Migration from v2.0.0

No breaking changes. All v2.0.0 code works with v3.0.0.

**Recommended:**
1. Update Maven dependency to 3.0.0
2. Add WebSocket support if needed
3. Use async handlers for I/O-bound operations
4. Use `Dockerfile.ultra` for production

---

**Full Changelog**: https://github.com/esasmer-dou/rust-java-rest/compare/v2.0.0...v3.0.0

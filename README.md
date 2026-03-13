# Rust-Java REST Framework

[![Version](https://img.shields.io/badge/version-2.0.0-blue.svg)](https://github.com/esasmer-dou/rust-java-rest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Ultra-fast REST API framework combining Rust Hyper HTTP server with Java handlers.

## v2.0.0 - Zero-Overhead Dependency Injection

This release adds **zero-overhead Dependency Injection** similar to Spring Boot:
- `@Component`, `@Service`, `@Repository`, `@Configuration`
- `@Autowired` for dependency injection
- `@PostConstruct` and `@PreDestroy` lifecycle callbacks
- `@Bean` methods for bean production
- **O(1) lookup** - NO runtime reflection

**Features:**
- ~27 MB memory (Spring Boot: ~94 MB)
- 3,257 RPS (Spring Boot: ~1,150 RPS)
- 33 ms latency (Spring Boot: ~144 ms)
- Spring Boot-like annotation-based API
- ResponseEntity<T> return type support
- Automatic parameter resolution (@PathVariable, @RequestParam, @HeaderParam, @RequestBody)
- **Zero-overhead Dependency Injection** (@Service, @Autowired, @PostConstruct)

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
    <version>2.0.0</version>
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

### Image Sizes

| Image | Size | Base | Description |
|-------|------|------|-------------|
| `ghcr.io/esasmer-dou/rust-java-rest:latest` | **74MB** | Distroless | Minimal (jlink + Distroless) |
| `ghcr.io/esasmer-dou/rust-java-rest:2.0.0` | **74MB** | Distroless | Version 2.0.0 |

### Pull from GitHub Container Registry

```bash
# Minimal image (74MB)
docker pull ghcr.io/esasmer-dou/rust-java-rest:2.0.0
docker run -p 8080:8080 --memory=40m ghcr.io/esasmer-dou/rust-java-rest:2.0.0
```

### Build Options

**Option 1: Minimal (Distroless + jlink) - 74MB**
```bash
docker build -t rust-java-rest:minimal -f src/main/resources/container/Dockerfile.minimal .
```

**Option 2: Standard (Debian + jlink) - 136MB**
```bash
docker build -t rust-java-rest:optimized -f src/main/resources/container/Dockerfile.optimized .
```

### Run

```bash
# With 40MB memory limit (recommended)
docker run -d -p 8080:8080 --memory=40m --name rust-java-app rust-java-rest:minimal
```

### Dockerfile Features

| Feature | Minimal | Optimized |
|---------|---------|-----------|
| Base Image | Distroless | Debian slim |
| Image Size | 74MB | 136MB |
| jlink JRE | 35MB (minimal modules) | 35MB |
| Health Check | External | curl included |
| Memory Limit | 40MB | 40MB |
| Non-root User | Yes | Yes |

### JVM Settings (Ultra-Minimal)

```bash
# Optimized for minimal memory footprint
-Xms4m                          # Minimum heap (4MB)
-Xmx20m                         # Maximum heap (20MB)
-XX:+UseSerialGC                # Lowest memory GC
-XX:MaxMetaspaceSize=24m        # Metaspace limit
-XX:+TieredCompilation          # Fast startup
-XX:TieredStopAtLevel=1         # C1 compiler only
-XX:+UseCompressedOops          # Memory optimization
-XX:+UseCompressedClassPointers # Memory optimization
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

## License

MIT

# Rust-Spring Performance Framework

Ultra-low latency REST API framework combining Rust Hyper server with Java handlers via JNI.

## Features

- **Sub-30MB Memory**: Runs in ~25MB container memory
- **3x Faster**: 3,257 RPS vs Spring Boot's 1,150 RPS
- **77% Lower Latency**: 33ms vs 144ms average latency
- **Spring-like Annotations**: Familiar `@GetMapping`, `@PostMapping`, `@RustRoute` annotations
- **Zero-Copy JSON**: DSL-JSON compile-time serialization
- **Lock-Free Buffer Pool**: Crossbeam ArrayQueue for concurrent requests
- **Bundled Native Library**: No Rust installation required

## Supported Platforms

Native libraries are bundled in the JAR for:

| Platform | Architecture | Library File | Status |
|----------|-------------|--------------|--------|
| Linux | x64 | `native/linux-x64/librust_hyper.so` | ✅ Supported |
| Windows | x64 | `native/windows-x64/rust_hyper.dll` | ✅ Supported |
| macOS | x64 | `native/macos-x64/librust_hyper.dylib` | 🚧 Coming Soon |
| macOS | ARM64 (M1/M2) | `native/macos-arm64/librust_hyper.dylib` | 🚧 Coming Soon |

> **macOS Users**: You can build the native library from source. See [Building Native Library](#building-native-library-optional).

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-spring</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Create DTOs

```java
import com.reactor.rust.annotations.Request;
import com.reactor.rust.annotations.Response;
import com.reactor.rust.annotations.Field;
import com.dslplatform.json.CompiledJson;

@Request
@CompiledJson
public record OrderCreateRequest(
    @Field(required = true)
    String orderId,

    @Field(min = 0, max = 1000000)
    double amount
) {}

@Response
@CompiledJson
public record OrderCreateResponse(
    int status,
    String message,
    long orderId
) {}
```

### 3. Create Handler

```java
import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.json.DslJsonService;
import java.nio.ByteBuffer;

public class OrderHandler {

    @RustRoute(
        method = "POST",
        path = "/order/create",
        requestType = OrderCreateRequest.class,
        responseType = OrderCreateResponse.class
    )
    public int create(ByteBuffer out, int offset, byte[] body) {
        OrderCreateRequest request = DslJsonService.parse(body, OrderCreateRequest.class);

        // Business logic here
        OrderCreateResponse response = new OrderCreateResponse(
            1,
            "Order created",
            System.currentTimeMillis()
        );

        return DslJsonService.writeToBuffer(response, out, offset);
    }

    @RustRoute(
        method = "GET",
        path = "/order/{id}",
        requestType = Void.class,
        responseType = OrderResponse.class
    )
    public int getOrderById(ByteBuffer out, int offset, byte[] body, String pathParams) {
        // Parse path params: "id=123"
        String orderId = parseParam(pathParams, "id");

        OrderResponse response = new OrderResponse(orderId, 100.0, "PENDING");
        return DslJsonService.writeToBuffer(response, out, offset);
    }

    @RustRoute(
        method = "GET",
        path = "/order/search",
        requestType = Void.class,
        responseType = OrderSearchResponse.class
    )
    public int search(ByteBuffer out, int offset, byte[] body,
                      String pathParams, String queryString) {
        // Parse query string: "status=pending&page=1"
        String status = parseParam(queryString, "status");

        OrderSearchResponse response = new OrderSearchResponse(status, 1, 10);
        return DslJsonService.writeToBuffer(response, out, offset);
    }
}
```

### 4. Create Main Application

```java
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;
import com.reactor.rust.bridge.HandlerRegistry;

public class Application {
    public static void main(String[] args) {
        // Scan and register routes (auto-detect handlers)
        RouteScanner.scanAndRegister();

        // Start HTTP server (native library loads automatically)
        NativeBridge.startHttpServer(8080);

        // Keep JVM alive
        Thread.sleep(Long.MAX_VALUE);
    }
}
```

### 5. Run

```bash
# Simple - native library loads automatically from JAR
java -cp target/rust-spring-1.0.0.jar:target/lib/* com.yourapp.Application
```

That's it! No Rust installation required.

## Native Library Loading

The framework automatically detects your platform and loads the correct native library from the JAR resources. Loading order:

1. **System Property** (custom path): `-Drust.lib.path=/path/to/library`
2. **java.library.path**: Standard JVM library path
3. **JAR Resources**: Bundled platform-specific library

### Custom Native Library Path

If you want to use a custom native library (e.g., for development or unsupported platforms):

```bash
# Option 1: System property (file path)
java -Drust.lib.path=/custom/path/to/rust_hyper.dll -cp ...

# Option 2: System property (directory)
java -Drust.lib.path=/custom/native/dir -cp ...

# Option 3: java.library.path
java -Djava.library.path=/custom/native/dir -cp ...
```

## Annotations Reference

### Route Annotations

| Annotation | Description |
|------------|-------------|
| `@RustRoute` | Main route definition with method, path, request/response types |
| `@RequestMapping` | Class-level base path prefix |
| `@GetMapping` | Shortcut for GET requests |
| `@PostMapping` | Shortcut for POST requests |
| `@PutMapping` | Shortcut for PUT requests |
| `@DeleteMapping` | Shortcut for DELETE requests |
| `@PatchMapping` | Shortcut for PATCH requests |

```java
@RequestMapping("/api/v1")  // Class-level base path
public class OrderHandler {

    @GetMapping("/orders")       // Full path: /api/v1/orders
    public int listOrders(...) { }

    @PostMapping("/orders")      // Full path: /api/v1/orders
    public int createOrder(...) { }
}
```

### Parameter Annotations

| Annotation | Description |
|------------|-------------|
| `@PathVariable` | Extract path parameter (e.g., `/order/{id}`) |
| `@RequestParam` | Extract query parameter (e.g., `?status=active`) |
| `@HeaderParam` | Extract HTTP header |
| `@CookieValue` | Extract cookie value |
| `@RequestBody` | Mark body parameter for deserialization |

### Validation Annotations

| Annotation | Description |
|------------|-------------|
| `@Valid` | Trigger nested validation |
| `@NotNull` | Field cannot be null |
| `@NotBlank` | String cannot be blank |
| `@NotEmpty` | Collection cannot be empty |
| `@Min(value)` | Minimum numeric value |
| `@Max(value)` | Maximum numeric value |
| `@DecimalMin(value)` | Minimum decimal value |
| `@DecimalMax(value)` | Maximum decimal value |
| `@Positive` | Must be positive |
| `@Negative` | Must be negative |
| `@Size(min, max)` | String/collection size constraints |
| `@Pattern(regexp)` | Regex pattern validation |
| `@Email` | Email format validation |

### DTO Annotations

| Annotation | Description |
|------------|-------------|
| `@Request` | Marks class as request DTO |
| `@Response` | Marks class as response DTO |
| `@Field` | Field-level configuration (required, min, max, defaultValue) |

### Response Annotations

| Annotation | Description |
|------------|-------------|
| `@ResponseStatus(code)` | Set HTTP status code for response |

```java
@PostMapping("/orders")
@ResponseStatus(HttpStatus.CREATED)  // Returns 201
public int createOrder(...) { }
```

### Configuration Annotations

| Annotation | Description |
|------------|-------------|
| `@RustProperty(key)` | Inject property from `rust-spring.properties` |

```java
public class OrderHandler {

    @RustProperty("order.max-items")
    private int maxItems;

    @RustProperty(value = "order.timeout", defaultValue = "30")
    private int timeout;
}
```

## Handler Method Signatures

### Basic Signature
```java
public int methodName(ByteBuffer out, int offset, byte[] body)
```

### With Path Parameters
```java
public int methodName(ByteBuffer out, int offset, byte[] body, String pathParams)
```

### With Path + Query Parameters
```java
public int methodName(ByteBuffer out, int offset, byte[] body,
                      String pathParams, String queryString)
```

### Full Signature (with Headers)
```java
public int methodName(ByteBuffer out, int offset, byte[] body,
                      String pathParams, String queryString, String headers)
```

## HTTP Utilities

### HttpStatus

```java
import com.reactor.rust.http.HttpStatus;

// Common status codes
HttpStatus.OK           // 200
HttpStatus.CREATED      // 201
HttpStatus.BAD_REQUEST  // 400
HttpStatus.NOT_FOUND    // 404
HttpStatus.INTERNAL_SERVER_ERROR  // 500

// Usage with @ResponseStatus
@ResponseStatus(HttpStatus.CREATED)
public int createOrder(...) { }
```

### MediaType

```java
import com.reactor.rust.http.MediaType;

MediaType.APPLICATION_JSON  // "application/json"
MediaType.TEXT_PLAIN        // "text/plain"
MediaType.TEXT_HTML         // "text/html"
```

### ResponseEntity

```java
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.http.HttpStatus;

// Create response with status
ResponseEntity<OrderResponse> response = ResponseEntity.ok(order);
ResponseEntity<OrderResponse> created = ResponseEntity.status(HttpStatus.CREATED).body(order);

// With headers
ResponseEntity<OrderResponse> response = ResponseEntity.ok()
    .header("X-Custom-Header", "value")
    .body(order);
```

## Configuration

### Properties File

Create `rust-spring.properties` in your resources:

```properties
# Server configuration
server.port=8080
server.host=0.0.0.0

# Custom properties
order.max-items=100
order.timeout=30
```

### Using @RustProperty

```java
public class OrderHandler {

    @RustProperty("order.max-items")
    private int maxItems;

    @RustProperty(value = "order.timeout", defaultValue = "30")
    private int timeout;
}
```

### Programmatic Access

```java
import com.reactor.rust.config.PropertiesLoader;

int port = PropertiesLoader.getInt("server.port", 8080);
String host = PropertiesLoader.get("server.host", "0.0.0.0");
```

## Error Handling

### Custom Exceptions

```java
import com.reactor.rust.exception.NotFoundException;
import com.reactor.rust.exception.BadRequestException;

@RustRoute(method = "GET", path = "/order/{id}", ...)
public int getOrder(ByteBuffer out, int offset, byte[] body, String pathParams) {
    String orderId = parseParam(pathParams, "id");

    if (orderId == null) {
        throw new BadRequestException("Order ID is required");
    }

    Order order = orderRepository.findById(orderId);
    if (order == null) {
        throw new NotFoundException("Order not found: " + orderId);
    }

    return DslJsonService.writeToBuffer(order, out, offset);
}
```

## Validation

### Using Validation Annotations

```java
@Request
@CompiledJson
public record OrderCreateRequest(
    @NotBlank(message = "Order ID is required")
    String orderId,

    @NotNull
    @Positive
    @Max(value = 1000000, message = "Amount cannot exceed 1,000,000")
    double amount,

    @Email(message = "Invalid email format")
    String customerEmail,

    @Size(min = 10, max = 500, message = "Description must be 10-500 characters")
    String description
) {}
```

### Nested Validation with @Valid

```java
@Request
public record OrderRequest(
    @Valid
    CustomerInfo customer,  // Validates nested object

    @Valid
    List<OrderItem> items   // Validates each item in list
) {}
```

## Docker Deployment

### Build Image
```bash
docker build -t rust-spring-app:minimal -f docker/rust-spring-perf/Dockerfile .
```

### Run Container
```bash
docker run -p 8080:8080 --memory=40m rust-spring-app:minimal
```

## Benchmark Results

| Metric | Spring Boot | Rust-Spring | Improvement |
|--------|-------------|-------------|-------------|
| Memory | 94 MB | 27 MB | -71% |
| RPS (100 conn) | 1,150 | 3,257 | +183% |
| Avg Latency | 144 ms | 33 ms | -77% |
| P99 Latency | 1,520 ms | 134 ms | -91% |

## Project Structure

```
com.reactor.rust/
├── annotations/          # Route and validation annotations
├── bridge/               # JNI bridge (NativeBridge, HandlerRegistry)
├── config/               # Configuration (PropertiesLoader)
├── exception/            # Custom exceptions
├── http/                 # HTTP utilities (HttpStatus, MediaType)
├── json/                 # DSL-JSON service
└── validation/           # Validation framework

com.reactor.rust.example/ # Example code (not included in library)
├── ReactorRustHyperApplication.java
├── handler/              # Example handlers
└── dto/                  # Example DTOs
```

## Requirements

- **Java 21+** (required)
- **Maven 3.8+** (for building)
- **Rust 1.82+** (optional - only if building native library yourself)

## Building Native Library (Optional)

The JAR comes with pre-built native libraries. If you need to rebuild:

```bash
cd rust-spring

# Linux (or use Docker on Windows/macOS)
cargo build --release

# Windows
cargo build --release

# macOS
cargo build --release
```

Copy the output to resources:
```bash
# Linux
cp target/release/librust_hyper.so ../rust-spring-boot/src/main/resources/native/linux-x64/

# Windows
cp target/release/rust_hyper.dll ../rust-spring-boot/src/main/resources/native/windows-x64/

# macOS
cp target/release/librust_hyper.dylib ../rust-spring-boot/src/main/resources/native/macos-x64/
```

## License

MIT License

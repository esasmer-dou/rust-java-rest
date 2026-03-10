# Rust-Spring Performance Framework

Ultra-low latency REST API framework combining Rust Hyper server with Java handlers via JNI.

## Features

- **Sub-30MB Memory**: Runs in ~25MB container memory
- **3x Faster**: 3,257 RPS vs Spring Boot's 1,150 RPS
- **77% Lower Latency**: 33ms vs 144ms average latency
- **Spring-like Annotations**: Familiar `@GetMapping`, `@PostMapping`, `@RustRoute` annotations
- **Zero-Copy JSON**: DSL-JSON compile-time serialization
- **Lock-Free Buffer Pool**: Crossbeam ArrayQueue for concurrent requests

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
        // Scan and register routes
        RouteScanner scanner = new RouteScanner();
        scanner.scanAndRegister("com.yourapp.handler");

        // Start HTTP server
        NativeBridge.startHttpServer(8080);
    }
}
```

### 5. Build Native Library

```bash
cd rust-spring
cargo build --release
```

### 6. Run

```bash
java -Djava.library.path=../rust-spring/target/release \
     -cp target/rust-spring-1.0.0.jar:target/lib/* \
     com.yourapp.Application
```

## Annotations Reference

### Route Annotations

| Annotation | Description |
|------------|-------------|
| `@RustRoute` | Main route definition with method, path, request/response types |
| `@GetMapping` | Shortcut for GET requests |
| `@PostMapping` | Shortcut for POST requests |
| `@PutMapping` | Shortcut for PUT requests |
| `@DeleteMapping` | Shortcut for DELETE requests |
| `@PatchMapping` | Shortcut for PATCH requests |

### Parameter Annotations

| Annotation | Description |
|------------|-------------|
| `@PathVariable` | Extract path parameter (e.g., `/order/{id}`) |
| `@RequestParam` | Extract query parameter (e.g., `?status=active`) |
| `@HeaderParam` | Extract HTTP header |
| `@RequestBody` | Mark body parameter for deserialization |

### Validation Annotations

| Annotation | Description |
|------------|-------------|
| `@NotNull` | Field cannot be null |
| `@NotBlank` | String cannot be blank |
| `@NotEmpty` | Collection cannot be empty |
| `@Min(value)` | Minimum numeric value |
| `@Max(value)` | Maximum numeric value |
| `@Size(min, max)` | String/collection size constraints |
| `@Pattern(regexp)` | Regex pattern validation |
| `@Email` | Email format validation |

### DTO Annotations

| Annotation | Description |
|------------|-------------|
| `@Request` | Marks class as request DTO |
| `@Response` | Marks class as response DTO |
| `@Field` | Field-level configuration (required, min, max, defaultValue) |

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
```

## Requirements

- Java 21+
- Rust 1.82+ (for native library)
- DSL-JSON 2.0.2

## License

MIT License

# 🚀 Rust-Java REST Framework v2.0.0

## Major New Feature: Zero-Overhead Dependency Injection

This release adds a lightweight, Spring Boot-like Dependency Injection container with zero runtime overhead.

### ✨ New Features

**DI Annotations:**
- `@Component` - General component marker
- `@Service` - Business logic service
- `@Repository` - Data access layer
- `@Configuration` - Configuration class with `@Bean` methods
- `@Bean` - Bean-producing method
- `@Autowired` - Dependency injection (with `required` option)
- `@PostConstruct` - Initialization callback
- `@PreDestroy` - Cleanup callback
- `@Primary` - Default bean selection
- `@Qualifier` - Bean disambiguation

### 📊 Performance

| Metric | Value |
|--------|-------|
| Bean Lookup | O(1) ConcurrentHashMap |
| Lookup Time | ~0.4 microseconds |
| Memory Overhead | ~50-100 bytes/bean |
| Runtime Reflection | **NONE** |

### 💻 Usage Example

```java
@Service
public class OrderService {

    @Autowired(required = false)
    private NotificationService notificationService;

    @PostConstruct
    public void init() {
        System.out.println("OrderService initialized");
    }

    public Order createOrder(OrderRequest request) {
        // Business logic
    }
}

@Component
public class OrderHandler {

    @Autowired
    private OrderService orderService;  // Auto-injected

    @RustRoute(method = "POST", path = "/order/create",
               requestType = OrderRequest.class,
               responseType = OrderResponse.class)
    public int create(ByteBuffer out, int offset, byte[] body) {
        OrderResponse response = orderService.createOrder(parse(body));
        return DslJsonService.writeToBuffer(response, out, offset);
    }
}
```

### 🧪 Tests

- 12 comprehensive unit tests for BeanContainer
- All tests passing ✅

### 📥 Installation

**Maven:**
```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/esasmer-dou/rust-java-rest</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-rest</artifactId>
    <version>2.0.0</version>
</dependency>
```

### 🐳 Docker

```bash
docker pull ghcr.io/esasmer-dou/rust-java-rest:2.0.0
docker run -p 8080:8080 --memory=50m ghcr.io/esasmer-dou/rust-java-rest:2.0.0
```

---

**Full Changelog**: https://github.com/esasmer-dou/rust-java-rest/compare/v1.0.0...v2.0.0

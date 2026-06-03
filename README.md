# Rust-Java REST Framework

[![Version](https://img.shields.io/badge/version-3.2.0-blue.svg)](https://github.com/esasmer-dou/rust-java-rest/releases/tag/v3.2.0)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Runtime](https://img.shields.io/badge/runtime-Rust%20Hyper%20%2B%20Java%2021-green.svg)]()
[![Status](https://img.shields.io/badge/status-stable-green.svg)]()

Rust-Java REST is a lightweight REST framework for Java services that want lower latency and lower
RSS than a typical Spring Boot runtime without moving business logic out of Java.

The model is intentionally simple:

- Rust owns the HTTP I/O plane: accept loop, request parsing, response write, file streaming,
  native memory limits, WebSocket transport, and backpressure.
- Java owns application code: handlers, services, components, records, validation, business rules,
  database calls, and RPC calls.
- The framework is not a Spring Boot clone. It gives you familiar REST annotations with a much
  smaller runtime surface.

## v3.2.0 At A Glance

`v3.2.0` is the stable release for the current low-RSS and route-tuning line.

What changed for users:

- More practical runtime profiles: `micro-rest`, `micro-dubbo`, `low-rss`, `balanced-dubbo`,
  `throughput`, `fast-start`, and `ready-low-latency`.
- Route-level admission control with `@RouteAdmission` and property overrides.
- Direct primitive binding for hot numeric query/path parameters.
- `JsonProducerResponse` for heavy dynamic JSON without building a Java DTO list graph.
- Direct JSON writer path with `JsonBufferWriter`.
- `RawResponse` header encoding cache and UTF-8-safe textual response defaults.
- Native static response and native static file routes.
- File streaming bulkhead for large exports/downloads.
- Startup diagnostics, optional startup indexes, OpenJ9 tuning docs, and benchmark runners.
- Lean production package split: the default jar excludes sample/benchmark classes; sample classes
  are attached as a separate classifier.
- Native ABI `20`; use the DLL/SO shipped with this package.

Measured release-gate signal:

| Workload at c512 | Rust-Java avg RPS | Rust-Java p99 | Rust-Java avg RSS | Spring avg RPS | Spring p99 | Spring avg RSS | Note |
|------------------|------------------:|--------------:|------------------:|---------------:|-----------:|---------------:|------|
| Small JSON | 9153 | 115.83 ms | 68.75 MiB | 3459 | 368.60 ms | 314.33 MiB | Strong default REST path |
| Raw/precomputed JSON | 9659 | 114.80 ms | 68.56 MiB | 3875 | 623.98 ms | 274.13 MiB | Best read-model path |
| Dynamic DTO JSON | 3338 | 309.68 ms | 67.82 MiB | 1524 | 776.47 ms | 309.98 MiB | Supported, but object graph cost remains |
| File/static response | 2035 | 881.50 ms | 54.06 MiB | n/a | n/a | n/a | Use route stream bulkhead |

Memory proof result for the release gate:

| Metric | Value |
|--------|------:|
| Baseline RSS | 66.11 MiB |
| Peak RSS during mixed load | 91.35 MiB |
| Final idle RSS after load | 75.59 MiB |
| Final minus baseline | +9.48 MiB |

Interpretation:

- For small/raw/micro REST services, a measured container RSS around `60-80 MiB` is realistic on
  this OpenJ9 profile.
- For heavy dynamic JSON routes, use at least `128 MiB` pod headroom unless your own soak test proves
  a lower limit.
- At very high concurrency, low-memory profiles intentionally return bounded `503` responses instead
  of letting one route consume all queues and memory.

## Install

### Maven Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-rest</artifactId>
  <version>3.2.0</version>
</dependency>
```

If you consume it from GitHub Packages, add the repository:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/esasmer-dou/rust-java-rest</url>
  </repository>
</repositories>
```

For a private package, Maven also needs a token in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN_WITH_READ_PACKAGES</password>
    </server>
  </servers>
</settings>
```

If the repository is private, the token normally needs `read:packages` and repository access.

### Java Runtime

Use Java 21. For low RSS, OpenJ9/Semeru is the recommended runtime.

Good first JVM options for a small service:

```bash
-Xms8m -Xmx48m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1
```

The package also includes startup option files:

- `src/main/resources/startup/openj9-micro-rss.options`
- `src/main/resources/startup/openj9-idle-rss.options`
- `src/main/resources/startup/openj9-scc-aot.options`

Use `openj9-idle-rss.options` only for very low traffic services. It can reduce RSS, but `-Xnojit`
trades away JIT-optimized Java execution.

## Quick Start

Create `src/main/resources/rust-spring.properties`:

```properties
server.port=8080
server.host=0.0.0.0

reactor.runtime.profile=micro-rest
reactor.rust.log.level=error
reactor.rust.java.log.level=warn

reactor.rust.http.max-request-body-bytes=1048576
reactor.rust.http.max-response-body-bytes=8388608
reactor.rust.http.max-inflight-response-bytes=8388608
reactor.rust.http.max-connections=512

reactor.rust.native-cache.max-entries=0
reactor.rust.native-cache.max-bytes=0
```

Create a minimal application:

```java
package com.acme.orders;

import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.config.RuntimeProfiles;
import com.reactor.rust.di.BeanContainer;

public final class OrdersApplication {
    public static void main(String[] args) throws InterruptedException {
        PropertiesLoader.load();
        RuntimeProfiles.apply();

        BeanContainer container = BeanContainer.getInstance();
        container.scan("com.acme.orders");
        container.start();

        HandlerRegistry registry = HandlerRegistry.getInstance();
        for (Object bean : container.getBeansOfType(Object.class)) {
            registry.registerBean(bean);
        }

        RouteScanner.scanAndRegister();
        NativeBridge.configureRuntimeFromProperties();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            NativeBridge.stopHttpServer();
            container.shutdown();
        }));

        NativeBridge.startHttpServer(PropertiesLoader.getInt("server.port", 8080));
        Thread.sleep(Long.MAX_VALUE);
    }
}
```

Run:

```bash
mvn package
java @src/main/resources/startup/openj9-micro-rss.options -cp "target/classes;target/dependency/*;target/your-app.jar" com.acme.orders.OrdersApplication
```

On Linux/macOS use `:` instead of `;` in the classpath.

## Copy/Paste REST Cookbook

This section is meant to be copied into a small project and edited.

### DTOs: Use Records For JSON Contracts

Request and response bodies should be Java records. Handlers and services should be classes.
In a real project, put each `public record` in its own file. For a quick local demo, keep them in
one file by removing `public` from the records except the first one.

```java
package com.acme.orders;

import com.dslplatform.json.CompiledJson;
import java.math.BigDecimal;
import java.util.List;

@CompiledJson
public record CreateOrderRequest(
        String customerId,
        BigDecimal amount,
        List<OrderLineRequest> lines
) {}

@CompiledJson
public record OrderLineRequest(
        String sku,
        int quantity
) {}

@CompiledJson
public record UpdateOrderRequest(
        BigDecimal amount,
        List<OrderLineRequest> lines
) {}

@CompiledJson
public record PatchOrderStatusRequest(
        String status
) {}

@CompiledJson
public record OrderResponse(
        long id,
        String customerId,
        BigDecimal amount,
        String status
) {}

@CompiledJson
public record OrderListResponse(
        List<OrderResponse> items,
        int page,
        int size
) {}

@CompiledJson
public record DeleteOrderResponse(
        long id,
        boolean deleted
) {}

@CompiledJson
public record ErrorResponse(
        String message
) {}
```

### Service: Business Logic Stays In Java

```java
package com.acme.orders;

import com.reactor.rust.di.annotation.Service;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public final class OrderService {
    private final AtomicLong ids = new AtomicLong(1000);
    private final ConcurrentHashMap<Long, OrderResponse> store = new ConcurrentHashMap<>();

    public OrderListResponse list(String status, int page, int size) {
        List<OrderResponse> items = store.values().stream()
                .filter(order -> status == null || status.isBlank() || status.equals(order.status()))
                .skip((long) Math.max(0, page - 1) * size)
                .limit(size)
                .toList();
        return new OrderListResponse(items, page, size);
    }

    public OrderResponse find(long id) {
        return store.get(id);
    }

    public OrderResponse create(CreateOrderRequest request) {
        long id = ids.incrementAndGet();
        OrderResponse response = new OrderResponse(
                id,
                request.customerId(),
                request.amount() != null ? request.amount() : BigDecimal.ZERO,
                "CREATED"
        );
        store.put(id, response);
        return response;
    }

    public OrderResponse replace(long id, UpdateOrderRequest request) {
        OrderResponse current = store.get(id);
        if (current == null) {
            return null;
        }
        OrderResponse updated = new OrderResponse(
                id,
                current.customerId(),
                request.amount() != null ? request.amount() : current.amount(),
                current.status()
        );
        store.put(id, updated);
        return updated;
    }

    public OrderResponse patchStatus(long id, String status) {
        OrderResponse current = store.get(id);
        if (current == null) {
            return null;
        }
        OrderResponse updated = new OrderResponse(id, current.customerId(), current.amount(), status);
        store.put(id, updated);
        return updated;
    }

    public boolean delete(long id) {
        return store.remove(id) != null;
    }
}
```

### Handler: All Main REST Verbs

```java
package com.acme.orders;

import com.reactor.rust.annotations.DeleteMapping;
import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.HeaderParam;
import com.reactor.rust.annotations.PatchMapping;
import com.reactor.rust.annotations.PathVariable;
import com.reactor.rust.annotations.PostMapping;
import com.reactor.rust.annotations.PutMapping;
import com.reactor.rust.annotations.RequestBody;
import com.reactor.rust.annotations.RequestMapping;
import com.reactor.rust.annotations.RequestParam;
import com.reactor.rust.di.annotation.Autowired;
import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.http.ResponseEntity;

@Component
@RequestMapping("/orders")
public final class OrderHandler {

    @Autowired
    private OrderService orderService;

    // GET /orders?status=CREATED&page=1&size=20
    @GetMapping(value = "", responseType = OrderListResponse.class)
    public ResponseEntity<OrderListResponse> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(orderService.list(status, page, Math.min(size, 100)));
    }

    // GET /orders/1001
    @GetMapping(value = "/{id}", responseType = OrderResponse.class)
    public ResponseEntity<?> getById(@PathVariable("id") long id) {
        OrderResponse order = orderService.find(id);
        return order != null
                ? ResponseEntity.ok(order)
                : ResponseEntity.notFound(new ErrorResponse("order not found"));
    }

    // POST /orders
    @PostMapping(value = "", requestType = CreateOrderRequest.class, responseType = OrderResponse.class)
    public ResponseEntity<OrderResponse> create(
            @RequestBody CreateOrderRequest request,
            @HeaderParam(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        return ResponseEntity.created(orderService.create(request))
                .header("X-Correlation-Id", correlationId != null ? correlationId : "");
    }

    // PUT /orders/1001
    @PutMapping(value = "/{id}", requestType = UpdateOrderRequest.class, responseType = OrderResponse.class)
    public ResponseEntity<?> replace(
            @PathVariable("id") long id,
            @RequestBody UpdateOrderRequest request
    ) {
        OrderResponse updated = orderService.replace(id, request);
        return updated != null
                ? ResponseEntity.ok(updated)
                : ResponseEntity.notFound(new ErrorResponse("order not found"));
    }

    // PATCH /orders/1001/status
    @PatchMapping(value = "/{id}/status", requestType = PatchOrderStatusRequest.class, responseType = OrderResponse.class)
    public ResponseEntity<?> patchStatus(
            @PathVariable("id") long id,
            @RequestBody PatchOrderStatusRequest request
    ) {
        OrderResponse updated = orderService.patchStatus(id, request.status());
        return updated != null
                ? ResponseEntity.ok(updated)
                : ResponseEntity.notFound(new ErrorResponse("order not found"));
    }

    // DELETE /orders/1001
    @DeleteMapping(value = "/{id}", responseType = DeleteOrderResponse.class)
    public ResponseEntity<DeleteOrderResponse> delete(@PathVariable("id") long id) {
        return ResponseEntity.ok(new DeleteOrderResponse(id, orderService.delete(id)));
    }
}
```

### Curl Smoke Test

```bash
curl -s "http://localhost:8080/orders?status=CREATED&page=1&size=20"

curl -s -X POST "http://localhost:8080/orders" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: test-1" \
  -d '{"customerId":"CUST-1","amount":125.50,"lines":[{"sku":"SKU-1","quantity":2}]}'

curl -s "http://localhost:8080/orders/1001"

curl -s -X PUT "http://localhost:8080/orders/1001" \
  -H "Content-Type: application/json" \
  -d '{"amount":145.75,"lines":[{"sku":"SKU-2","quantity":1}]}'

curl -s -X PATCH "http://localhost:8080/orders/1001/status" \
  -H "Content-Type: application/json" \
  -d '{"status":"PAID"}'

curl -s -X DELETE "http://localhost:8080/orders/1001"
```

## Which Response Path Should I Use?

Start simple. Move one route at a time only when measurements show a problem.

| Use case | Use this | Why |
|----------|----------|-----|
| Normal CRUD or business API | Record DTO + `@GetMapping`, `@PostMapping`, etc. | Simple and maintainable |
| Existing JSON from Redis/read model/RPC | `RawResponse.json(byte[])` | Avoids DTO rebuild and JSON serialization |
| Same JSON repeats many times | `RawResponse.registeredJson(...)` or native dynamic cache | Avoids repeated Java-to-Rust body transfer |
| Hot fixed-shape dynamic JSON | `JsonProducerResponse` or `JsonBufferWriter` | Avoids Java DTO list/object graph allocation |
| Large download/export | `FileResponse` | File bytes stay out of Java heap and JNI frame |
| Immutable static file | `FileResponse` + `@NativeStaticFileRoute` | Rust serves the file route after startup |

### Small JSON: Default Business API

```java
@GetMapping(value = "/products/{id}", responseType = ProductResponse.class)
public ResponseEntity<?> product(@PathVariable("id") long id) {
    ProductResponse product = productService.find(id);
    return product != null
            ? ResponseEntity.ok(product)
            : ResponseEntity.notFound(new ErrorResponse("product not found"));
}
```

Use this first. It is the right path for most endpoints.

### Raw/Precomputed JSON: JSON Already Exists

```java
@GetMapping(value = "/catalog/raw", responseType = RawResponse.class)
public RawResponse catalogRaw() {
    byte[] json = catalogReadModel.currentJson();
    return RawResponse.json(json);
}
```

Use this when the payload is already serialized. Do not parse JSON into a record just to serialize it
again.

### Native Static JSON: Immutable Until Restart

```java
private static final RawResponse CONFIG =
        RawResponse.registeredJson("{\"currency\":\"TRY\",\"taxIncluded\":true}".getBytes(StandardCharsets.UTF_8));

@GetMapping(value = "/config/public", responseType = RawResponse.class)
@NativeStaticRoute
public RawResponse publicConfig() {
    return CONFIG;
}
```

This is not a magic global cache. It is for deliberate immutable responses. The body is registered in
Rust and reused.

### Native Dynamic Cache: Repeated Read Model

```java
@GetMapping(value = "/catalog/cache", responseType = RawResponse.class)
public RawResponse catalogCached() {
    String key = "catalog:v1";
    int id = NativeBridge.lookupDynamicResponse(key);
    if (id > 0) {
        return RawResponse.nativeJson(id);
    }

    byte[] payload = catalogReadModel.renderJson();
    id = NativeBridge.registerDynamicResponse(
            key,
            payload,
            "Content-Type: application/json; charset=utf-8\n",
            200,
            300_000L
    );
    return id > 0 ? RawResponse.nativeJson(id) : RawResponse.json(payload);
}
```

Use this only when cache hits are likely and invalidation is clear. Avoid it for user-specific or
authorization-sensitive responses unless the key includes the full authorization boundary.

### Direct JSON Writer: Hot Fixed Shape

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

Use this for a measured hot route. Keep golden JSON tests because the writer is manual.

### JsonProducerResponse: Heavy Dynamic JSON Without DTO Graph

```java
@RustRoute(method = "GET", path = "/reports/heavy", requestType = Void.class, responseType = JsonProducerResponse.class)
@DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
@RouteAdmission(maxConcurrent = 80, queueTimeoutMs = 150)
public JsonProducerResponse heavyReport(int items) {
    return JsonProducerResponse.ok((out, offset) -> {
        JsonBufferWriter writer = JsonBufferWriter.reusable(out, offset);
        writer.beginObject()
                .fieldString("type", "heavy-report")
                .comma()
                .fieldInt("items", items)
                .comma()
                .fieldName("rows")
                .beginArray();

        for (int i = 0; i < items; i++) {
            if (i > 0) {
                writer.comma();
            }
            writer.beginObject()
                    .fieldInt("id", i)
                    .comma()
                    .fieldString("name", "item-" + i)
                    .endObject();
        }

        return writer.endArray().endObject().result();
    });
}
```

Use this when the route is dynamic but building a `List<Record>` for every request is the real cost.

### FileResponse: Large Export Or Download

```java
@GetMapping(value = "/exports/daily", responseType = FileResponse.class)
public FileResponse dailyExport() {
    Path path = exportService.currentDailyExport();
    return FileResponse.download(path, "daily-export.csv", "text/csv")
            .header("Cache-Control", "no-store");
}
```

For immutable files, add native static registration:

```java
@GetMapping(value = "/exports/static", responseType = FileResponse.class)
@NativeStaticFileRoute
public FileResponse staticExport() {
    return FileResponse.download(Path.of("/app/exports/static.csv"), "static.csv", "text/csv");
}
```

Use `FileResponse` instead of returning a huge `byte[]` or `String`.

## Profile And RSS Decision Guide

Profile selection is a production decision. Do not pick a profile by name only; pick it by workload.

| Profile | Best for | RSS behavior | Trade-off |
|---------|----------|--------------|-----------|
| `micro-rest` | Small REST service, Dubbo off, low to moderate traffic | Lowest REST profile | Fail-fast under heavy route pressure |
| `micro-dubbo` | REST service with native Dubbo consumer enabled | Lowest Dubbo-enabled profile | Small queues, static providers recommended |
| `low-rss` | General memory-first REST service | More headroom than `micro-rest` | Less throughput headroom than `throughput` |
| `balanced-dubbo` | Dubbo consumer where tail latency matters | Higher RSS than `micro-dubbo` | More worker/connection headroom |
| `throughput` | Dedicated high-RPS service | Highest retained buffers/workers | Not for tiny pod memory budgets |
| `fast-start` | Startup-sensitive service | Uses startup acceleration defaults | Not a memory profile by itself |
| `ready-low-latency` | Service where first requests must be warm | Prewarm-focused | Can retain more warm state |

### Recommended Starting Points

Low-traffic small REST service:

```properties
reactor.runtime.profile=micro-rest
reactor.rust.http.max-connections=512
reactor.rust.http.max-inflight-response-bytes=8388608
reactor.rust.native-cache.max-entries=0
reactor.rust.native-cache.max-bytes=0
```

Small REST service with Dubbo consumer:

```properties
reactor.runtime.profile=micro-dubbo
reactor.dubbo.enabled=true
reactor.dubbo.transport=native
reactor.dubbo.providers=provider-host:20880
reactor.dubbo.native-connections-per-endpoint=1
reactor.dubbo.native-async-workers=1
reactor.dubbo.max-inflight=32
```

Heavy JSON route on a memory-first service:

```properties
reactor.runtime.profile=micro-rest
reactor.rust.http.max-connections=768

reactor.rust.route-admission.get.reports.heavy.max-concurrent=80
reactor.rust.route-admission.get.reports.heavy.queue-timeout-ms=150
```

Large file/download service:

```properties
reactor.runtime.profile=low-rss
reactor.rust.file-stream.chunk-bytes=65536
reactor.rust.static-file.inline-max-bytes=0
reactor.rust.static-file.max-concurrent-streams=64
```

### When 50 MiB Is Realistic

A very small, low-traffic service can stay close to the low-memory target when:

- OpenJ9/Semeru is used with small heap and stack options.
- Dubbo, ZooKeeper, DB pools, native cache, WebSocket, and file stream fanout are off or tightly bounded.
- Routes are small JSON, raw JSON, or direct producer/writer paths.
- The service is not building large Java DTO graphs under load.

For real Kubernetes sizing, do not set the pod limit exactly at the best idle number. Give the process
headroom for native buffers, thread stacks, class metadata, request bursts, and JIT/runtime state.

Practical starting budget:

| Service shape | Starting pod memory |
|---------------|--------------------:|
| Tiny low-traffic REST, no RPC/DB | 96 MiB |
| Small REST with normal JSON | 128 MiB |
| REST + native Dubbo consumer | 128-160 MiB |
| Heavy dynamic JSON | 160 MiB or more, then measure |
| Large file/download routes | Size by stream concurrency and file chunk settings |

## Route Admission And Overload Behavior

`@RouteAdmission` protects the whole service from one expensive endpoint.

```java
@RustRoute(method = "GET", path = "/reports/heavy", requestType = Void.class, responseType = JsonProducerResponse.class)
@DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
@RouteAdmission(maxConcurrent = 80, queueTimeoutMs = 150)
public JsonProducerResponse heavyReport(int items) {
    return JsonProducerResponse.ok(new HeavyReportProducer(items));
}
```

Property override format:

```properties
reactor.rust.route-admission.get.reports.heavy.max-concurrent=80
reactor.rust.route-admission.get.reports.heavy.queue-timeout-ms=150
```

Use route admission when:

- one route builds a heavy payload;
- one route calls a slow DB/RPC dependency;
- c256/c512 tests show p99 or RSS spikes;
- you prefer controlled `503` over unbounded queue growth.

Do not use global worker increases as the first fix. They often hide the problem and increase RSS.

## Body, Response, Timeout, And File Limits

```properties
reactor.rust.http.max-request-body-bytes=1048576
reactor.rust.http.max-response-body-bytes=8388608
reactor.rust.http.max-inflight-body-bytes=33554432
reactor.rust.http.max-inflight-response-bytes=67108864

reactor.rust.http.max-request-header-bytes=16384
reactor.rust.http.max-request-headers=64
reactor.rust.http.header-read-timeout-ms=5000
reactor.rust.http.request-body-timeout-ms=10000
reactor.rust.http.idle-timeout-ms=30000
reactor.rust.http.keep-alive-enabled=true

reactor.rust.file-stream.chunk-bytes=65536
reactor.rust.static-file.inline-max-bytes=524288
reactor.rust.static-file.max-concurrent-streams=128
```

Rules:

- Do not raise a per-request limit without checking in-flight byte limits.
- Use `FileResponse` for large files.
- Use `JsonProducerResponse`, direct writer, or precomputed `RawResponse` for large JSON.
- Keep `inline-max-bytes` small; inlined files are pinned in native memory.

## UTF-8 Behavior

`v3.2.0` keeps the UTF-8 fixes for:

- response bodies;
- `RawResponse.text(...)` and `RawResponse.json(...)` content types;
- path variables;
- request params;
- cookies;
- middleware query helpers;
- WebSocket path/query maps.

For textual responses, the framework normalizes `Content-Type` to include `charset=utf-8` when the
media type is text, JSON, or `+json`.

## Observability

Use:

- `GET /metrics` for Prometheus metrics.
- `GET /diagnostics/startup` for startup phases.
- `GET /diagnostics/routes` for route strategy/fallback visibility.
- `NativeBridge.nativeMemoryDiagnosticsJson()` for native memory diagnostics.

Important metrics to watch:

- p50/p95/p99 request latency;
- JNI queue p95/p99;
- route admission rejections;
- response body bytes and in-flight bytes;
- native cache size and evictions;
- file stream active/rejected counters;
- fallback/legacy route counters.

Production default:

```properties
reactor.rust.log.level=error
reactor.rust.java.log.level=warn
reactor.optimizer.mode=observe
reactor.optimizer.report.enabled=true
reactor.optimizer.fail-on-fallback=false
```

For a strict production gate, switch to fail-fast only after route diagnostics are clean:

```properties
reactor.optimizer.fail-on-fallback=true
reactor.optimizer.required-fast-routes=get.api.v1.candidates,get.reports.heavy
```

## Startup Tuning

Startup features:

- optional component index: `META-INF/reactor/components.idx`;
- optional route index validation: `META-INF/reactor/routes.idx`;
- native extraction cache keyed by ABI, platform, and SHA-256;
- startup prewarm hooks;
- OpenJ9/Semeru option files;
- CRIU/Semeru InstantOn checkpoint hook for Linux container experiments.

Read [docs/startup-tuning.md](docs/startup-tuning.md) before using CRIU/InstantOn. It is not a
Windows Docker Desktop production path.

## WebSocket Quick Example

```java
@Component
@WebSocket("/ws/chat/{roomId}")
public final class ChatSocket {
    @OnOpen
    public void open(WebSocketSession session) {
        String roomId = session.getPathParams().get("roomId");
        session.sendText("{\"type\":\"joined\",\"room\":\"" + roomId + "\"}");
    }

    @OnMessage
    public void message(WebSocketSession session, String text) {
        session.sendText("{\"type\":\"echo\",\"text\":\"" + text + "\"}");
    }
}
```

Tune WebSocket memory with:

```properties
reactor.rust.websocket.max-frame-bytes=1048576
reactor.rust.websocket.outbound-queue-capacity=1024
reactor.rust.websocket.send-timeout-ms=5000
```

## Benchmark And Release Evidence

Release-gate commands used for `v3.2.0`:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 `
  -RuntimeProfile micro-rest-plus `
  -FrameworkJvmPreset current `
  -EndpointClasses "small-json-legacy,small-json-direct,dynamic-dto-json,producer-json,direct-json-writer,raw-json,native-cache-json,file-static" `
  -ConcurrencyLevels "64,256,512,1000" `
  -Duration 20s -Warmup 5s -RepeatCount 3 `
  -RandomSeed 202606061 `
  -ResultsDir "benchmark\results\release_gate_repeat_20260603" `
  -SkipBuild

powershell -ExecutionPolicy Bypass -File .\benchmark\route_admission_matrix.ps1 `
  -EndpointClass "producer-json" `
  -RouteAdmissionKey "get.api.v1.heavy.producer" `
  -ConcurrencyLevels "256,512" `
  -MaxConcurrentValues "64,80,96,128" `
  -QueueTimeoutMsValues "75,125,150" `
  -RuntimeProfile micro-rest-plus `
  -Duration 20s -Warmup 5s -RepeatCount 3 `
  -RandomSeed 202606062 `
  -ResultsRoot "benchmark\results\release_gate_route_admission_full_20260603" `
  -SkipBuild
```

More benchmark details:

- [benchmark/README.md](benchmark/README.md)
- [docs/production-runtime.md](docs/production-runtime.md)
- [docs/release-notes/v3.2.0.md](docs/release-notes/v3.2.0.md)

## Native Binaries

The Maven package includes:

- `native/windows-x64/rust_hyper.dll`
- `native/windows-x64/rust_hyper-windows-x64.dll`
- `native/linux-x64/librust_hyper.so`
- `native/linux-x64/librust_hyper-linux-x64.so`

The release asset names are:

- `rust_hyper-windows-x64.dll`
- `librust_hyper-linux-x64.so`

Java checks the native ABI at startup. If the DLL/SO does not match the Java artifact, startup fails
early instead of running with a broken JNI contract.

## Production Checklist

Before shipping a service:

- Pick a profile by workload, not by wishful memory target.
- Add route admission to expensive routes.
- Use `FileResponse` for files and exports.
- Use `RawResponse` only when JSON/text is already serialized.
- Use native cache only when keys and invalidation are clear.
- Keep request/response limits bounded.
- Run c64/c256/c512 at minimum with p99, `503%`, RSS, and native metrics.
- Run a post-load idle/soak RSS check before lowering pod memory limits.
- Keep hot-path logging off.

## License

MIT

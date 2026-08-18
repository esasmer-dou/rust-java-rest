# Rust-Java REST Framework

[English](README.md) | [Türkçe](README.tr.md)

[![Version](https://img.shields.io/badge/version-4.5.5-blue.svg)](https://github.com/esasmer-dou/rust-java-rest/releases/tag/v4.5.5)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Runtime](https://img.shields.io/badge/runtime-Rust%20Hyper%20%2B%20Java%2021-green.svg)](https://github.com/esasmer-dou/rust-spring)
[![Status](https://img.shields.io/badge/status-stable-blue.svg)](https://github.com/esasmer-dou/rust-java-rest/releases/tag/v4.5.5)

Rust-Java REST is a lightweight REST framework for Java services that want lower latency and lower
RSS than a typical Spring Boot runtime without moving business logic out of Java.

The model is intentionally simple:

- Rust owns the HTTP I/O plane: accept loop, request parsing, response write, file streaming,
  native memory limits, WebSocket transport, and backpressure.
- Java owns application code: handlers, services, components, records, validation, business rules,
  database calls, and RPC calls.
- The framework is not a Spring Boot clone. It gives you familiar REST annotations with a much
  smaller runtime surface.

Use Rust-Java REST when the application is an HTTP service whose handlers and business logic should
stay in Java, while connection handling, bounded I/O, and selected response-heavy paths should run
in Rust. Do not choose it when the application depends on Spring's complete ecosystem, runtime bean
discovery, or libraries that require a Spring application context.

## Start Here

| I want to... | Read or use this |
| --- | --- |
| Create a service now | [Five-minute project setup](#five-minute-project-setup) |
| See working GET, POST, PATCH, DELETE, upload, streaming, or WebSocket code | [Compile-verified examples](examples/README.md) |
| Select only REST, Dubbo, cache, scheduler, or WebSocket dependencies | [Platform and starters](platform/README.md) |
| Choose record, direct writer, raw JSON, native response, or file streaming | [Response-path guide](#which-response-path-should-i-use) |
| Set pod memory, concurrency, and route limits | [Production runtime guide](docs/production-runtime.md) |
| Find every supported property | [Configuration reference](docs/configuration.md) |
| Diagnose startup, route fallback, native loading, or `503` | [Troubleshooting](docs/troubleshooting.md) |

## Contents

- [Five-minute project setup](#five-minute-project-setup)
- [Choose the smallest starter set](#choose-the-smallest-starter-set)
- [Response and error contracts](#responses-and-errors)
- [Response-path guide](#which-response-path-should-i-use)
- [Profiles and RSS](#profile-and-rss-decision-guide)
- [Admission and overload](#route-admission-and-overload-behavior)
- [Limits and timeouts](#body-response-timeout-and-file-limits)
- [Observability](#observability)
- [Startup](#startup-tuning)
- [Production checklist](#production-checklist)
- [Documentation map](#documentation-map)

## Current Stable Line

`4.5.5` is the stable declarative runtime line. It packages clean `rust-spring v4.5.5` native
artifacts whose complete source commit matches the Maven provenance manifest. It uses REST ABI
`29`, Dubbo ABI `7`, Redis ABI `6`, and Glowroot ABI `3`. Always use the native file carried by the
same coordinated Maven artifact.

When bounded telemetry is enabled, `4.5.5` stores its per-request capture state in one compact
32-bit value and runs export as low-priority batch work outside the Hyper executor. Sampling,
exact 5xx accounting, Java handler behavior, and the public annotation API are unchanged.

Generated application wiring and route invokers keep Java handlers and business services
unchanged. If your application combines the published libraries, keep the versions aligned:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-rest</artifactId>
  <version>4.5.5</version>
</dependency>

<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-cache</artifactId>
  <version>0.7.4</version>
</dependency>

<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-dubbo</artifactId>
  <version>0.7.2</version>
</dependency>
```

Do not mix `java-rust-cache:0.7.4` or `java-rust-dubbo:0.7.2` native mode with a DLL/SO copied from
an older release. Startup verifies all four ABI values, source revision, platform, and SHA-256
provenance before serving traffic. An incompatible binary fails at startup instead of producing
delayed JNI errors.

## Five-Minute Project Setup

The recommended application surface is the platform parent plus only the starter your process
needs. This keeps versions aligned, keeps annotation processors on the compiler path, and enables
the build-time AOT gates without adding runtime reflection.

```xml
<parent>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-platform-parent</artifactId>
  <version>4.5.5</version>
</parent>

<dependencies>
  <dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-starter-rest</artifactId>
  </dependency>
</dependencies>
```

```java
package com.example.catalog;

import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.PathVariable;
import com.reactor.rust.annotations.ReactorApplication;
import com.reactor.rust.annotations.RestController;
import com.reactor.rust.annotations.Response;
import com.reactor.rust.app.RestApplication;

@ReactorApplication(scanBasePackages = "com.example.catalog")
public final class CatalogApplication {
    public static void main(String[] args) {
        RestApplication.run(CatalogApplication.class, args);
    }
}

@RestController("/api/v1/catalog")
final class CatalogHandler {
    @GetMapping("/{id}")
    CatalogItem get(@PathVariable("id") long id) {
        return new CatalogItem(id, "READY");
    }
}

@Response
record CatalogItem(long id, String status) {}
```

```properties
server.host=0.0.0.0
server.port=8080
reactor.runtime.profile=micro-rest
```

```powershell
mvn clean verify
mvn exec:java
curl http://localhost:8080/api/v1/catalog/1
```

`mvn clean verify` generates the component graph, route invokers, configuration metadata, JSON
writers where requested, and startup indexes. Production startup uses those generated artifacts.
There is no request-time controller scan or dependency-injection reflection on the strict AOT path.

## Choose The Smallest Starter Set

| Process shape | Add | Do not add unless used |
| --- | --- | --- |
| REST API | `rust-java-starter-rest` | Dubbo, Redis, scheduler, WebSocket |
| REST + native Dubbo consumer | `rust-java-starter-dubbo` | Official Dubbo/Netty/ZooKeeper stack for static discovery |
| REST + Redis reader | `rust-java-starter-cache-reader` | Redis writer lifecycle |
| Scheduled DB-to-Redis writer | `rust-java-starter-cache-writer` | REST runtime |
| WebSocket API | `rust-java-starter-websocket` | WebSocket support in unrelated services |
| REST + OpenAPI | `rust-java-starter-rest` and `rust-java-starter-openapi` | Runtime contract scanning |
| REST + JWT guard | `rust-java-starter-rest` and `rust-java-starter-security` | Per-request dynamic policy discovery |
| REST + outbound HTTP | `rust-java-starter-rest` and `rust-java-starter-http-client` | Dynamic proxies or unbounded executors |

Add capabilities intentionally. A disabled feature may avoid hot-path work, but an unused dependency
still enlarges the classpath and can increase loaded-class and native-runtime surface.

### Framework Experience In One View

| You write | The build generates | Runtime behavior |
| --- | --- | --- |
| `@ReactorApplication` | Application descriptor and startup indexes | Direct deterministic startup |
| Constructor parameters | Bean factories and dependency graph | No reflective DI lookup |
| REST annotations | Exact route invokers | No reflective method invocation |
| `@ConfigurationProperties` | Typed property binding | Invalid config fails startup |
| `@Response` | Response DTO metadata | Compatible DSL-JSON serialization; no implicit writer generation |
| `@GenerateDirectJsonWriter` | Required type-specific writer | Bound once before traffic; unsupported shapes fail the build |
| `@GenerateJdbcMapper` | Direct `ResultSet` mapper | No runtime record inspection |
| `@ReactorHttpClient` | Typed HTTP client | No dynamic proxy |
| `@Scheduled` | Bounded task registration | One owned scheduler lifecycle |
| `@RequiresProperty` / `@Profile` | Conditional bean and route plan | Condition evaluated once at startup |

Compatibility scanning remains available for migration, but it is observable and should not become
the normal production path. Keep strict AOT gates enabled so an accidental fallback fails the build
or startup instead of silently reducing performance.

Built-in metrics are opt-in. Enable them only in applications that expose the framework diagnostics
routes:

```java
@ReactorApplication(
        scanBasePackages = "com.example.catalog",
        metrics = true)
public final class CatalogApplication { /* main stays unchanged */ }
```

With the default `metrics = false`, the metrics handler and its routes are not registered. This keeps
the smallest REST process free of an unused diagnostics surface.

### Responses And Errors

Return a DTO directly for the smallest normal `200` path. Use `HttpResponse<T>` when status or
headers differ. Use `ProblemDetail` for a stable RFC 9457-compatible error contract.

```java
@Component
final class ApiErrors {
    @ExceptionHandler(NotFoundException.class)
    HttpResponse<ProblemDetail> notFound(NotFoundException error) {
        return HttpResponse.notFound(
                ProblemDetail.of(HttpStatus.NOT_FOUND, error.getMessage())
                        .withCode("catalog_not_found"));
    }
}
```

Exception handlers are indexed and invoked through generated code. Do not catch every exception in
every route or expose dependency exception text to clients.

## v4.5.5 At A Glance

`v4.5.5` keeps the Java handler and service model unchanged. It retains the bounded telemetry
profiles introduced in `v4.5.0` and improves CPU isolation for the exporter. Export, JVM probes,
diagnostics, and profile memory reclamation remain isolated in Rust.

- Telemetry is disabled by default and allocates no route table, queue, or collector connection.
- Embedded REST telemetry validates collector reachability at startup, closes that probe, and opens
  a bounded h2 connection only for each export window. It does not retain an idle collector
  connection beside the Hyper data plane.
- The separate Spring agent continues to reuse one bounded collector connection in its standalone
  Rust runtime.
- The isolated exporter uses a lower operating-system priority so HTTP traffic remains preferred
  under a one-vCPU limit.
- `micro`, `jvm`, `sql`, `full`, and `diagnostic` profiles expose only the selected bounded state.
- A downgrade releases profile-owned SQL slots, error queues, diagnostic state, JNI references, and
  pending profile-derived export data before the control call returns.
- `/diagnostics/glowroot` and `reactor_glowroot_*` metrics expose configuration and exporter health.
- Startup validates REST ABI `29`, Dubbo ABI `7`, Redis ABI `6`, Glowroot ABI `3`, source revision,
  platform, and SHA-256 before serving traffic.
- Native release artifacts must come from a clean CI build of the exact recorded Rust commit.
- Spring Boot services use the separate `java-rust-glowroot-spring-boot-starter:0.3.0` and standalone
  `rust_glowroot_agent` binary; Spring dependencies do not enter this framework artifact.

Existing REST annotations, handler signatures, response types, services, validation, and business
logic remain source-compatible. Deploy the ABI `29` DLL/SO packaged with the coordinated `4.5.5`
artifact.

## How Startup And Configuration Work

Handlers, services, records, validation, database calls, and business rules stay in Java. The
`codegen` classifier creates indexes and direct generated helpers at compile time, so production
startup performs less scanning and applications carry less handwritten infrastructure.

### Migration From 3.x

Applications that still import a removed compatibility helper should use the explicit replacement
below. New applications should not copy any compatibility bootstrap code.

| Removed API | Use instead | Reason |
|-------------|-------------|--------|
| `FastMapV2` | `RequestValueMap` or the typed route parameter APIs | Keeps request binding explicit and allocation-aware. |
| manual `StartupIndexGenerator` | `ReactorStartupProcessor` from the `codegen` classifier | Moves scanning and index generation to compilation. |
| `RestApplication.sleepForever()` | `RestApplication.run(...)`, `start(...)`, or `startAsync(...)` | Makes lifecycle ownership explicit. |
| allocation-based primitive parser helpers | typed/direct path and query binding | Avoids duplicate hot-path parsing APIs. |

For hot large-JSON routes, prefer `JsonBodyProducer`, generated direct writers, raw/precomputed JSON,
or native response handles so the JVM does not build a large temporary object graph.

### Property Layers

Applications can keep a small `rust-spring.properties` in the classpath and apply production
overrides from an external file:

```powershell
java "-Dreactor.config.file=config/production.properties" -jar app.jar
```

Multiple overlays can be applied in order:

```powershell
java "-Dreactor.config.file=config/production.properties;config/advanced-tuning.properties" -jar app.jar
```

The same setting can be passed as an environment variable:

```bash
export REACTOR_CONFIG_FILE=/app/config/production.properties
```

BEST: keep the packaged file minimal, put Kubernetes/production values in `production.properties`,
and keep route admission, native trim, pool and backpressure experiments in `advanced-tuning.properties`.
ANTI-PATTERN: exposing every internal knob in the starter file and forcing new users to understand all
of them before the service can run.

Use this table first. Pick the row closest to your service, copy the starting properties, then run
your own endpoint matrix before tightening memory limits.

| Service shape | Start with this profile | Use this response/API path | What you get | Watch point |
|---------------|-------------------------|----------------------------|--------------|-------------|
| Small CRUD REST, normal JSON | `micro-rest` | Java records, normal `@GetMapping` / `@PostMapping` | Small runtime surface, simple code, bounded queues | Do not over-optimize before measuring |
| Read-heavy or precomputed JSON | `micro-rest` | `RawResponse.nativeResponse(nativeId)`, `RawResponse.json(bytes)`, or `@NativeStaticRoute` | Avoids DTO serialization and repeated body build | Use native handles only when the body already lives in Rust |
| Hot dynamic JSON with large DTO shape | `micro-rest-plus` | `JsonBodyProducer` / `JsonProducerResponse` + `@DirectQuery*` where useful | Avoids large Java object graph allocation on hot routes | Route can still reject under overload; tune route budgets |
| Dubbo consumer service | `micro-dubbo` | Provider returns UTF-8 JSON bytes; consumer returns `RawResponse.nativeResponse(handle.nativeId())` when using native handles | Keeps REST narrow while Dubbo is explicit and bounded | Use `RawResponse.json(bytes)` only when Java must inspect the response |
| Static JSON or immutable file | `micro-rest` or `low-rss` | `@NativeStaticRoute`, `FileResponse`, `@NativeStaticFileRoute` | Rust serves response/file path without moving bytes through Java heap | Size file stream concurrency deliberately |
| Low-traffic pod with long idle windows | `micro-rest` + opt-in idle trim | Background `reactor.rust.native-trim.*` policy | Reclaims warmed native anonymous memory after idle | Never trim on request path; run p99/503 gate first |
| High throughput, fewer rejects required | `balanced` or `throughput` | Same Java APIs, larger runtime headroom | Smoother overload behavior than memory-first profiles | Higher RSS; do not use as the default for tiny pods |

### Minimal Application Bootstrap

`@ReactorApplication` is the normal entry point. Build-time codegen creates constructor factories,
configuration beans, route indexes, and direct route invokers. The request path does not scan classes
or invoke handlers through reflection:

```java
@ReactorApplication(scanBasePackages = "com.example.orders")
public final class OrdersApplication {
    public static void main(String[] args) {
        RestApplication.run(OrdersApplication.class, args);
    }
}

@RestController("/api/v1/orders")
public final class OrderHandler {
    private final OrderService orders;

    public OrderHandler(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/{id}")
    public OrderResponse order(@PathVariable("id") long id) {
        return orders.find(id);
    }
}
```

If `scanBasePackages` is omitted, the package containing the application class is scanned. If it is
set, only the listed package roots are scanned. Use the common application root when handlers and
services live in sibling packages such as `app`, `handler`, and `service`.

Use generated constructor injection, `@Configuration`/`@Bean`, and starter-owned lifecycle for
normal applications. `@RequiresProperty` and `@Profile` can select a component or one `@Bean`
method at startup. `Optional<T>` constructor parameters express an optional capability without a
manual factory. The same conditions are honored in strict AOT and explicit compatibility mode.

`RestApplication.Module` remains a low-level embedding API. Do not use it to select ordinary
application features or Dubbo/cache surfaces; generated conditional wiring covers those cases
without runtime reflection.

If application configuration or HTTP startup fails, managed resources are closed in reverse order.
A builder can start only once. This keeps startup failure behavior deterministic.

Use `RestApplication.runStandard(...)` only when the application needs the complete property-controlled
startup set: low-RSS gate, WebSocket registration, static files, prewarm, InstantOn checkpoint, and
idle native trim. Small explicit applications do not enable that surface automatically.

The builder remains the advanced API for custom ports, embedded tests, custom containers, or unusual
lifecycle wiring. Do not make the builder the default copy-paste path for a normal service.

For small typed responses, use `JsonResponses.body(record)` or the primitive field helpers. For hot
large responses, keep using `JsonBodyProducer`, a direct writer, raw/native response, or file
streaming. Declarative bootstrap does not replace the response-path decision.

BEST: use this for predictable startup and less boilerplate. ACCEPTABLE: keep manual
`NativeBridge.startHttpServer(...)` when you need custom lifecycle wiring. ANTI-PATTERN: classpath
scanning the whole application and letting hidden components become active by accident.

### Copy-Paste Starting Profiles

Small REST service:

```properties
reactor.runtime.profile=micro-rest
reactor.rust.http.max-request-body-bytes=1048576
reactor.rust.http.max-response-body-bytes=8388608
reactor.rust.http.max-inflight-response-bytes=8388608
reactor.rust.http.max-connections=512
reactor.rust.native-cache.max-entries=0
reactor.rust.native-cache.max-bytes=0
reactor.rust.route-admission.enabled=true
```

Native thread stacks are profile-controlled. Keep these defaults unless a Linux container benchmark
shows a real stack or RSS problem:

```properties
# 0 means use the platform/runtime default outside a named low-memory profile.
reactor.rust.jni.thread-stack-bytes=0
reactor.rust.server.thread-stack-bytes=0
```

`micro-rest` and `micro-dubbo` set bounded native stacks for JNI and server threads. The accepted
range is `131072..8388608` bytes. Reducing a stack is not a free memory optimization: deep user code,
serialization or exception paths can overflow it. Run route smoke tests and c64/c256 p99 gates
before changing either value. Increasing worker counts multiplies the stack budget.

Hot dynamic JSON route:

```properties
reactor.runtime.profile=micro-rest-plus
reactor.rust.route-budget.heavy-json-producer.route-admission.max-concurrent=96
reactor.rust.route-budget.heavy-json-producer.route-admission.queue-timeout-ms=125
reactor.rust.route-budget.heavy-json-producer.jni-admission.max-pending=0
```

```java
@GetMapping(value = "/orders/report", responseType = JsonBodyProducer.class)
@DirectQueryInt(value = "limit", defaultValue = 100, min = 1, max = 1000)
@RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-producer")
public JsonBodyProducer report(int limit) {
    return (out, offset) -> JsonBufferWriter.reusable(out, offset)
            .beginObject()
            .fieldInt("limit", limit)
            .fieldName("items")
            .beginArray()
            // write rows directly here; avoid building a large DTO list graph
            .endArray()
            .endObject()
            .result();
}
```

Read-heavy/precomputed JSON:

```java
@GetMapping(value = "/catalog/snapshot", responseType = RawResponse.class)
public RawResponse snapshot() {
    byte[] json = catalogSnapshotService.currentUtf8Json();
    return RawResponse.json(json);
}
```

Immutable startup-registered JSON:

```java
@GetMapping(value = "/features", responseType = RawResponse.class)
@NativeStaticRoute
public RawResponse features() {
    return RawResponse.json("""
            {"features":["orders","catalog","billing"]}
            """.getBytes(StandardCharsets.UTF_8));
}
```

Low-traffic idle memory reclaim:

```properties
reactor.rust.native-trim.enabled=true
reactor.rust.native-trim.initial-delay-ms=30000
reactor.rust.native-trim.interval-ms=60000
reactor.rust.native-trim.min-idle-ms=10000
reactor.rust.native-trim.max-active-connections=0
reactor.rust.native-trim.max-active-requests=0
reactor.rust.native-trim.retain-small=16
reactor.rust.native-trim.retain-medium=0
reactor.rust.native-trim.retain-large=0
reactor.rust.native-trim.retain-huge=0
reactor.rust.native-trim.allocator-trim-enabled=true
```

BEST: use `micro-rest` for ordinary small services, `micro-rest-plus` only for measured hot heavy
JSON routes, and `micro-dubbo` only when Dubbo is actually enabled. ANTI-PATTERN: increasing global
workers, queues, or pod memory to hide one slow route. Use route budgets, producer/direct writers,
or raw/native response paths instead.

The JNI response staging pool uses a small bounded native buffer before Java writes a response. It
keeps queue burst memory bounded without serializing buffer rental on one worker. Inspect the
`reactor_native_response_pool_staging_*` metrics when diagnosing warm-state RSS; do not tune it by
growing global JNI queues.

Choose profiles from your own workload shape, pod budget, latency target, and overload policy. Raw
benchmark tools and machine-specific evidence are intentionally kept outside the public repository.

## Install

### Maven Dependency

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-rest</artifactId>
  <version>4.5.5</version>
</dependency>
```

If you consume it from GitHub Packages, add the repository:

```xml
<repositories>
  <repository>
    <id>github-rust-java-rest</id>
    <url>https://maven.pkg.github.com/esasmer-dou/rust-java-rest</url>
  </repository>
</repositories>
```

For a private package, Maven also needs a token in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github-rust-java-rest</id>
      <username>x-access-token</username>
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
- `src/main/resources/startup/openj9-micro-rss-jitcap.options`
- `src/main/resources/startup/openj9-micro-rss-jitthreads1.options`
- `src/main/resources/startup/openj9-idle-rss.options`
- `src/main/resources/startup/openj9-scc-aot.options`

Use `openj9-idle-rss.options` only for very low traffic services. It can reduce RSS, but `-Xnojit`
trades away JIT-optimized Java execution.

Use `openj9-micro-rss-jitcap.options` only after A/B testing. It keeps JIT enabled but caps OpenJ9
code-cache commit with `-Xcodecachetotal8m`. This can reduce Linux `anon` memory, but Java-heavy
routes must be checked for p99 and throughput before production use.

Use `openj9-micro-rss-jitthreads1.options` only after A/B testing. It keeps JIT enabled but limits
OpenJ9 background compilation workers with `-XcompilationThreads1`. In the current minimal probe it
reduced Linux thread count and thread-stack budget without making direct-buffer memory grow, but it
is still an optional service-local preset. Do not make it the default until your endpoint matrix
passes p99, `503`, and RSS checks.

For a lower-anon container image, the Maven `runtime-image` goal can create an `8m` ROM-only OpenJ9
shared cache. This keeps AOT methods out of the cache and preserves normal Java JIT behavior. It is
an image choice, not a runtime profile. The current minimal gate reduced final cgroup anon from
`31.027 MiB` to `28.207 MiB`; the long c64 small-route gate reported useful RPS `+11.43%`, p99
`-35.18%`, and no errors. Keep it opt-in and rerun c64/c256 for your own endpoint mix. The complete
Maven configuration is in [Production Runtime](docs/production-runtime.md#rom-only-openj9-shared-cache).
The generated multi-stage image keeps `binutils` in the build stage, runs as user `10001`, and
limits runtime writes to `/app/.reactor/native` and `/app/work`.

Latest local full gate result for this option was deliberately conservative: c64/c256/c512,
sample repeat `3`, minimal smaps repeat `3`, `micro-rest`, and the framework's small/direct/raw/
producer/legacy dynamic DTO endpoint set. Minimal production RSS improved by about `5.95 MiB`, but
the gate failed as a common default because the legacy DTO graph regressed at c256/c512 and raw JSON
also regressed at higher concurrency in that run. The next benchmark gate now uses
`dynamic-producer-json` for the recommended hot DTO-shaped route and keeps `dynamic-dto-json` as the
explicit legacy graph comparison. Use JIT-cap only as a service-local tuning candidate after your
own endpoint matrix passes.

Artifact rule:

- `rust-java-rest-4.5.5.jar`: normal application dependency. Use this in your Maven `pom.xml`.
- `rust-java-rest-4.5.5-codegen.jar`: annotation processors used only during compilation.
- `rust-java-rest-4.5.5-core-runtime.jar`: single lean runtime jar for container
  classpaths when you do not want to copy dependency jars separately.
- `sample/target/rust-java-rest-4.5.5-sample.jar`: runnable full-feature demo application built by
  the separate `sample` Maven project. Do not use it as a production dependency.
- Sources and javadocs are production-focused and exclude framework sample packages.

What this means in practice:

- If your application depends on `com.reactor:rust-java-rest:4.5.5`, it does not receive the
  framework's demo handlers, sample DTOs, or Dubbo sample classes.
- The `sample` directory is an isolated runnable project. It depends on the core artifact in the
  same way as a real consumer application.
- Do not use framework `target/classes` or `rust-java-rest-*-sample.jar` to make production RSS
  claims. Those paths intentionally contain demo code and can make the memory picture look worse
  than the real library dependency.
- For production-like memory checks, run your real application with `core-runtime` plus only its
  application classes.

Package rule:

- Maven packages are immutable. Never republish changed Java classes or native binaries under an
  existing version.
- Documentation and GitHub release notes can be clarified without replacing package bytes.
- If Java code, DLL/SO files, ABI, or packaged fixtures change, cut a new patch version.

Build the two artifacts independently:

```powershell
mvn clean install
mvn -f sample/pom.xml clean package
java -jar sample/target/rust-java-rest-4.5.5-sample.jar
```

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
| Provider/RPC response already lives in Rust native memory | `RawResponse.nativeResponse(nativeId)` | Java passes a small response id; the body does not materialize as a Java heap `byte[]` |
| Same JSON repeats many times | `RawResponse.registeredJson(...)` or native dynamic cache | Avoids repeated Java-to-Rust body transfer |
| Hot fixed-shape dynamic JSON | `JsonProducerResponse` or `JsonBufferWriter` | Avoids Java DTO list/object graph allocation |
| Hot endpoint currently returning a large DTO graph | Keep DTO route for normal use, add `JsonProducerResponse` hot route | Same response shape with lower allocation/RSS pressure |
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

### Native Response Handle: Provider JSON Already Lives In Rust

```java
@GetMapping(value = "/catalog/native", responseType = RawResponse.class)
public CompletionStage<ResponseEntity<RawResponse>> catalogNative() {
    return catalogClient.nestedCatalogNativeJsonAsync()
            .thenApply(handle -> ResponseEntity.ok(RawResponse.nativeResponse(handle.nativeId())));
}
```

Use this when an integration such as `java-rust-dubbo` returns a native response handle instead of a
Java `byte[]`. The HTTP body stays in Rust native memory and Java only carries the small response id.

BEST: use this for pass-through provider JSON that the REST handler does not need to inspect.
ACCEPTABLE: use `RawResponse.json(bytes)` when Java already has the bytes because it must validate,
transform, audit, or log the response. ANTI-PATTERN: convert a native response handle back into a
Java DTO and then serialize it again on a hot route.

Treat `nativeId` as a response-scoped handle. Do not cache it, store it in a DTO, or reuse it across
requests.

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
@DirectQueryInt(value = "version", defaultValue = 1, min = 1, max = 1000)
public RawResponse catalogCached(int version) {
    String key = "catalog:v" + version;
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

The miss path still matters. If every cache miss builds a large DTO graph and serializes it, the
first request for every key still creates GC/RSS pressure. Prefer read-model bytes,
Redis/materialized JSON, or a `JsonBodyProducer`/`JsonBufferWriter` miss producer that writes the
same response shape without allocating the full DTO list.

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

### JsonBodyProducer: Heavy Dynamic JSON Without DTO Graph

```java
private static final byte[] ITEM_PREFIX = "item-".getBytes(StandardCharsets.US_ASCII);

@RustRoute(method = "GET", path = "/reports/heavy", requestType = Void.class, responseType = JsonBodyProducer.class)
@DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
@RouteAdmission(maxConcurrent = 80, queueTimeoutMs = 150)
public JsonBodyProducer heavyReport(int items) {
    return (out, offset) -> {
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
                    .fieldStringAsciiPrefixInt("name", ITEM_PREFIX, i)
                    .endObject();
        }

        return writer.endArray().endObject().result();
    };
}
```

This is the preferred replacement when an ordinary DTO route becomes hot. The API contract can stay
the same JSON shape, but the handler writes directly into the response buffer instead of allocating a
large Java record/list graph per request. `JsonBodyProducer` is the lowest-allocation producer shape
for normal `200 OK` JSON responses. Use `JsonProducerResponse` instead when the route needs custom
status or custom response headers.

In the bundled benchmark app this split is visible as:

Avoid string concatenation inside direct writers. A loop such as `"item-" + i` allocates a new
`String` for every row. Use `fieldStringAsciiPrefixInt(...)`, `stringAsciiPrefixInt(...)`, or raw
ASCII fragments when the value is a predictable prefix plus a primitive.

- `/api/v1/heavy/dto`: optimized DTO-shaped JSON through `JsonProducerResponse`.
- `/api/v1/heavy/dto/legacy`: real Java DTO graph + DSL-JSON, kept for comparison.

Use this when the route is dynamic but building a `List<Record>` for every request is the real cost.

### Async JsonBodyProducer: Use Only When It Removes Blocking Pressure

Async producer routes are supported, including direct query-int binding. This is not a universal
replacement for the sync producer path. Use it when the handler would otherwise block a JNI worker
on remote I/O, RPC, a bounded executor, or a measured c512 pressure point.

```java
@RustRoute(method = "GET", path = "/reports/heavy/async", requestType = Void.class, responseType = JsonBodyProducer.class)
@DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
@RouteAdmission(maxConcurrent = 80, queueTimeoutMs = 150)
public CompletionStage<JsonBodyProducer> heavyReportAsync(int items) {
    return AsyncHandlerExecutor.getInstance()
            .submit(() -> (JsonBodyProducer) new HeavyReportProducer(items));
}
```

BEST: keep CPU-bound JSON on the sync `JsonBodyProducer` path unless the benchmark proves that moving
the work off the JNI worker improves useful `200` RPS and p99 for your route.

ACCEPTABLE: use async producer for blocking RPC/database/read-model calls when the route also has a
bounded executor, route admission, and timeout.

ANTI-PATTERN: wrapping every CPU-bound route in `CompletionStage` because "async is faster". That can
increase queueing, retained buffers, and p99.

Async completion buffers are heap-backed and process-wide by default for low RSS:

```properties
reactor.rust.async.direct-buffer.enabled=false
reactor.rust.async.frame-initial-bytes=8192
reactor.rust.async.frame-pool-capacity=2
reactor.rust.async.frame-retain-max-bytes=65536
```

These are the `micro-rest` starting values. `frame-initial-bytes` is the first write capacity, not a
response hard limit. If a response is larger, the writer reports the required bounded size and the
framework retries with a larger frame. Keep this value at or just above the normal async response
size. Setting it below the common payload size adds another serialization pass. Setting it far above
the common payload size increases burst allocation under concurrency.

`frame-pool-capacity` is a process-wide ownership pool. It does not multiply by Java thread count.
`frame-retain-max-bytes` prevents a rare large response from permanently replacing the small working
set. For a different service, measure p95 response bytes, then gate `8192`, `16384`, and `65536`
against useful `200` RPS, p99, `503%`, and Linux cgroup anon together.

Enable direct async buffers only after a Linux RSS/p99 gate:

```properties
reactor.rust.async.direct-buffer.enabled=true
```

Direct buffers can reduce one copy in some async response paths, but they can also keep direct/native
memory warm after bursts. If `direct_buffer_mib` grows in the anon evidence gate, keep this disabled.

In the matched minimal-app `micro-rest` async-producer A/B, the bounded heap-backed design finished at
`46.93 MiB` cgroup current and `40.52 MiB` cgroup anon. smaps RSS was `72.05 MiB` because it also
includes mapped OpenJ9/system libraries. Async producer remains a measured route-local choice, not a
global replacement for synchronous CPU-bound handlers.

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
reactor.websocket.enabled=false
reactor.static-files.enabled=false
reactor.rust.http.max-connections=512
reactor.rust.http.max-inflight-response-bytes=8388608
reactor.rust.native-cache.max-entries=0
reactor.rust.native-cache.max-bytes=0
```

If this service is truly memory-first and has idle periods between traffic bursts, you can enable
idle native trim:

```properties
reactor.rust.native-trim.enabled=true
reactor.rust.native-trim.initial-delay-ms=30000
reactor.rust.native-trim.interval-ms=60000
reactor.rust.native-trim.min-idle-ms=10000
reactor.rust.native-trim.max-active-connections=0
reactor.rust.native-trim.max-active-requests=0
reactor.rust.native-trim.retain-small=16
reactor.rust.native-trim.retain-medium=0
reactor.rust.native-trim.retain-large=0
reactor.rust.native-trim.retain-huge=0
reactor.rust.native-trim.allocator-trim-enabled=true
```

Use this only after checking p99. The policy is designed for low-RSS pods: it waits until no request
activity is observed for `min-idle-ms`, active connections are at or below
`max-active-connections`, active requests are at or below `max-active-requests`, then calls
`NativeBridge.releaseNativeMemoryRetaining(...)` from a daemon thread. It does not run from request
handlers. `retain-small` keeps a tiny warm floor in the small response pool so the next burst does
not start fully cold; medium/large/huge are normally reclaimed in memory-first services.
`allocator-trim-enabled=true` asks the platform allocator to return idle native pages to the OS.
The idle-window request counter is `reactor_native_http_user_requests_total`: `/health`, `/metrics`,
`/metrics/*`, and `/diagnostics/*` are excluded, so Kubernetes probes and Prometheus scrapes do not
prevent a genuinely idle pod from trimming.
The manual `/diagnostics/native/trim` endpoint remains a full diagnostic trim. Automatic trimming
is supported only through the idle-only `reactor.rust.native-trim.*` policy, so allocator work is not
attached to a user request.

`micro-rest` and `micro-dubbo` are memory-first profiles. They disable WebSocket registration and the
annotation-based static-file scanner unless you explicitly enable them. If your service needs one of
these features, turn on only that feature:

```properties
reactor.runtime.profile=micro-rest
reactor.websocket.enabled=true
```

Use the build-time `ReactorStartupProcessor` for startup indexes. It creates `components.idx`,
`routes.idx`, `properties.idx`, and an application component factory while Java sources compile.
It also fails the build when two selected handlers own the same HTTP method and path. This annotation
processor is the supported index-generation path; do not maintain index files manually.

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
reactor.runtime.profile=micro-rest-plus

# Optional route-specific override. Use this only after measuring your own route.
# reactor.rust.route-admission.get.reports.heavy.max-concurrent=80
# reactor.rust.route-admission.get.reports.heavy.queue-timeout-ms=150
```

Use this when the route is expensive and you prefer fewer `503` responses over maximum raw RPS.
Do not raise `reactor.rust.http.max-connections` first. `micro-rest-plus` keeps the same small
runtime shape as `micro-rest`, but applies measured route-budget defaults only to routes you mark
with `@RouteWorkload`.

Hot small direct JSON route:

```java
private static final RawResponse CANDIDATES_DIRECT =
        RawResponse.registeredJson(CandidateResponseJsonWriter.INSTANCE.precomputedBytes());

@RustRoute(
        method = "GET",
        path = "/api/v1/candidates/direct",
        requestType = Void.class,
        responseType = RawResponse.class
)
@NativeStaticRoute
public RawResponse candidatesDirect() {
    return CANDIDATES_DIRECT;
}
```

Use this only when the payload is immutable or intentionally precomputed until restart. Rust serves
the response from the native registry, so the request does not enter Java/JNI. If the route has
per-request business logic, user-specific data, query-dependent data, or DB/RPC calls, keep it as a
direct writer or producer writer instead.

If c256/c512 metrics show queue-full rejections on a dynamic Java route and the business requirement
prefers fewer `503` over maximum useful RPS, test a route-local JNI lane in your own matrix. Do not
enable this blindly; the bundled full matrix rejected it as a default because it adds a priority JNI
worker and changes the pod's scheduling profile.

```properties
reactor.rust.jni-admission.get.api.v1.candidates.direct.max-pending=512
reactor.rust.jni-admission.get.api.v1.candidates.direct.queue-timeout-ms=0
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

Recommended initial Kubernetes memory limit:

This is not the exact RSS the process will always consume. It is the first safe pod
`resources.limits.memory` value to try before your own load and idle/soak test. The service may idle
below this number, but the pod still needs headroom for native buffers, thread stacks, class metadata,
JIT/runtime state, request bursts, and route-specific payloads.

How to read this table:

- `RSS` is the memory the process is using at that moment.
- `resources.requests.memory` is what Kubernetes uses for scheduling. It is not a hard cap.
- `resources.limits.memory` is the hard cap. If the process goes over this value, Kubernetes can kill
  the pod with `OOMKilled`.
- The table gives a first safe `limits.memory` value to try, not the exact memory the service will
  always use.
- If the service idles at `66 MiB`, do not set the pod limit to `66Mi`. A small traffic burst,
  response buffer, native allocation, thread stack, or JIT/runtime change can push it over the limit.
- Start with the table value, run a load test, wait for idle again, then adjust. Lower the limit only
  after peak RSS, final idle RSS, p99, and `503` rate are stable.

Simple example:

```yaml
resources:
  requests:
    memory: "64Mi"
    cpu: "100m"
  limits:
    memory: "96Mi"
    cpu: "500m"
```

This means: Kubernetes schedules the pod as if it needs at least `64Mi`, but the pod is allowed to use
up to `96Mi`. If your release-gate test shows idle RSS around `66 MiB` and final idle RSS around
`75 MiB`, `96Mi` is a reasonable first limit for a tiny low-traffic REST service. If you add Dubbo,
database pools, native cache, WebSocket, heavy JSON, or high concurrency, start higher.

| Service shape | Initial pod memory limit to try |
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
@RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-direct")
@RouteAdmission(maxConcurrent = 80, queueTimeoutMs = 150)
public JsonProducerResponse heavyReport(int items) {
    return JsonProducerResponse.ok(new HeavyReportProducer(items));
}
```

`@RouteWorkload` describes what kind of pressure the route creates. The optional `budget` gives the
profile a named place to apply measured settings without hardcoding every route path. This is useful
when the same application has a small JSON route, a heavy direct writer, and a producer-writer route
that each need different overload behavior.

The annotation can be placed on a handler class. A method-level annotation overrides the class
default. This keeps a handler declarative without repeating the same metadata on every method:

```java
@RequestMapping("/customers")
@RouteWorkload(value = RouteWorkload.Type.RPC_READ, budget = "rpc-customer-read")
public final class CustomerHandler {

    @GetMapping(value = "/{id}", responseType = CustomerResponse.class)
    public CustomerResponse get(@PathVariable("id") long id) { /* Java business logic */ }

    @PostMapping(responseType = CustomerResponse.class)
    @RouteWorkload(value = RouteWorkload.Type.RPC_COMMAND, budget = "rpc-customer-create")
    public CustomerResponse create(@RequestBody CreateCustomer request) { /* Java business logic */ }
}
```

Choose the type by the resource that constrains the route:

| Type | Use it for |
|------|------------|
| `STANDARD`, `SMALL_JSON`, `HEAVY_JSON` | Local Java work and JSON response shape. |
| `RAW_STATIC`, `FILE_STREAM` | Precomputed/native static bodies and streamed files. |
| `CACHE_READ` | Redis/native cache reads with a bounded cache budget. |
| `RPC`, `RPC_READ`, `RPC_COMMAND` | General RPC, read-only RPC, or side-effecting RPC routes. |
| `DB_READ`, `DB_WRITE` | Routes whose capacity is constrained directly by a database pool. |
| `BLOCKING_IO` | Legacy or unavoidable blocking I/O that must have an explicit small budget. |

This metadata is resolved during route registration. It does not add reflection, map lookup, or an
extra wrapper to the request hot path.

For heavy JSON, direct primitive binding is not enough by itself. `@DirectQueryInt` or `@DirectPathInt`
only removes scalar parameter parsing and string allocation. If the handler still returns a large
record/list DTO graph, the JSON object graph cost remains. When
`reactor.optimizer.fail-on-heavy-json-object-graph=true` is enabled, a route marked
`@RouteWorkload(HEAVY_JSON)` must use a non-object-graph response path such as `JsonBodyProducer`,
`JsonProducerResponse`, direct buffer writer, `RawResponse`, `FileResponse`, or native static response.

Budget-level property format:

```properties
reactor.rust.route-budget.heavy-json-direct.route-admission.max-concurrent=80
reactor.rust.route-budget.heavy-json-direct.route-admission.queue-timeout-ms=150
```

Property override format:

```properties
reactor.rust.route-admission.get.reports.heavy.max-concurrent=80
reactor.rust.route-admission.get.reports.heavy.queue-timeout-ms=150
```

Precedence is intentionally explicit: annotation defaults < workload defaults < named budget
defaults < route-specific properties. `micro-rest-plus` currently sets measured budget defaults for
`heavy-json-direct`, `heavy-json-producer`, and `heavy-json-legacy`. If one real route needs a
different value, use the route-specific property instead of changing the global profile.

Use route admission when:

- one route builds a heavy payload;
- one route calls a slow DB/RPC dependency;
- c256/c512 tests show p99 or RSS spikes;
- you prefer controlled `503` over unbounded queue growth.

Do not use global worker increases as the first fix. They often hide the problem and increase RSS.

JNI queue admission is an advanced opt-in for a different problem: a very fast bodyless/direct route
is so hot that it fills the shared JNI queue, while the route itself is not slow or memory-heavy. Use
it only after a repeat benchmark proves that lower `503` is worth the extra priority JNI worker.

```java
@RustRoute(method = "GET", path = "/catalog/summary", requestType = Void.class, responseType = SummaryResponse.class)
public int summary(ByteBuffer out, int offset) {
    return SummaryJsonWriter.INSTANCE.write(out, offset);
}
```

The override format is:

```properties
reactor.rust.jni-admission.get.catalog.summary.max-pending=512
reactor.rust.jni-admission.get.catalog.summary.queue-timeout-ms=0
```

`@JniQueueAdmission` releases its permit when a JNI worker starts the job. `@RouteAdmission` releases
its permit after the response path completes. Use the first for queue pressure, the second for heavy
business work. Neither should be used to hide a route that allocates too much or needs a producer
writer.

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

`v3.2.5` keeps the UTF-8 fixes for:

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

The stable `4.5.5` line can use `java-rust-glowroot-agent:0.4.0`. It sends bounded
HTTP route, native Dubbo, native Redis, process RSS, thread, exporter health, and sampled slow/error
trace data to Glowroot Central. The Java agent does not weave bytecode and adds no runtime
dependencies. Protobuf and plaintext HTTP/2 export run inside the existing Rust runtime.

The bounded `micro`, `jvm`, `sql`, `full`, and `diagnostic` profiles use REST ABI `29` and Glowroot
ABI `3`. Do not use these Java classes with an older ABI `28` DLL/SO.

The existing Glowroot Central/collector deployment stays unchanged. Do not deploy a second
collector or a framework-specific collector plugin. The strict low-memory production path needs no
agent JAR. Use the coordinated Rust-Java REST artifact whose native binary contains the `glowroot`
capability, then enable it with `-Dreactor.glowroot.*`, environment variables, or
`rust-spring.properties`. The optional `-javaagent:/app/agent/java-rust-glowroot-agent.jar` is only a
configuration convenience and is measured separately. Java controllers, handlers, services,
validation, and business logic remain unchanged.

Use the full Glowroot Java agent when you need arbitrary method tracing, automatic JDBC weaving,
broad JMX discovery, continuous profiling, log capture, or live weaving. The bounded runtime can now
provide explicit reusable SQL timings, fixed JVM/GC gauges, bounded error stacks, and authorized
on-demand diagnostics. It still does not pretend to be the full upstream agent.

The agent's heavy work stays in Rust: optional MXBean discovery and polling, JNI global-reference
ownership, aggregation, queues, protobuf/h2 export, diagnostic orchestration, file I/O, and profile
release. Java has no telemetry polling worker or JVM-bean cache. Java business code only supplies
explicit SQL timing/error events and the `Throwable` reference that originates in the JVM.

Switch only from an authenticated internal control path:

```java
GlowrootTelemetry.switchTo(TelemetryProfile.FULL, Duration.ofSeconds(5));
// Collect a bounded incident window.
GlowrootTelemetry.restoreConfiguredProfile();
```

`restoreConfiguredProfile()` returns to the startup value from `reactor.glowroot.profile`. When that
value is `micro`, it waits until profile-owned SQL slots, error/diagnostic queues, in-flight
profile-derived export data, and Rust-owned JNI MXBean global references are released. Stale
SQL descriptors are generation-aware and re-register on the next upgrade. Profile switching is
never a per-request operation.

The release worker is isolated from Hyper. On Linux, its final `malloc_trim(0)` call is still a
process-wide glibc operation. Treat a profile switch as a rare operations action and use fresh
telemetry-off/on processes when attributing RSS.

When `@ReactorApplication(metrics = true)` is enabled, inspect the micro agent locally with:

```bash
curl -s http://localhost:8080/diagnostics/glowroot
curl -s http://localhost:8080/metrics | grep reactor_glowroot
```

After restoring a profile without JVM gauges or diagnostics, verify
`jvm_probe_registered=false` and `jvm_probe_owned_global_refs=0`. This proves that the Rust-owned
JNI bean references were released; a lower RSS value alone is not sufficient proof.

The collector is never on the HTTP request critical path. Collector failure uses bounded reconnect
backoff and drops an expired rollup instead of retaining data indefinitely. The application remains
available. The source-enforced agent-owned ceiling is `1 MiB`. Earlier `4.4.1` evidence used the
shared-runtime path and observed resident maxima of `+1.742 MiB`, `+1.817 MiB`, and `+1.754 MiB`
with no telemetry thread. Version `4.5.5` isolates export and profile reclamation on one `256 KiB`
Rust thread; it does not consume Hyper workers. Embedded REST closes the startup collector probe
and reconnects only for a bounded export window. The coordinated release gate measures the
disabled/enabled `+3 MiB`, RPS, p99, and `503` contract before publishing the companion agent.

`GET /diagnostics/routes` is also the migration checklist for hot routes. For every route, check:

- `strategy`: whether the route is direct, exact annotated, async, raw/static, or legacy.
- `workload` and `workload_budget`: which admission recipe is applied.
- `benchmark_only`: `true` means the route exists only to compare or demonstrate a slower path.
- `heavy_json_object_graph`: `true` means the route is marked `HEAVY_JSON` but still returns a
  normal Java object graph or legacy serialization path.
- `generated_route_metadata`: `true` means HTTP method, path, request/response types and body limits
  came from build-time metadata instead of startup reflection.
- `generated_response_writer`: `true` means the route has bound an exact generated writer. A writer
  that is already registered is bound while the route plan is built. A writer registered later gets
  one final lookup on the first non-null object response. Producer, raw, native, and file routes never
  trigger writer discovery.
- `generated_response_writer_state`: `unresolved` means no writer was available while the route plan
  was built and the first normal object response will perform one final lookup. `bound` means the
  exact writer is active. `miss` means no writer exists for the declared response type. `disabled`
  means direct writers are disabled by configuration. `not_applicable` is expected for primitive,
  producer, raw, native, and file routes. Check the root `direct_json_writer_enabled` and
  `direct_json_writer_providers` fields when a route reports `miss`.

If `heavy_json_object_graph=true`, do not fix it by only adding `@DirectQuery*` or `@DirectPath*`.
Those annotations only optimize scalar parameter binding. Move the response to `JsonBodyProducer`,
`JsonProducerResponse`, `DirectJsonResponse`, `RawResponse`, `FileResponse`, or native static/file
serving depending on the use case.

Summary fields are split for production decisions. Use `production_routes`, `production_legacy`,
and `heavy_json_object_graph` for production health. Use `benchmark_only`,
`benchmark_legacy`, and `benchmark_heavy_json_object_graph` only when reading sample/benchmark apps.
The bundled `/api/v1/heavy/dto/legacy` route is intentionally marked benchmark-only.

Important metrics to watch:

- p50/p95/p99 request latency;
- JNI queue p95/p99;
- route admission rejections;
- `reactor_route_plan_heavy_json_object_graph`;
- `reactor_route_plan_benchmark_only`;
- `reactor_route_plan_benchmark_heavy_json_object_graph`;
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
reactor.optimizer.fail-on-benchmark-only-routes=false
```

For a strict production gate, switch to fail-fast only after route diagnostics are clean:

```properties
reactor.optimizer.fail-on-fallback=true
reactor.optimizer.fail-on-benchmark-only-routes=true
reactor.optimizer.fail-on-reflection-route-metadata=true
reactor.optimizer.required-fast-routes=get.api.v1.candidates,get.reports.heavy
```

## Startup Tuning

Startup features:

- optional component index: `META-INF/reactor/components.idx`;
- optional route index validation: `META-INF/reactor/routes.idx`;
- optional property metadata: `META-INF/reactor/properties.idx`;
- generated application descriptor and component factory through Java `ServiceLoader`;
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

## Performance Validation

Validate the framework with the real application image, production JVM flags, pod CPU/memory limits,
and representative endpoint mix. Compare at least three randomized telemetry-off/on runs. Record
useful `200` RPS, p95/p99, non-2xx rate, cgroup RSS, anonymous memory, thread count, and idle recovery
together. Do not publish one workstation run as a universal framework claim.

Public operational guidance:

- [docs/production-runtime.md](docs/production-runtime.md)
- [docs/release-notes/v4.5.5.md](docs/release-notes/v4.5.5.md)
- [docs/release-notes/v4.4.1.md](docs/release-notes/v4.4.1.md)
- [docs/release-notes/v4.3.0.md](docs/release-notes/v4.3.0.md)
- [docs/release-notes/v3.2.5.md](docs/release-notes/v3.2.5.md)
- [docs/release-notes/v3.2.3.md](docs/release-notes/v3.2.3.md)
- [docs/release-notes/v3.2.2.md](docs/release-notes/v3.2.2.md)
- [docs/release-notes/v3.2.1.md](docs/release-notes/v3.2.1.md)

Start production-like RSS attribution from the real minimal application, not the framework sample.
Collect Linux cgroup and `smaps_rollup` evidence after baseline, warmup, load, idle, and optional
conservative native trim. This distinguishes Java heap/object graph, class metadata, JIT/code cache,
thread/native pools, Rust buffers, allocator retention, and optional Dubbo runtime surface.

Do not treat this as a tuning preset. Treat it as a decision gate. If heap is already small but
`anon_residual_mib` is high, lowering `-Xmx` is the wrong lever. If Java-heavy endpoints dominate
peak anon and p99, move those specific routes toward `JsonProducerResponse`, direct writer,
raw/read-model, or native serialization.

## Native Binaries

The Maven package includes:

- `native/windows-x64/rust_hyper.dll`
- `native/linux-x64/librust_hyper.so`

The release asset names are:

- `rust_hyper-windows-x64.dll`
- `librust_hyper-linux-x64.so`

Java checks the native ABI and provenance schema at startup. The packaged manifest records REST ABI
`29`, Dubbo ABI `7`, Redis ABI `6`, Glowroot ABI `3`, source revision, crate version, and a SHA-256 hash for each
platform. If the DLL/SO does not match the Java artifact, startup fails early instead of running with
a broken JNI contract.

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
- If testing `openj9-micro-rss-jitcap.options`, require a service-local gate pass before using it
  in production. Memory gain alone is not enough; p99 regressions on hot DTO routes reject the
  profile.
- Keep hot-path logging off.

## Documentation Map

| Need | Document |
| --- | --- |
| Build-time DI, conditions, clients, scheduling, and generated wiring | [Declarative development](docs/declarative-development.md) |
| Parent, BOM, and starter dependency boundaries | [Platform guide](platform/README.md) |
| All runtime properties and override order | [Configuration reference](docs/configuration.md) |
| Runtime profiles, RSS, native trim, route budgets, and Kubernetes sizing | [Production runtime](docs/production-runtime.md) |
| Startup indexes, OpenJ9, shared class cache, and InstantOn experiments | [Startup tuning](docs/startup-tuning.md) |
| Health, readiness, shutdown, and operational endpoints | [Operations](docs/operations.md) |
| Error symptoms and direct checks | [Troubleshooting](docs/troubleshooting.md) |
| Small projects that compile in the build | [Examples](examples/README.md) |
| Runtime sizing and performance decisions | [Production runtime guide](docs/production-runtime.md) |
| Changes in this release | [4.5.5 release notes](docs/release-notes/v4.5.5.md) |

## License

MIT

# Declarative Development

[English](declarative-development.md) | [Türkçe](declarative-development.tr.md)

## Starter-Based Build

Use the platform parent and add only the starter needed by the process. The parent keeps framework,
Dubbo, cache, DSL-JSON, processor, and Maven-plugin versions aligned. Annotation processors remain
on the compiler path and are not runtime dependencies.

```xml
<parent>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-platform-parent</artifactId>
  <version>4.5.1</version>
</parent>

<dependencies>
  <dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-starter-rest</artifactId>
  </dependency>
</dependencies>
```

| Need | Starter | Runtime effect |
| --- | --- | --- |
| REST API | `rust-java-starter-rest` | Rust HTTP runtime and Java REST API |
| Static native Dubbo consumer | `rust-java-starter-dubbo` | Native-static Dubbo client; no official Dubbo/Netty/ZooKeeper stack |
| Redis reader | `rust-java-starter-cache-reader` | REST plus native Redis cache |
| Redis writer process | `rust-java-starter-cache-writer` | Native Redis cache only; REST is not pulled in |
| OpenAPI | `rust-java-starter-openapi` | Build-time contract and optional `/openapi.json`/`/docs` routes |
| JWT security | `rust-java-starter-security` | Route guards created once at startup |
| W3C tracing | `rust-java-starter-tracing` | Opt-in sampled request guards and outbound propagation |
| Scheduler | `rust-java-starter-scheduler` | One bounded scheduler owned by the application |
| HTTP client | `rust-java-starter-http-client` | Generated clients and one bounded JDK HTTP runtime |
| Tests | `rust-java-starter-test` | Test-scope helpers; never package it as production runtime |

Use one starter per real capability. Do not add every starter in case it may be used later. Disabled
starters do not add request-path work, but unused classpath and service metadata are still avoidable
surface.

## What Is Generated

The build-only codegen artifact generates route and component indexes, an application descriptor,
property metadata, direct JSON writers, and JDBC record mappers. Generated classes use direct calls.
They do not add runtime scanning or reflection to the request path.

## Application Wiring

```java
@ReactorApplication(
        name = "Customer API",
        version = "1.0.0",
        description = "Customer query and command endpoints",
        scanBasePackages = "com.example.customer",
        metrics = true)
public final class CustomerApplication {
    public static void main(String[] args) {
        RestApplication.run(CustomerApplication.class, args);
    }
}

@RestController("/api/v1/customers")
final class CustomerHandler {
    private final CustomerService customers;

    CustomerHandler(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping("/{id}")
    CustomerResponse customer(@PathVariable("id") long id) {
        return customers.find(id);
    }
}
```

Without `scanBasePackages`, the application class package is the scan root. An explicit value
replaces that default; list the common root of sibling `app`, `handler`, and `service` packages.
`metrics` defaults to `false`. Set it to `true` only when this application should expose the built-in
metrics and diagnostics routes.

Constructor factories and route invokers are generated at build time. Use `@Configuration` and
`@Bean` for third-party objects. The generated factory can inject parameters into the `@Bean`
method. Keep `RestApplication.Module` for deliberate alternative artifact surfaces, not normal CRUD
wiring.

When two beans expose the same interface, mark exactly one implementation with `@Primary` or put
`@Qualifier("beanName")` on the injection parameter. The generated container does not silently pick
the first bean. It fails startup with a clear ambiguity error. A checked exception thrown while a
generated constructor or `@Bean` method runs is reported as `BeanCreationException` with the bean
name and original cause. This work happens only during startup, not on the request path.

## Conditional Components And Optional Capabilities

Use startup conditions instead of branching in `main` or constructing alternate modules. The
condition is compiled into the generated factory. A disabled component is not instantiated.

```java
@Component
@RequiresProperty(name = "customer.commands.enabled", value = "true")
final class CustomerCommandService {}

@Configuration
final class ClientConfiguration {
    @Bean
    @RequiresProperty(name = "customer.audit.enabled", value = "true")
    AuditClient auditClient() {
        return new AuditClient();
    }
}

@RestController("/api/v1/customers")
final class CustomerHandler {
    CustomerHandler(Optional<AuditClient> audit) {}
}
```

`@Profile({"micro-rest", "micro-dubbo"})` is also valid on a component or `@Bean` method.
Conditions are evaluated once at startup. They do not add a branch to each request. The generated
route-index validator ignores routes whose owning component is intentionally disabled. AOT and
explicit compatibility mode use the same condition semantics.

## Typed Configuration

```java
@ConfigurationProperties("clients.customer")
record CustomerClientProperties(
        @ConfigDefault("2") int threads,
        @ConfigDefault("750ms") Duration timeout,
        Optional<String> proxy) {}
```

The generated factory binds supported scalar values directly. Missing required values and invalid
numbers or durations fail startup. Keep Kubernetes values in environment variables or an external
property overlay. Do not duplicate operational defaults in Java code.

## Generated Outbound HTTP Client

```java
@ReactorHttpClient(baseUrlProperty = "clients.customer.base-url")
interface CustomerClient {
    @HttpExchange(path = "/customers/{id}")
    CompletionStage<Customer> get(
            @PathVariable("id") long id,
            @HeaderParam("x-tenant") String tenant);

    @HttpExchange(method = HttpMethod.POST, path = "/customers", retries = 0)
    CompletionStage<Customer> create(@RequestBody CreateCustomer request);
}
```

```properties
clients.customer.base-url=http://customer-service:8080
reactor.http-client.threads=2
reactor.http-client.queue-capacity=256
reactor.http-client.max-inflight=128
reactor.http-client.connect-timeout-ms=1000
reactor.http-client.request-timeout-ms=2000
reactor.http-client.retries=1
reactor.http-client.max-response-bytes=8388608
```

Implementations are generated. There is no dynamic proxy. Retries apply only to idempotent
operations or methods explicitly marked `idempotent=true`. Command methods should normally use
`retries=0` unless the business operation has an idempotency key. Queue rejection is intentional
backpressure. Do not replace it with an unbounded executor.

## Responses And Generated Exception Handling

Return a response record directly for the smallest normal `200` path. Use `HttpResponse<T>` only
when a route needs a different status or headers. Use `ProblemDetail` for one stable error schema.

```java
@Component
final class ApiErrors {
    @ExceptionHandler(NotFoundException.class)
    HttpResponse<ProblemDetail> notFound(NotFoundException error) {
        return HttpResponse.notFound(
                ProblemDetail.of(HttpStatus.NOT_FOUND, error.getMessage())
                        .withCode("customer_not_found"));
    }
}
```

The processor validates handler signatures and generates direct exception dispatch. A handler may
accept the declared exception type and return a DTO, `ResponseEntity<T>`, or `HttpResponse<T>`.
Centralize public error codes here. Do not leak database, Redis, or RPC exception details.

## Security, Tracing, And OpenAPI

```java
@RestController("/api/v1/orders")
@Authenticated(roles = "order-read")
final class OrderHandler {
    @PermitAll
    @GetMapping("/public-status")
    Status publicStatus() { return new Status("UP"); }

    @Traced("orders.get")
    @GetMapping("/{id}")
    Order get(@PathVariable("id") long id) { return load(id); }
}
```

```properties
reactor.security.enabled=true
reactor.security.jwt.hmac-secret=${JWT_SECRET_AT_LEAST_32_BYTES}
reactor.security.jwt.issuer=orders
reactor.security.jwt.audience=internal-api
reactor.security.jwt.require-expiration=true
reactor.tracing.enabled=true
reactor.tracing.annotated-only=true
reactor.tracing.sample-ratio=0.01
reactor.openapi.enabled=true
reactor.openapi.ui.enabled=false
```

The built-in verifier is a strict HS256 option. Install one `JwtVerifierProvider` when production
uses another token format or key source. Do not put secrets in the packaged properties file.
Tracing is sampled and can be annotation-only; a custom `TraceExporter` must be non-blocking.
OpenAPI is generated during compilation. Enabling its route does not scan controllers at runtime.
Use `@Operation` and repeatable `@ApiResponse` to enrich the generated contract.

## Generated Scheduler

```java
@Component
final class CatalogRefresh {
    @Scheduled(
            name = "catalog-refresh",
            intervalProperty = "catalog.refresh-ms",
            initialDelayProperty = "catalog.initial-delay-ms",
            lockName = "catalog-refresh",
            lockAtMostProperty = "catalog.lock-at-most-ms")
    void refresh() {
        // Read the DB in batches and publish one bounded cache projection.
    }
}
```

```properties
reactor.scheduler.enabled=true
reactor.scheduler.threads=1
reactor.scheduler.max-tasks=16
catalog.refresh-ms=60000
catalog.initial-delay-ms=5000
catalog.lock-at-most-ms=55000
```

A task with `lockName` requires exactly one `ScheduledLockProvider`. This prevents two replicas from
publishing the same projection at the same time. Keep thread count at one unless independent tasks
are proven to overlap and the DB/Redis capacity can absorb the concurrency.

## Extension And Artifact Boundaries

Security and tracing use `RequestGuardFactory`. Guards are selected while routes are built and have
correct sync/async completion semantics. The old general `Middleware` API is deprecated and rejected
by AOT codegen because it was not connected to the native request path and encouraged per-request
map and chain allocation.

Use `RestApplication.Module` only for embedding or an unusual manually owned lifecycle. Normal REST,
Dubbo, cache, scheduler, security, tracing, and HTTP-client applications should use starters and
generated wiring.

Run `mvn clean verify` before packaging. The platform parent runs `reactor:doctor`,
`reactor:verify-aot`, and `reactor:verify-native-abi`. Production runtime JARs exclude framework
processors, DSL-JSON processor classes, and annotation-processor ServiceLoader metadata. The
`codegen` classifiers are compiler-only artifacts. Do not add them as normal dependencies.

## Direct JSON Writer

```java
@Response
@GenerateDirectJsonWriter
public record OrderSummary(
        long id,
        String status,
        java.util.List<OrderLine> lines,
        java.util.Optional<String> note) {}

public record OrderLine(long productId, int quantity) {}
```

`@Response` marks the type as an HTTP response DTO. It does not enable a direct writer by itself.
Add `@GenerateDirectJsonWriter` only when this DTO is on a measured serialization hot path. The
processor then creates an exact writer for primitives, boxed scalars, strings, enums, UUID/time
values, nested records, arrays, `Iterable` collections, `Optional`, `BigDecimal`, and `BigInteger`.

The generated writer is registered during startup and bound to the route once, before requests are
accepted. Runtime requests do not perform lazy writer discovery. Unsupported maps, byte/char arrays,
wildcard/type-variable collections, or recursive graphs fail the build when the explicit annotation
is present. A record with only `@Response` stays on the compatible DSL-JSON path. Manually registered
writers must also be registered before route compilation. Use `JsonBodyProducer` for large dynamic
graphs instead of building a large `List<Record>`.

## JDBC Record Mapper

```java
@GenerateJdbcMapper
public record CustomerRow(
        long id,
        @JdbcColumn("customer_no") String customerNo,
        Instant createdAt) {}

CustomerRow row = CustomerRowJdbcMapper.map(resultSet);
```

The mapper reads columns directly. It does not inspect record components at runtime.

## Runtime Profile Plan

```java
RuntimeProfilePlan plan = RuntimeProfilePlan.named("customer-api")
        .positiveInt("reactor.rust.jni.workers", 2)
        .routeBudget("customer-heavy", 96, 125)
        .build();

RestApplication.run(context -> context.profile(plan).scan("com.example.customer"));
```

External `-D`, environment, and property-file values keep precedence. The plan supplies validated
application defaults; it does not hide operational overrides.

## Health And Readiness

```java
HealthEndpoint health = HealthStarter.application("customer-api")
        .required("postgres", 250, repository::ping)
        .optional("telemetry", 100, telemetry::available)
        .build();
```

Liveness does not call dependencies. Readiness checks run only when `/app/readiness` is requested,
with one bounded virtual thread per probe and no retained polling executor. Dependency exception
messages are not exposed in HTTP responses.

## Project Generator

Use `scripts/new-reactor-project.ps1` or `scripts/new-reactor-project.sh`. The generated project has
a declarative application class, constructor-injected handlers, a minimal property file, Maven
repositories, and the codegen paths needed by its selected shape. Processor classes are discovered
from the build-only classifier. The generator refuses to overwrite a non-empty directory.

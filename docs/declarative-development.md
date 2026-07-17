# Declarative Development

[English](declarative-development.md) | [Türkçe](declarative-development.tr.md)

## What Is Generated

The build-only codegen artifact generates route and component indexes, an application descriptor,
property metadata, direct JSON writers, and JDBC record mappers. Generated classes use direct calls.
They do not add runtime scanning or reflection to the request path.

## Direct JSON Writer

```java
@GenerateDirectJsonWriter
public record OrderSummary(long id, String status, Boolean priority) {}
```

Use this for scalar records. Primitive values, boxed scalar values, strings, enums, UUID, and common
time values are supported. Nested object graphs and collections must use an explicit producer or a
business-specific writer. The processor fails the build instead of silently selecting a slow path.

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

RestApplication.run(context -> context.profile(plan).handlers(handler));
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
an explicit application module, a minimal property file, Maven repositories, and the processors
needed by its selected shape. The generator refuses to overwrite a non-empty directory.

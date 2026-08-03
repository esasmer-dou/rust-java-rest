# Declarative Development

[English](declarative-development.md) | [Türkçe](declarative-development.tr.md)

## What Is Generated

The build-only codegen artifact generates route and component indexes, an application descriptor,
property metadata, direct JSON writers, and JDBC record mappers. Generated classes use direct calls.
They do not add runtime scanning or reflection to the request path.

## Application Wiring

```java
@ReactorApplication(scanBasePackages = "com.example.customer")
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

Constructor factories and route invokers are generated at build time. Use `@Configuration` and
`@Bean` for third-party objects. The generated factory can inject parameters into the `@Bean`
method. Keep `RestApplication.Module` for deliberate alternative artifact surfaces, not normal CRUD
wiring.

When two beans expose the same interface, mark exactly one implementation with `@Primary` or put
`@Qualifier("beanName")` on the injection parameter. The generated container does not silently pick
the first bean. It fails startup with a clear ambiguity error. A checked exception thrown while a
generated constructor or `@Bean` method runs is reported as `BeanCreationException` with the bean
name and original cause. This work happens only during startup, not on the request path.

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

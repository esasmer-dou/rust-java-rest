# Rust-Java Platform

[English](README.md) | [Türkçe](README.tr.md)

The platform modules provide one consistent Maven experience for Rust-Java applications. Use the
platform parent to align versions, then add only the starters required by the process.

Use the parent for a new service. Use the BOM only when an existing corporate parent cannot be
replaced. Use direct dependencies only for embedding or framework development.

## Contents

- [Copy-paste parent setup](#start-here)
- [Choose a process shape](#choose-a-process-shape)
- [Parent, BOM, or direct dependency](#parent-bom-or-direct-dependency)
- [Build-time generated surface](#what-the-build-adds)
- [Common starter combinations](#common-combinations)
- [Production rules](#production-rules)
- [Module map](#module-map)

## Start Here

```xml
<parent>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-platform-parent</artifactId>
  <version>4.4.0</version>
</parent>

<artifactId>customer-api</artifactId>

<dependencies>
  <dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-starter-rest</artifactId>
  </dependency>
</dependencies>
```

The parent configures Java 21, aligned dependency versions, build-only annotation processors, and
the framework Maven gates. Run this before packaging:

```powershell
mvn clean verify
```

Expected result: generated component/route metadata is present, dependency boundaries pass, and no
processor artifact is packaged as a runtime dependency.

## Choose A Process Shape

| Process | Required starter | Runtime included |
| --- | --- | --- |
| REST API | `rust-java-starter-rest` | Rust HTTP runtime and Java REST API |
| WebSocket API | `rust-java-starter-websocket` | REST plus bounded WebSocket transport |
| REST contract | `rust-java-starter-openapi` | REST plus build-time OpenAPI output |
| Secured REST API | `rust-java-starter-security` | REST plus startup-created request guards |
| Traced REST API | `rust-java-starter-tracing` | REST plus sampled tracing guards |
| REST with outbound HTTP | `rust-java-starter-http-client` | REST plus generated HTTP clients |
| REST with scheduling | `rust-java-starter-scheduler` | REST plus one bounded scheduler lifecycle |
| Native Dubbo consumer | `rust-java-starter-dubbo` | REST plus native-static Dubbo client |
| Redis-backed REST reader | `rust-java-starter-cache-reader` | REST plus native Redis read plane |
| Scheduled Redis writer | `rust-java-starter-cache-writer` | Native Redis write plane; no REST runtime |
| Framework tests | `rust-java-starter-test` with test scope | Test helpers only |

Do not add every starter. An unused starter can enlarge the classpath even if its feature is disabled.

## Parent, BOM, Or Direct Dependency?

| Choice | Use it when | What you manage |
| --- | --- | --- |
| `rust-java-platform-parent` | New application | Almost nothing; this is the recommended path |
| `rust-java-platform-bom` | The company parent POM cannot be replaced | Compiler plugin, processors, and gates |
| Direct `rust-java-rest` dependency | Embedding or framework development | Every version and build setting |

The BOM aligns dependency versions only. It does not configure annotation processors or Maven
verification gates. Prefer the parent for normal services.

## What The Build Adds

The parent keeps code generation on the compiler path. It does not package processor classes as
runtime dependencies.

| Build step | Purpose |
| --- | --- |
| `ReactorStartupProcessor` | Generates component factories, routes, conditions, config binding, clients, and indexes |
| `DirectJsonWriterProcessor` | Generates direct writers for supported response records |
| `reactor:doctor` | Detects common dependency and configuration mistakes |
| `reactor:verify-aot` | Verifies the generated startup and route surface |
| `reactor:verify-native-abi` | Verifies the Java/native contract before packaging |

Generated files are build output. Do not commit or hand-edit them.

## Common Combinations

Small REST service:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-starter-rest</artifactId>
</dependency>
```

REST service with OpenAPI and JWT guards:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-starter-rest</artifactId>
</dependency>
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-starter-openapi</artifactId>
</dependency>
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-starter-security</artifactId>
</dependency>
```

Redis writer without an HTTP server:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-starter-cache-writer</artifactId>
</dependency>
```

## Production Rules

- Keep one clear process responsibility per artifact.
- Prefer build-time conditions and Maven profiles over runtime branches for physically different
  application surfaces.
- Use `@RequiresProperty` or `@Profile` when one artifact intentionally supports a small startup
  choice. Conditions are evaluated once, not on every request.
- Keep processor classifiers off the runtime classpath.
- Treat `mvn clean verify` as a release gate.
- Use only the native DLL/SO packaged with the aligned framework release.
- Measure the final container with its real starter set. A full sample application is not a valid
  baseline for a minimal production service.

## Module Map

| Module | Audience |
| --- | --- |
| `parent` | Application builds |
| `bom` | Builds that must retain another parent POM |
| `starter-*` | Applications selecting one capability |
| `maven-plugin` | Build verification |
| `compat` | Explicit migration work only |
| `integration-smoke` | Platform maintainers |

For application code and generated wiring examples, continue with
[Declarative Development](../docs/declarative-development.md).

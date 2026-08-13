# Rust-Java REST Compatibility Sample

[English](README.md) | [Türkçe](README.tr.md)

This module is the full runnable compatibility, diagnostics, and benchmark fixture for
`rust-java-rest`. It is not the recommended template for a new production service.

It deliberately contains many handlers, response strategies, WebSocket examples, diagnostics, and
benchmark-only routes. Those classes are separate from the published production framework JAR.

## Use The Right Starting Point

| Goal | Start with |
| --- | --- |
| Build a new service | Platform parent plus the smallest starter |
| Learn one endpoint pattern | [`../examples`](../examples/README.md) |
| Compare response paths | This compatibility sample |
| Measure minimal production RSS | `benchmark/minimal-production`, not this sample |

Do not add this sample JAR as an application dependency.

## Build And Run

Run from the `rust-java-rest` directory:

```powershell
mvn clean install
mvn -f sample/pom.xml clean package
java -jar sample/target/rust-java-rest-4.4.1-sample.jar
```

Verify the process before running a benchmark:

```powershell
curl http://localhost:8080/app/health
curl http://localhost:8080/diagnostics/routes
```

The first endpoint must be healthy. The route diagnostics must clearly separate production and
benchmark-only routes. Stop the process before switching binaries or profiles.

The application reads `server.port` and the remaining runtime limits from
`sample/src/main/resources/rust-spring.properties` plus any external overlay.

## Artifacts

| Need | Artifact |
| --- | --- |
| Production dependency | `com.reactor:rust-java-rest:4.4.1` |
| Full compatibility application | `sample/target/rust-java-rest-4.4.1-sample.jar` |
| Self-contained minimal benchmark classpath | `target/rust-java-rest-4.4.1-core-runtime.jar` |
| Build-only processors | `rust-java-rest-4.4.1-codegen.jar` |

The codegen JAR belongs on the annotation-processor path. It is not a runtime dependency.

## Benchmark Safety

- Separate production routes from routes annotated as benchmark-only.
- Report useful `200` RPS, p99, `503` ratio, and container RSS together.
- Warm the same route set before comparing two builds.
- Do not use this full sample's RSS as the framework baseline. Its class and route surface is
  intentionally much larger than a normal service.
- Use native binaries from the same build. The current source tree expects REST ABI `26`.

For normal application development, return to the
[five-minute project setup](../README.md#five-minute-project-setup).

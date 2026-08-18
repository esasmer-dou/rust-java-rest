# Rust-Java REST Compatibility Sample

[English](README.md) | [Türkçe](README.tr.md)

This module is the full runnable compatibility and diagnostics application for `rust-java-rest`.
It is not the recommended template for a new production service.

It deliberately contains many handlers, response strategies, WebSocket examples, and diagnostics.
Those classes are separate from the published production framework JAR.

## Use The Right Starting Point

| Goal | Start with |
| --- | --- |
| Build a new service | Platform parent plus the smallest starter |
| Learn one endpoint pattern | [`../examples`](../examples/README.md) |
| Inspect several features together | This compatibility sample |

Do not add this sample JAR as an application dependency.

## Build And Run

Run from the `rust-java-rest` directory:

```powershell
mvn clean install
mvn -f sample/pom.xml clean package
java -jar sample/target/rust-java-rest-4.5.6-sample.jar
```

Verify the process after startup:

```powershell
curl http://localhost:8080/app/health
curl http://localhost:8080/diagnostics/routes
```

The first endpoint must be healthy. Stop the process before switching binaries or profiles.

The application reads `server.port` and the remaining runtime limits from
`sample/src/main/resources/rust-spring.properties` plus any external overlay.

## Artifacts

| Need | Artifact |
| --- | --- |
| Production dependency | `com.reactor:rust-java-rest:4.5.6` |
| Full compatibility application | `sample/target/rust-java-rest-4.5.6-sample.jar` |
| Self-contained lean runtime classpath | `target/rust-java-rest-4.5.6-core-runtime.jar` |
| Build-only processors | `rust-java-rest-4.5.6-codegen.jar` |

The codegen JAR belongs on the annotation-processor path. It is not a runtime dependency.

## Runtime Safety

- Do not use this full sample's RSS as the framework baseline. Its class and route surface is
  intentionally much larger than a normal service.
- Use native binaries from the same build. The current source tree expects REST ABI `29`.

For normal application development, return to the
[five-minute project setup](../README.md#five-minute-project-setup).

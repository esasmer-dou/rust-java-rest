# Compile-Verified REST Examples

[English](README.md) | [Türkçe](README.tr.md)

These modules show one framework feature at a time. They are intentionally small so you can inspect,
run, and copy the relevant code without pulling the full compatibility sample into a new service.

For a new production project, use the platform parent and a starter as described in the
[main README](../README.md#five-minute-project-setup). The examples use a compact explicit module to
keep each isolated demo in a few files.

## Prerequisites

- Java 21 and Maven 3.9+.
- The aligned `rust-java-rest:4.5.4` package and its packaged DLL/SO.
- One free local port for the selected module.

Every module is compiled by the examples reactor. If a README snippet and source diverge, the source
module and its tests are authoritative; fix the README in the same change.

## Pick One Example

| Module | Port | Demonstrates | Copy when |
| --- | ---: | --- | --- |
| `minimal-rest` | `8080` | Prebuilt health JSON | You need the smallest runnable HTTP shape |
| `crud` | `8081` | Record input, validation, GET, POST, PATCH, DELETE | You are building a normal business API |
| `upload` | `8082` | Bounded request body and JSON producer response | You accept file or binary content |
| `streaming` | `8083` | `FileResponse` and object-graph-free JSON | You return exports or large dynamic arrays |
| `websocket` | `8084` | Bounded WebSocket session and echo flow | You need bidirectional messages |

## Build Every Example

Run from the `rust-java-rest` directory:

```powershell
mvn -f examples/pom.xml clean package
```

The build also verifies generated source and startup indexes. Generated files are under each
module's `target` directory and must not be edited.

## Run One Example

Minimal REST:

```powershell
mvn -f examples/pom.xml -pl minimal-rest exec:java `
  "-Dexec.mainClass=com.reactor.examples.minimal.MinimalApplication"
curl http://localhost:8080/app/health
```

CRUD:

```powershell
mvn -f examples/pom.xml -pl crud exec:java `
  "-Dexec.mainClass=com.reactor.examples.crud.CrudApplication"
```

```powershell
curl.exe -X POST http://localhost:8081/api/v1/products `
  -H "Content-Type: application/json" `
  -d '{"name":"Keyboard","priceCents":259900}'

curl http://localhost:8081/api/v1/products/1
curl http://localhost:8081/api/v1/products

curl.exe -X PATCH http://localhost:8081/api/v1/products/1 `
  -H "Content-Type: application/json" `
  -d '{"name":"Mechanical Keyboard","priceCents":319900}'

curl.exe -X DELETE http://localhost:8081/api/v1/products/1
```

Streaming and file response:

```powershell
mvn -f examples/pom.xml -pl streaming exec:java `
  "-Dexec.mainClass=com.reactor.examples.streaming.StreamingApplication" `
  "-Dsample.export-file=README.md"

curl "http://localhost:8083/api/v1/orders/live?count=100"
curl.exe -OJ http://localhost:8083/api/v1/orders/export
```

## What To Copy

- Copy the application, handler, record, and the smallest matching property set.
- Replace in-memory maps with your service and repository boundary.
- Move normal applications to `@ReactorApplication` plus constructor injection.
- Keep response-path choices: record for small business JSON, producer for large dynamic JSON,
  `RawResponse` for an already prepared body, and `FileResponse` for files.
- Add only the starter required by the real process.

## What Not To Copy

- Do not load a large file into a Java `byte[]` before returning it.
- Do not build a large DTO list only to serialize and discard it.
- Do not replace bounded admission with an unbounded executor or queue.
- Do not add the full `sample` JAR to a production application.

The original [`sample`](../sample/README.md) module remains a full compatibility application. It is
deliberately separate from the production framework runtime.

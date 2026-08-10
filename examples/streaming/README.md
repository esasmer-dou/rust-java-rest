# Streaming And Large Response Example

[English](README.md) | [Türkçe](README.tr.md)

This module demonstrates two response paths that avoid moving a large body through a Java DTO graph.

| Endpoint | Response type | Data path |
| --- | --- | --- |
| `GET /api/v1/orders/export` | `FileResponse` | Rust streams the file with bounded concurrency |
| `GET /api/v1/orders/live?count=100` | `JsonProducerResponse` | Java writes JSON directly into the native response buffer |

## Run

Set `sample.export-file` to a readable file:

```powershell
mvn -f ../pom.xml -pl streaming exec:java `
  "-Dexec.mainClass=com.reactor.examples.streaming.StreamingApplication" `
  "-Dsample.export-file=README.md"
```

```powershell
curl "http://localhost:8083/api/v1/orders/live?count=100"
curl.exe -OJ http://localhost:8083/api/v1/orders/export
```

## Choose The Right Path

- Use `FileResponse` for files and exports already present on disk.
- Use `JsonProducerResponse` when a large JSON array can be written item by item without first
  creating a `List<DTO>`.
- Use a normal record response for small business payloads. Producer code is not automatically
  better for every endpoint.
- Put an explicit upper bound on input counts. This example caps `count` at `10_000`.
- Tune file stream concurrency only after measuring disk throughput, p99, `503`, and container RSS.

Do not read the entire file into Java heap. Do not create a large object graph only to serialize it
once. Both patterns increase allocation and retained memory without improving the business model.

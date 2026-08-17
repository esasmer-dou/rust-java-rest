# CRUD Example

[English](README.md) | [Türkçe](README.tr.md)

This module demonstrates record input, validation, generated JSON writing, and common REST verbs.
It stores data in memory only; replace the map with your service and repository.

## Run

```powershell
mvn -f ../pom.xml -pl crud exec:java `
  "-Dexec.mainClass=com.reactor.examples.crud.CrudApplication"
```

## Endpoints

| Method | Path | Result |
| --- | --- | --- |
| `POST` | `/api/v1/products` | Creates a product |
| `GET` | `/api/v1/products/{id}` | Reads one product |
| `GET` | `/api/v1/products` | Lists products |
| `PATCH` | `/api/v1/products/{id}` | Replaces the sample fields |
| `DELETE` | `/api/v1/products/{id}` | Deletes a product |

```powershell
curl.exe -X POST http://localhost:8081/api/v1/products `
  -H "Content-Type: application/json" `
  -d '{"name":"Keyboard","priceCents":259900}'

curl http://localhost:8081/api/v1/products/1
curl http://localhost:8081/api/v1/products

curl.exe -X PATCH http://localhost:8081/api/v1/products/1 `
  -H "Content-Type: application/json" `
  -d '{"name":"Mechanical Keyboard","priceCents":319900}'

curl.exe -i -X DELETE http://localhost:8081/api/v1/products/1
```

Expected status sequence: `201`, `200`, `200`, `200`, `204`. Calling the deleted id again returns
`404`. Sending an invalid name or negative price returns a validation error instead of entering the
service logic.

Keep validation on the request record. Keep business decisions in a service. Use a bounded database
pool and repository in production; do not turn the in-memory map into a shared mutable domain model.

Return to the [examples index](../README.md) to choose upload, streaming, or WebSocket paths.

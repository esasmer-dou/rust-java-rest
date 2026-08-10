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
```

Keep validation on the request record. Keep business decisions in a service. Use a bounded database
pool and repository in production; do not turn the in-memory map into a shared mutable domain model.

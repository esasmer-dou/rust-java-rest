# Response Path Comparison Example

[English](README.md) | [Türkçe](README.tr.md)

This module exposes three routes for controlled response-path comparison. It is not a business API.

| Route | Path |
| --- | --- |
| `/bench/native-static` | Immutable JSON served from the native static path |
| `/bench/direct-record` | Small record with a generated direct writer |
| `/bench/producer?items=100` | Dynamic array written without a DTO list |

```powershell
mvn -f ../pom.xml -pl benchmark exec:java `
  "-Dexec.mainClass=com.reactor.examples.benchmark.BenchmarkApplication"
```

Compare useful `200` RPS, p99, `503`, and container memory together. Use identical warmup, CPU
limits, endpoint mix, and repeat count. Do not use native static results to represent an endpoint
that executes Java business logic.

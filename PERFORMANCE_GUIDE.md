# Performance Guide

This framework is optimized for Java business logic with a Rust HTTP I/O plane. The production goal is
low allocation, bounded native memory, predictable overload behavior, and low RSS. Do not treat every
endpoint shape as equivalent.

## Endpoint Classes

| Class | Use When | Preferred API | Risk |
|-------|----------|---------------|------|
| `small-json` | Common small JSON request/response | Java record DTOs, primitive direct bindings for hot params | Usually safe default |
| `dynamic-dto-json` | Business logic naturally creates DTO graphs | Java records + DSL-JSON | Java heap/object graph cost dominates under concurrency |
| `direct-json-writer` | Hot endpoint can write JSON without DTO graph | `JsonBufferWriter` or `DirectJsonWriterRegistry` | Manual/generated writer must stay correct |
| `raw-json` | JSON is already serialized or precomputed | `RawResponse.json(...)`, `RawResponse.registeredJson(...)`, optional `@NativeStaticRoute` | Stale data if app misuses static semantics |
| `file-static` | Static file/export path | `FileResponse` or stream path | Low-RSS profile is not throughput tuned for large file fanout |

## Route Design Rules

- BEST: keep normal business handlers in Java and use record DTOs for ordinary endpoints.
- BEST: use direct primitive query/path bindings for hot scalar parameters: `int`, `long`, `boolean`, `double`, `short`.
- BEST: use `RawResponse` only when the payload is already bytes/text/JSON and should not be serialized again.
- BEST: add `@NativeStaticRoute` only when a registered `RawResponse` is immutable and can be served by Rust without entering Java.
- BEST: use direct writers for endpoints where object graph allocation or serializer temporary buffers are measurable.
- ACCEPTABLE: dynamic DTO response for normal business endpoints where p99 and RSS are still inside SLO.
- ANTI-PATTERN: large JSON export as a huge Java `String` or `byte[]` per request.
- ANTI-PATTERN: increasing per-request body limits without lowering max connections, queue capacity, or total in-flight bytes.

## FileResponse Stream Tuning

`FileResponse` keeps file bytes out of Java heap and JNI frames. Rust opens the file and streams it with
a bounded read chunk controlled by:

```properties
reactor.rust.file-stream.chunk-bytes=65536
```

BEST: keep low-RSS services around `32 KiB` to `64 KiB` and raise only after measuring p99 and RSS.
ACCEPTABLE: use `128 KiB` to `256 KiB` in throughput profiles for large downloads.
ANTI-PATTERN: globally setting `1 MiB` chunks for high-concurrency downloads without a memory proof run.

The active value is visible as `reactor_native_file_stream_chunk_bytes` and under
`runtime.file_stream_chunk_bytes` in native memory diagnostics.

For immutable file routes, add `@NativeStaticFileRoute`. The handler is called once at startup,
then Rust streams the registered file path directly. Use it only when the file identity and headers
are intentionally static for that route. Rust caches the file length and parsed response headers at
registration, so the hot path avoids Java invocation, JNI response framing, file metadata lookup, and
header re-parsing.

For small immutable files, set:

```properties
reactor.rust.static-file.inline-max-bytes=524288
```

BEST: inline only assets/exports small enough to fit your RSS budget. Low-RSS defaults to `512 KiB`.
ANTI-PATTERN: setting this to tens or hundreds of MiB to chase throughput; that pins file bodies in
native memory and violates the low-RSS goal. Larger files should stay streamed.

Disk-backed static file streams are protected by:

```properties
reactor.rust.static-file.max-concurrent-streams=128
```

BEST: size this bulkhead from file size, disk/network capacity, and pod memory budget. Low-RSS keeps
it bounded so overload returns `503` instead of creating unbounded file descriptor and I/O-worker
pressure. Throughput profiles can raise it when the service is dedicated to downloads.

## Runtime Profiles

| Profile | Goal | Typical Use |
|---------|------|-------------|
| `low-rss` | Lowest practical memory with bounded queues | RSS regression gate, memory-sensitive services |
| `balanced` | More stable high-concurrency tail latency without throughput-sized RSS | Services with external RPC or heavier handlers |
| `throughput` | Maximum RPS with higher memory budget | Batch/API gateway style workloads |

The profile is not cosmetic. `low-rss` intentionally allows fail-fast behavior and higher p99 at overload
instead of retaining more queues, buffers, and worker threads.

Low-RSS admission limits still need headroom above expected concurrency. A max connection limit equal
to benchmark concurrency causes false 503s from keep-alive and health-check jitter; current low-RSS
presets use `reactor.rust.http.max-connections=1024` for 512-concurrency gates.

## Native Static Response Gate

Run id: `container_20260531_051255`

Settings: OpenJ9/Semeru 21, CPU limit `2`, Rust-Java memory `96m`, Spring Boot memory `512m`,
duration `10s`, warmup `2s`, concurrency `64/256/512`, repeat `3`, randomized order.

| Endpoint | C | Rust-Java RPS Range | Rust-Java P99 Range | Spring Boot RPS Range | Spring Boot P99 Range |
|----------|--:|--------------------:|--------------------:|----------------------:|----------------------:|
| `heavy100_raw` | 64 | 18,228-18,797 | 10.08-11.29ms | 4,928-5,562 | 39.98-54.17ms |
| `heavy100_raw` | 256 | 11,102-17,552 | 40.89-65.74ms | 4,682-4,830 | 131.63-152.73ms |
| `heavy100_raw` | 512 | 13,062-15,782 | 126.94-131.37ms | 1,137-5,378 | 241.24ms-1.22s |
| `export_static_registered` | 64 | 2,367-2,748 | 70.78-78.53ms | n/a | n/a |
| `export_static_registered` | 256 | 2,452-2,629 | 322.22-351.72ms | n/a | n/a |
| `export_static_registered` | 512 | 2,136-2,236 | 748.71ms-1.04s | n/a | n/a |

Follow-up admission headroom check: `container_20260531_053510`.
With low-RSS `max-connections=1024`, `export_static_registered` at c512 produced `2265 RPS`,
`830.95ms` p99, `0` connection rejections, `0` response backpressure, and `0` 5xx.

Native static file route check: `container_20260531_060646`.
With `@NativeStaticFileRoute` and `reactor.rust.file-stream.chunk-bytes=65536`, `export_file_stream`
at c512 produced `1553 RPS`, `1.08s` p99, `0` connection rejections, `0` response backpressure,
and `0` 5xx. The endpoint metrics snapshot had `reactor_native_jni_requests_total=1`, which means
the load path itself bypassed Java handler invocation; the remaining JNI activity was startup and
diagnostic traffic.

Native static file inline check: `container_20260531_063622`.
With `reactor.rust.static-file.inline-max-bytes=524288`, the same `302608` byte file was served from
native memory instead of disk streaming. `export_file_stream` at c512 produced `1926 RPS`,
`944.03ms` p99, `0` connection rejections, `0` response backpressure, and `0` 5xx. Metrics reported
`reactor_native_static_file_inline_bytes=302608`.

Memory proof run id: `memory_proof_low-rss_20260531_052414`.
After repeated load plus 30s idle snapshots, RSS stayed bounded and direct buffer usage remained
about `0.29 MiB`; no native response cache growth was observed. The remaining c512 errors were
connection-admission rejections in the old low-RSS benchmark config, not response-buffer leaks.

## Latest Low-RSS Benchmark Gate

Run id: `current_full_20260531_090441`

Settings: OpenJ9/Semeru 21, CPU limit `2`, Rust-Java memory `96m`, Spring Boot memory `512m`,
duration `10s`, warmup `2s`, concurrency `64/256/512/1000`, repeat `1`, randomized order.

Latest c512/c1000 comparable endpoints:

| Endpoint | C | Rust-Java RPS | Spring Boot RPS | Ratio | Rust P99 | Spring P99 | Rust Max Mem | Spring Max Mem |
|----------|--:|--------------:|----------------:|------:|---------:|-----------:|-------------:|---------------:|
| candidates | 512 | 12,347 | 2,533 | 4.87x | 126ms | 694ms | 80 MiB | 392 MiB |
| candidates | 1000 | 13,897 | 1,204 | 11.54x | 289ms | 1.94s | 94 MiB | 310 MiB |
| echo parse | 512 | 16,896 | 3,844 | 4.39x | 98ms | 338ms | 90 MiB | 426 MiB |
| echo parse | 1000 | 10,970 | 3,904 | 2.81x | 768ms | 614ms | 92 MiB | 423 MiB |
| heavy100 raw | 512 | 11,517 | 5,761 | 2.00x | 142ms | 289ms | 91 MiB | 422 MiB |
| heavy100 raw | 1000 | 15,247 | 5,050 | 3.02x | 254ms | 517ms | 93 MiB | 444 MiB |
| heavy100 dynamic DTO | 512 | 2,812 | 2,193 | 1.28x | 309ms | 515ms | 92 MiB | 420 MiB |
| heavy100 dynamic DTO | 1000 | 8,750 | 2,488 | 3.52x | 526ms | 633ms | 76 MiB | 422 MiB |

Rust-Java-only optimized paths at c512:

| Endpoint | Class | Rust-Java RPS | Rust P99 | Rust Max Mem |
|----------|-------|--------------:|---------:|-------------:|
| candidates direct | `small-json-direct` | 14,174 | 129ms | 88 MiB |
| echo raw | `echo-raw` | 13,369 | 118ms | 83 MiB |
| heavy100 direct writer | `direct-json-writer` | 5,071 | 217ms | 78 MiB |
| heavy100 Rust writer | `rust-json-writer` | 8,128 | 177ms | 80 MiB |
| heavy100 native cache | `native-cache-json` | 14,970 | 102ms | 79 MiB |
| export file stream | `file-stream` | 1,987 | 1.09s | 95 MiB |

Read these tables as RC evidence, not a universal marketing claim. The framework is strongest when
Java stays business-focused and Rust owns I/O, buffering, native response paths, and selected
serialization-heavy paths. Repeat `3` plus idle/soak memory proof is required before a stable release.

## Large File Stream Matrix

Run ids: `stream_matrix_{32,64,128,256}_20260531_085157`

Settings: 8 MiB file, inline disabled with `reactor.rust.static-file.inline-max-bytes=0`,
low-RSS profile, CPU limit `2`, Rust-Java memory `96m`, concurrency `256/512/1000`.

| Max Streams | C | RPS | P99 | 503 Rate | RSS After | Max Mem |
|------------:|--:|----:|----:|---------:|----------:|--------:|
| 32 | 256 | 1,272 | 1.36s | 97.14% | 14 MiB | 92 MiB |
| 32 | 512 | 2,922 | 860ms | 98.66% | 20 MiB | 82 MiB |
| 64 | 512 | 2,176 | 1.98s | 98.28% | 27 MiB | 94 MiB |
| 128 | 512 | 1,749 | 3.16s | 97.07% | 42 MiB | 84 MiB |
| 256 | 512 | 1,318 | 6.94s | 96.74% | 58 MiB | 94 MiB |

BEST: use `32` or `64` as the low-RSS starting point for large immutable file routes. Higher stream
limits accept more file work but worsen p99 and RSS. `503` under overload is a deliberate backpressure
signal and should be surfaced to clients with retries or queueing at the caller/gateway layer.

## Required Release Checks

```powershell
mvn -q test
cargo test
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 -RuntimeProfile low-rss -Duration 10s -Warmup 2s -ConcurrencyLevels "64,256,512,1000" -RepeatCount 3 -RandomSeed 20260531 -EndpointClasses "small-json-legacy,small-json-direct,echo-parse,echo-raw,dynamic-dto-json,direct-json-writer,rust-json-writer,raw-json,native-cache-json,file-static,file-stream"
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 -RuntimeProfile low-rss -Duration 8s -Warmup 2s -ConcurrencyLevels "256,512,1000" -EndpointClasses "file-stream-large" -FrameworkJavaOptsAppend "-Dreactor.rust.static-file.inline-max-bytes=0 -Dreactor.rust.static-file.max-concurrent-streams=64"
```

For a stable release, also run a longer soak test after the benchmark and inspect RSS after idle. A good
benchmark without idle recovery evidence is not enough to rule out retention or leak behavior.

## Profile Comparison at c=1000

Run ids:

- `low-rss`: `container_20260530_154237`, full `64/256/512/1000` matrix.
- `balanced`: `container_20260530_165249`, c=1000 profile gate.
- `throughput`: `container_20260530_172402`, c=1000 profile gate.

Average c=1000 comparable endpoints:

| Profile | Endpoint | Rust-Java RPS | Spring Boot RPS | Ratio | Rust P99 | Spring P99 | Rust Max Mem |
|---------|----------|--------------:|----------------:|------:|---------:|-----------:|-------------:|
| low-rss | candidates | 7,995 | 2,601 | 3.07x | 579ms | 1.12s | 98 MiB |
| balanced | candidates | 6,731 | 1,355 | 5.18x | 587ms | 2.04s | 115 MiB |
| throughput | candidates | 6,606 | 2,177 | 3.12x | 840ms | 1.05s | 116 MiB |
| low-rss | echo | 6,912 | 2,193 | 3.76x | 824ms | 1.29s | 94 MiB |
| balanced | echo | 7,186 | 1,229 | 7.80x | 580ms | 1.86s | 117 MiB |
| throughput | echo | 7,045 | 1,943 | 4.14x | 634ms | 1.34s | 123 MiB |
| low-rss | heavy100 raw | 8,638 | 2,124 | 4.01x | 516ms | 1.25s | 97 MiB |
| balanced | heavy100 raw | 5,662 | 2,150 | 3.07x | 842ms | 1.71s | 103 MiB |
| throughput | heavy100 raw | 6,264 | 2,787 | 2.97x | 665ms | 1.52s | 108 MiB |
| low-rss | heavy100 dynamic DTO | 1,495 | 1,068 | 1.51x | 1.61s | 1.75s | 98 MiB |
| balanced | heavy100 dynamic DTO | 1,518 | 604 | 3.09x | 1.19s | 3.67s | 124 MiB |
| throughput | heavy100 dynamic DTO | 1,862 | 809 | 3.21x | 1.02s | 3.36s | 130 MiB |

Conclusion: `balanced` and `throughput` can improve specific tail-latency paths, especially `echo` and
dynamic DTO. They do not universally improve raw/static paths. Route-level tuning is still required;
do not globally raise memory and queues expecting every endpoint to improve.

# Benchmark Package

Primary path in this workspace is the container harness:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1
```

It builds both jars, builds local runtime images, starts both services as containers, runs the Rust `load-probe` from a separate benchmark container, and writes results to `benchmark/results/container_<timestamp>/summary.md`.

Default comparison:

- Runtime: `ibm-semeru-runtimes:open-21-jre-jammy`
- CPU: same limit for both services
- Runtime profile: `low-rss`
- Framework memory limit: `128m` in `low-rss`, `256m` in `throughput`
- Spring Boot memory limit: `512m`
- Endpoint classes:
  - `small-json`: common Rust-Java and Spring Boot endpoints, `GET /api/v1/candidates` and `POST /api/v1/echo`.
  - `dynamic-dto-json`: common dynamic object graph endpoint, Rust-Java `GET /api/v1/heavy/dto?items=100` vs Spring Boot `GET /api/v1/heavy?items=100`.
  - `direct-json-writer`: Rust-Java direct JSON writer endpoint, `GET /api/v1/heavy?items=100`. No Spring Boot ratio is calculated because this is a framework-specific zero-DTO path.
  - `rust-json-writer`: Rust-Java native Rust serializer endpoint, `GET /api/v1/heavy/rust?items=100`. No Spring Boot ratio is calculated.
  - `raw-json`: common precomputed response endpoint, `GET /api/v1/heavy/raw`.
  - `native-cache-json`: Rust-Java bounded native cache endpoint, `GET /api/v1/heavy/cache?items=100`.
  - `file-static`: Rust-Java file/static response endpoint, `GET /api/v1/export/file`.
- Metrics: RPS, average latency, p50/p90/p99, before/after HTTP status probe, socket errors, process RSS before/after, max sampled container memory
- Report sections: one section per endpoint class, plus Rust/Spring RPS ratio only where both targets expose the same class.

Shorter run:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 -Duration 10s -Warmup 3s -ConcurrencyLevels "64,256"
```

Full low-RSS profile:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 -RuntimeProfile low-rss -Duration 20s -Warmup 5s -ConcurrencyLevels "64,256,512,1000" -RepeatCount 3 -RandomizeOrder:$true -RandomSeed 20260425
```

Micro REST profile:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 `
  -RuntimeProfile micro-rest `
  -FrameworkJvmPreset cpu1 `
  -Duration 20s `
  -Warmup 5s `
  -ConcurrencyLevels "64,256,512,1000" `
  -RepeatCount 3 `
  -RandomSeed 20260425
```

Use `micro-rest` when the service is memory-first and controlled overload is acceptable. If a row's
HTTP status contains `503`, the reported RPS includes rejected requests; use the `200=` count and p99
together when judging useful throughput.

Framework-only JVM A/B test:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 `
  -FrameworkOnly `
  -RuntimeProfile micro-rest `
  -FrameworkJvmPreset cpu1 `
  -EndpointClasses "small-json-direct,dynamic-dto-json,direct-json-writer,producer-json,raw-json,native-cache-json" `
  -ConcurrencyLevels "64,256" `
  -Duration 5s `
  -Warmup 2s `
  -RepeatCount 3 `
  -RandomSeed 20260604
```

Use this for JVM preset decisions such as `cpu1` versus `cpu1-nojit`. It skips the Spring target and
writes `results.csv` plus `summary.md`. Do not use it for Rust/Spring product claims.

Production-only framework classpath check:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\micro_runtime_rss_matrix.ps1 `
  -SkipZookeeper `
  -OnlyDubbo `
  -DubboArtifactMode native-static `
  -FrameworkArtifactMode core-runtime `
  -JvmPreset cpu1 `
  -IdleSeconds 5
```

Use `-FrameworkArtifactMode core-runtime` for production-like consumer RSS checks. This runs the
sample consumer with `rust-java-rest-*-core-runtime.jar` plus user application classes, not
`target/classes`. The old `-FrameworkArtifactMode classes` path is a debug fallback only; it can
pull framework sample/example classes into the container classpath and inflate the measurement.

Latest local A/B smoke check, same native-static Dubbo consumer, `cpu1`, idle `5s`:

| Framework artifact mode | Ready RSS MiB | After first RPC RSS MiB | Docker Mem MiB ready | Image build context |
|-------------------------|--------------:|------------------------:|---------------------:|--------------------:|
| `core-runtime` | `57.27` | `58.28` | `30.00` | `5.01 MB` |
| `classes` | `58.75` | `59.88` | `31.48` | `9.99 MB` |

Interpretation: this is not a large JVM baseline breakthrough. It is a packaging and measurement
correctness gate: production-like runs should not include sample/example classes unless the app
actually uses them. RSS can stay close because unloaded classes do not always become live RSS, but
classpath size, accidental startup-index pollution, and user-facing artifact surface are cleaner.

Latest framework-only `cpu1` vs `cpu1-nojit` result, `micro-rest`, repeat `3`, duration `5s`,
concurrency `64/256`:

| Endpoint | C | cpu1 200 RPS | nojit 200 RPS | nojit p99 | RSS delta |
|----------|--:|-------------:|---------------:|----------:|----------:|
| `heavy100_direct_writer` | 64 | 2,718 | 106 | `1180ms` | `-18.12 MiB` |
| `heavy100_dynamic_dto` | 64 | 1,047 | 62 | `1410ms` | `-17.67 MiB` |
| `heavy100_producer_json` | 64 | 2,164 | 84 | `1530ms` | `-17.82 MiB` |
| `candidates_direct_bodyless` | 64 | 6,011 | 921 | `167.93ms` | `-18.15 MiB` |
| `heavy100_raw` | 64 | 7,722 | 8,340 | `23.10ms` | `-17.83 MiB` |

Interpretation: `cpu1-nojit` is not a production default for Java business or JSON writer routes.
It saves memory under warmed load, but Java-heavy endpoints lose useful throughput and p99 becomes
unacceptable. It is only a candidate for very low-call-rate services or raw/precomputed/static
response workloads where Java execution is minimal.

Micro REST plus route-admission check:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 `
  -RuntimeProfile micro-rest-plus `
  -Duration 20s `
  -Warmup 5s `
  -ConcurrencyLevels "256,512" `
  -EndpointClasses "direct-json-writer,dynamic-dto-json" `
  -RepeatCount 3 `
  -RandomSeed 20260603
```

Use `micro-rest-plus` as a benchmark/deployment recipe when `micro-rest` protects RSS but rejects too
much traffic on known heavy JSON routes. It keeps the Java runtime profile as `micro-rest`, raises
native `max-connections` to `768`, and applies measured route admission to the sample heavy routes:
direct writer `maxConcurrent=128, timeout=125ms`; dynamic DTO `maxConcurrent=64, timeout=125ms`.
Do not copy these route keys blindly into another application. Map them to your real route keys and
validate `p99`, `503%`, useful `200 RPS`, and RSS together.

Route admission matrix for one heavy route:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\route_admission_matrix.ps1 `
  -EndpointClass "producer-json" `
  -RouteAdmissionKey "get.api.v1.heavy.producer" `
  -ConcurrencyLevels "256,512" `
  -MaxConcurrentValues "64,80,96,128" `
  -QueueTimeoutMsValues "75,125,150" `
  -RuntimeProfile micro-rest-plus `
  -Duration 20s `
  -Warmup 5s `
  -RepeatCount 3 `
  -RandomSeed 20260603 `
  -SkipBuild
```

Use this before changing a production default. The report writes
`route_admission_matrix.csv`, `route_admission_matrix_aggregate.csv`, and
`route_admission_matrix.md`, including useful `200 RPS`, `p99`, `503%`, RSS, route
rejected/timeout counters, and JNI queue saturation. Prefer the lowest `maxConcurrent`
that reaches the useful `200 RPS` target with acceptable `p99` and RSS. Raising
`maxConcurrent` only to hide `503` is usually an anti-pattern because it can push the
same pressure into latency, heap churn, or downstream services.

Current measured sample recipe for `producer-json` at c256/c512 is
`maxConcurrent=80, queueTimeoutMs=150`. In the local release-gate matrix this kept c512
useful `200 RPS` high while reducing `503%` below 1% with RSS still around the
micro-rest-plus budget. Treat it as a starting point for the sample endpoint, not as a
universal default for every service.

Compare DTO graph vs producer/direct writer paths:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 `
  -RuntimeProfile micro-rest-plus `
  -EndpointClasses "dynamic-dto-json,producer-json,direct-json-writer" `
  -ConcurrencyLevels "256,512" `
  -Duration 20s `
  -Warmup 5s `
  -RepeatCount 3 `
  -RandomSeed 20260603
```

Low-RSS with explicit small-pod OpenJ9 worker sizing:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 `
  -RuntimeProfile low-rss `
  -FrameworkJvmPreset cpu1 `
  -Duration 20s `
  -Warmup 5s `
  -ConcurrencyLevels "64,256,512,1000" `
  -RepeatCount 3 `
  -RandomSeed 20260425
```

`FrameworkJvmPreset` values:

| Preset | Adds | Use when |
|--------|------|----------|
| `current` | Nothing beyond the selected runtime profile | Baseline comparison and previous benchmark continuity |
| `cpu1` | `-Xss256k -XX:ActiveProcessorCount=1` | Small pods where RSS/thread count matters but JIT should stay enabled |
| `cpu1-xss192` | `-Xss192k -XX:ActiveProcessorCount=1` | Stack-size A/B test for small pods; validate with your call depth |
| `cpu1-xss160` | `-Xss160k -XX:ActiveProcessorCount=1` | Stack-size A/B test when every MiB matters |
| `cpu1-xss128` | `-Xss128k -XX:ActiveProcessorCount=1` | Aggressive stack-size A/B test only after smoke tests pass |
| `cpu1-nojit` | `-Xss256k -XX:ActiveProcessorCount=1 -Xnojit` | Very low traffic services only; not a general throughput default |
| `cpu1-nojit-xss160` | `-Xss160k -XX:ActiveProcessorCount=1 -Xnojit` | Idle-service RSS experiment; not a throughput default |
| `cpu1-nojit-xss128` | `-Xss128k -XX:ActiveProcessorCount=1 -Xnojit` | Most aggressive idle-service RSS experiment; validate stack depth and latency |

Short local trade-off check, low-rss, c=64/256, duration `5s`, repeat `1`:

| Endpoint | C | `cpu1` RPS delta | P99 change | Max memory change |
|----------|--:|-----------------:|------------|------------------:|
| `candidates` | 64 | +16.1% | `30.34ms -> 35.87ms` | +2.68 MiB |
| `candidates` | 256 | +16.5% | `91.23ms -> 79.00ms` | +0.28 MiB |
| `candidates_direct_bodyless` | 64 | -19.5% | `36.55ms -> 35.23ms` | +2.03 MiB |
| `candidates_direct_bodyless` | 256 | +7.1% | `72.55ms -> 60.43ms` | +0.87 MiB |
| `heavy100_dynamic_dto` | 64 | +33.2% | `83.34ms -> 66.49ms` | +2.42 MiB |
| `heavy100_dynamic_dto` | 256 | +5.9% | `246.33ms -> 206.06ms` | +1.94 MiB |
| `heavy100_direct_writer` | 64 | +31.2% | `60.95ms -> 58.64ms` | +0.58 MiB |
| `heavy100_direct_writer` | 256 | -20.5% | `241.76ms -> 314.75ms` | +3.87 MiB |
| `heavy100_raw` | 64 | -12.3% | `15.48ms -> 20.74ms` | +0.58 MiB |
| `heavy100_raw` | 256 | +48.7% | `89.14ms -> 54.33ms` | +1.78 MiB |

Interpretation: `cpu1` is promising for small-pod RSS and does not show a universal latency penalty,
but it is not a blind replacement for all profiles. Repeat `>=3` before making it the stable default.

Short local `micro-rest + cpu1` check, same endpoint set:

| Endpoint | C | RPS | P99 | HTTP status | Max memory |
|----------|--:|----:|-----|-------------|-----------:|
| `candidates` | 64 | 8,989 | `24.15ms` | `200=45059` | 64.25 MiB |
| `candidates` | 256 | 8,614 | `84.33ms` | `200=41643, 503=1742` | 57.73 MiB |
| `candidates_direct_bodyless` | 64 | 10,688 | `17.78ms` | `200=53552` | 51.57 MiB |
| `candidates_direct_bodyless` | 256 | 9,763 | `89.17ms` | `200=48151, 503=957` | 58.04 MiB |
| `heavy100_raw` | 64 | 7,852 | `22.97ms` | `200=39382` | 54.89 MiB |
| `heavy100_raw` | 256 | 7,489 | `74.84ms` | `200=37766` | 52.23 MiB |
| `heavy100_direct_writer` | 64 | 3,141 | `34.17ms` | `200=15787` | 53.91 MiB |
| `heavy100_direct_writer` | 256 | 4,106 | `380.73ms` | `200=3426, 503=17368` | 43.53 MiB |
| `heavy100_dynamic_dto` | 64 | 1,319 | `107.61ms` | `200=6645` | 67.71 MiB |
| `heavy100_dynamic_dto` | 256 | 7,763 | `274.09ms` | `200=3964, 503=35518` | 51.44 MiB |

Interpretation: `micro-rest` is a real memory-first profile, not a throughput profile. Small/raw
routes stay useful at low memory. Heavy dynamic/direct routes at high concurrency need `low-rss`,
`balanced`, or route-specific bulkhead tuning if `503` is not acceptable.

Balanced profile:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 -RuntimeProfile balanced -Duration 20s -Warmup 5s -ConcurrencyLevels "64,256,512,1000" -RepeatCount 3 -RandomSeed 20260425
```

Run only selected endpoint classes:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 -RuntimeProfile low-rss -Duration 20s -Warmup 5s -ConcurrencyLevels "64,256,512,1000" -RepeatCount 3 -EndpointClasses "dynamic-dto-json,direct-json-writer,raw-json"
```

Use this when validating a specific optimization. Do not compare `direct-json-writer`, `rust-json-writer`, `native-cache-json`, or `file-static` as if they were generic Spring Boot equivalents; these are explicit optimized paths with different application contracts.

## Micro Runtime RSS Matrix

Use this when the target is a very small REST service or a REST service that always has the native
Dubbo consumer enabled. This matrix does not compare Spring Boot; it checks whether the Rust-Java
runtime can stay inside a small Kubernetes memory budget.

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\micro_runtime_rss_matrix.ps1 `
  -JvmPreset cpu1 -SkipZookeeper
```

To isolate JVM baseline choices across repeated runs:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\jvm_baseline_rss_matrix.ps1 `
  -Repeats 3 `
  -JvmPresets "cpu1,cpu1-xss128,cpu1-nojit,cpu1-nojit-xss128" `
  -DubboArtifactMode native-static
```

JVM presets:

| Preset | Use when | Trade-off |
|--------|----------|-----------|
| `current` | You want to compare against the previous OpenJ9 baseline | Higher JVM worker/thread footprint on hosts with many CPUs |
| `cpu1` | Small pod, low-RSS service, JIT still enabled | Lower RSS/thread count; do not use for CPU-heavy throughput without measuring |
| `cpu1-xss192` / `cpu1-xss160` / `cpu1-xss128` | You need to prove whether smaller thread stacks help this service | The latest local matrix showed little RSS benefit; validate stack depth before production |
| `cpu1-nojit` | Very low traffic service where RSS matters more than CPU throughput | Lowest RSS in this matrix; slower dynamic Java execution |
| `cpu1-nojit-xss160` / `cpu1-nojit-xss128` | You want to combine no-JIT with smaller stacks | Treat as idle-service experiments; not defaults |

Latest local micro-RSS result:

| Scenario | Preset | Docker Mem Ready | smaps RSS Ready | Threads Ready |
|----------|--------|-----------------:|----------------:|--------------:|
| `micro-rest` | `current` | 40.08 MiB | 67.94 MiB | 30 |
| `micro-rest` | `cpu1` | 32.32 MiB | 60.02 MiB | 22 |
| `micro-rest` | `cpu1-nojit` | 31.55 MiB | 52.45 MiB | 20 |
| `micro-dubbo-static` | `current` | 35.85 MiB | 63.75 MiB | 30 |
| `micro-dubbo-static` | `cpu1` | 30.51 MiB | 57.73 MiB | 22 |
| `micro-dubbo-static` | `cpu1-nojit` | 29.25 MiB | 49.95 MiB | 20 |
| `micro-dubbo-zk` | `current` | 36.29 MiB | 64.50 MiB | 32 |
| `micro-dubbo-zk` | `cpu1` | 31.00 MiB | 58.50 MiB | 24 |

Interpretation: `cpu1` is the production-safe low-RSS JVM preset for small pods. `cpu1-nojit` is an
idle-service option, not a throughput default. If the service does database/RPC work under real load,
run the same matrix together with the normal latency benchmark before choosing it.

Latest optional-surface-off check, `micro-rest`, `cpu1`, filtered sample startup index, repeat `1`:

| Scenario | Phase | smaps RSS | Docker Mem | Threads |
|----------|-------|----------:|-----------:|--------:|
| `micro-rest` | ready | 59.11 MiB | 32.20 MiB | 22 |
| `micro-dubbo-static` | ready | 58.39 MiB | 30.85 MiB | 22 |

Interpretation: disabling optional WebSocket/static registration and filtering sample startup index
cleans the low-RSS runtime surface and removes false gate warnings. It does not produce a dramatic
RSS drop by itself; the remaining pressure is JVM baseline, class metadata, JIT/runtime state, and
Java-heavy route allocation.

Latest JVM baseline isolation, native-static Dubbo artifact, static provider, repeat `1`, idle `10s`:

| Preset | smaps RSS Ready | smaps RSS Idle | PSS Idle | Private Dirty Idle | Threads Idle |
|--------|----------------:|---------------:|---------:|-------------------:|-------------:|
| `cpu1` | 57.21 MiB | 58.23 MiB | 57.06 MiB | 28.62 MiB | 23 |
| `cpu1-xss160` | 58.20 MiB | 59.13 MiB | 57.94 MiB | 29.55 MiB | 23 |
| `cpu1-xss128` | 57.19 MiB | 58.11 MiB | 56.93 MiB | 29.56 MiB | 23 |
| `cpu1-nojit` | 51.07 MiB | 51.50 MiB | 50.36 MiB | 29.14 MiB | 21 |
| `cpu1-nojit-xss128` | 51.09 MiB | 51.51 MiB | 50.38 MiB | 29.14 MiB | 21 |

Interpretation: on this host, smaller thread stacks alone did not materially reduce RSS. The visible
RSS drop came from `-Xnojit`, which is a CPU/latency trade-off. Use it only for low-call-rate services
or explicit idle-RSS targets, then run the normal p99 benchmark before deploying it.

Full throughput profile:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 -RuntimeProfile throughput -Duration 20s -Warmup 5s -ConcurrencyLevels "64,256,512,1000" -RepeatCount 3 -RandomizeOrder:$true -RandomSeed 20260425
```

When calling through `powershell -File`, pass multiple concurrency values as one string if your shell treats commas unexpectedly:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 -ConcurrencyLevels "64,256,512,1000"
```

The older `production_benchmark.sh` is still useful when both services are already running and `wrk` is installed locally.

Do not compare results while either application prints per-request logs. Console I/O dominates p99 and invalidates the benchmark.

## Latest Low-RSS Gate

Run id: `container_20260530_154237`

Command:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 -RuntimeProfile low-rss -Duration 10s -Warmup 2s -ConcurrencyLevels "64,256,512,1000" -RepeatCount 3 -RandomSeed 20260530 -EndpointClasses "small-json,dynamic-dto-json,direct-json-writer,raw-json,file-static"
```

Average c=1000 comparable endpoints:

| Endpoint | Rust-Java RPS | Spring Boot RPS | Ratio | Rust P99 | Spring P99 | Rust Max Mem | Spring Max Mem |
|----------|--------------:|----------------:|------:|---------:|-----------:|-------------:|---------------:|
| candidates | 7,995 | 2,601 | 3.07x | 579ms | 1.12s | 98 MiB | 313 MiB |
| echo | 6,912 | 2,193 | 3.76x | 824ms | 1.29s | 95 MiB | 295 MiB |
| heavy100 raw | 8,638 | 2,124 | 4.01x | 516ms | 1.25s | 97 MiB | 303 MiB |
| heavy100 dynamic DTO | 1,495 | 1,068 | 1.51x | 1.61s | 1.75s | 98 MiB | 303 MiB |

Interpretation: low-RSS protects memory well, but dynamic DTO and file/static high-concurrency tail latency still need route-specific throughput tuning.

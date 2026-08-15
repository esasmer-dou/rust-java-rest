# Benchmark Package

[English](README.md) | [Türkçe](README.tr.md) | [Framework Guide](../README.md) | [Examples](../examples/README.md)

This directory is the reproducible performance-evidence archive. It is not the quick-start guide and
its historical sections describe the exact source line named in each heading.

## Choose The Right Gate

| Question | Use |
| --- | --- |
| Did a framework change improve memory without hurting RPS/p99? | `paired_image_gate.ps1` |
| What contributes to Linux RSS and anonymous memory? | `linux_smaps_breakdown.ps1` |
| Does idle native trim reclaim memory safely? | `anon_evidence_gate.ps1` with trim A/B |
| Does a temporary Glowroot profile release every owned resource? | `profile-switch/RestProfileSwitchProbe.java` |
| Which route admission values maximize useful `200` RPS? | `route_admission_matrix.ps1` |
| How do response paths differ under the same load? | `container_benchmark.ps1` with explicit endpoint classes |

Do not copy a historical result into a product claim. Re-run the matching gate on the current
source, native binaries, JVM, container limits, endpoint mix, and provider/database topology.

The stable `4.5.1` source tree uses REST ABI `29`, Dubbo ABI `7`, Redis ABI `6`, and Glowroot ABI
`3`. Before comparing two builds, rebuild the native artifact from the same source revision. A
benchmark that uses an older DLL/SO is invalid even if the application starts.

## Runtime Telemetry Profile Release Gate

`profile-switch/RestProfileSwitchProbe.java` starts the real Hyper server, runs `100` temporary
`full` profile windows, and returns through `restoreConfiguredProfile()`. It rejects retained active
or retired profile bytes, a pending transition, a remaining Rust-owned JVM probe, or a non-zero JNI
global-reference count. The probe
halts its dedicated benchmark process after the server stop because process-lifetime JNI workers are
not application profile resources.

Run it with an exact-source ABI `29`/`3` Linux binary. The lifecycle result is valid only when the
binary and Java classes come from the same checkout. Use fresh telemetry-off/on processes for RSS
attribution because Linux `malloc_trim(0)` can return unrelated free allocator pages.

## How To Read A Result

Always report these signals together:

| Signal | Why it matters |
| --- | --- |
| Useful `200` RPS | Successful business capacity, excluding rejected requests |
| p50/p95/p99 | Typical and tail latency |
| `503` ratio | Bounded overload behavior |
| Container `memory.current` | Kubernetes-relevant charged memory |
| Container `anon` | Heap, JVM/native runtime, thread, and allocator pressure |
| Process RSS / `Private_Dirty` | Mapping and process-private memory evidence |
| JNI queue wait and in-flight bytes | Framework backpressure pressure |

Do not rank a profile by RPS alone. A larger queue may hide `503` while increasing p99 and retained
memory. Use repeat `>=3`, randomized order, the same warmup, the same container limits, and the same
endpoint mix.

Use `micro-rest` as the memory-first baseline. Use `micro-rest-plus` only for measured heavy routes.
Keep benchmark-only routes out of production route reports. The full sample process is not the RSS
baseline for a minimal service.

## Paired Image Gate

Use `paired_image_gate.ps1` when a framework change must prove that it does not trade throughput or
tail latency for memory. The runner alternates the outer positions on every cycle:

- Odd cycles: baseline, candidate, candidate, baseline.
- Even cycles: candidate, baseline, baseline, candidate.

This order prevents page cache, host temperature, and scheduler drift from consistently favouring
one image. Always use an even repeat count so both images occupy outer and middle positions equally.
Use `PairRepeats = 2` for local engineering evidence and `PairRepeats >= 4` for a release decision.
`-FailOnGate` rejects an odd repeat count. Runs shorter than five seconds are diagnostic only. Judge
useful `200` RPS, p99, `503`, container memory, and RSS together.

The paired gate builds the load-runner image once and probes applications through the Docker
benchmark network. Normal source builds use clean Maven outputs. This prevents a local host port or
an older shaded sample JAR from contaminating the comparison.

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\paired_image_gate.ps1 `
  -BaselineImage rust-java-rest:baseline `
  -CandidateImage rust-java-rest:candidate `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json-direct,direct-json-writer,dynamic-producer-json,raw-json" `
  -Duration 10s `
  -Warmup 3s `
  -PairRepeats 4 `
  -CalibrationCycles 1 `
  -PlanPreWarm `
  -PlanPreWarmDuration 10s `
  -FailOnGate
```

`PlanPreWarmDuration` runs each selected route before measurement. Use at least `10s` for release
evidence on OpenJ9 Java-heavy routes. This keeps a faster-starting candidate from being measured at
a younger JIT compilation state than the baseline. The selected value is stored in `metadata.json`.

`CalibrationCycles 1` runs one unrecorded baseline/candidate pass before the balanced measurements.
Use it for release evidence when Docker Desktop page cache, image loading, or the first host cycle is
visibly noisier than later cycles. Calibration results stay under `runs/cycle-00-*` but are excluded
from the comparison.

### Resident crossover and startup regression gates

Use the resident crossover gate for a surgical comparison of generated invocation, echo parsing, or
the native-static control route. Both images stay resident during a phase. The second phase swaps the
images between CPU slots. Cooldowns reduce thermal drift, and process RSS is read separately from
container memory. The effect size uses crossover-pooled candidate/baseline medians. Same-phase
candidate/baseline delta spread is the stability check. The unnormalized report remains under
`comparison/absolute` for diagnostics, but phase-level host drift does not inflate the release
stability statistic.

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\resident_crossover_gate.ps1 `
  -BaselineImage rust-java-rest:baseline `
  -CandidateImage rust-java-rest:candidate `
  -Concurrency 64 `
  -Duration 15s `
  -EndpointClasses "annotated-generated-json,echo-parse,small-json-direct" `
  -RepeatCountPerSlot 3 `
  -SlotACpuSet 2 `
  -SlotBCpuSet 3 `
  -RunnerCpuSet 4-7
```

Use `image_startup_gate.ps1` for startup. It reports framework internal-ready and host HTTP-ready
separately. Startup order is balanced and the decision uses same-cycle paired deltas. A noisy
baseline cannot hide an unstable candidate; candidate CV remains a mandatory gate.

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\image_startup_gate.ps1 `
  -BaselineImage rust-java-rest:baseline `
  -CandidateImage rust-java-rest:candidate `
  -RepeatCount 6 `
  -CpuSet 2
```

`small-json-direct` is a native-static control. Its Java invocation count must remain zero. If that
route is unstable while both images contain the same native binary, classify the run as host noise
and repeat it on a quiet Linux runner. Do not tune Java code to compensate for a bypassed route.

### Generated response writer gate

The build-time route plan binds an already registered generated response writer directly. It does
not initialize the generic DSL-JSON serializer to perform this lookup. Writer registration after
route compilation does not mutate a published route descriptor; register custom writers before
route compilation.

The 2026-08-10 paired c64 gate used four balanced cycles, one calibration cycle, a 10-second route
pre-warm, and a 15-second measurement. Compared with the lazy writer proxy, direct AOT binding
produced `+1.73%` useful `200` RPS, `-1.15%` p99, zero `503`, and `-1.29 MiB` average sampled peak
container memory. A separate 30-second load plus 30-second idle A/B measured `-1.24 MiB` cgroup
memory, `-1.19 MiB` anon, and `-0.52 MiB` process RSS after idle. The earlier lazy proxy has since
been removed; explicit writers now have to be available when routes are compiled.

## Historical v3.2.2 Release Gate Snapshot

Latest release-gate artefacts:

- Route matrix: `benchmark/results/release_gate_routes_20260607_151500`
- Anon evidence: `benchmark/results/anon_gate_minimal_20260607_154000`

Route matrix command shape:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 `
  -RuntimeProfile micro-rest-plus `
  -FrameworkJvmPreset cpu1 `
  -FrameworkOnly `
  -SkipBuild `
  -PlanPreWarm `
  -Duration 10s `
  -Warmup 3s `
  -ConcurrencyLevels "64,256,512,1000" `
  -RepeatCount 3 `
  -EndpointClasses "dynamic-producer-json,dynamic-dto-json,direct-json-writer,native-cache-json,raw-json"
```

Anon evidence command shape:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\anon_evidence_gate.ps1 `
  -AppMode minimal `
  -Profiles "micro-rest,micro-rest-plus,micro-dubbo" `
  -ConcurrencyValues "64,256,512" `
  -DurationSeconds 5 `
  -IdleSeconds 3 `
  -FinalIdleSeconds 12 `
  -TrimFinalIdleSeconds 95 `
  -TrimFinalIdleSnapshotSeconds "35,95"
```

Current gate decision:

| Signal | Result |
|--------|-------:|
| Production heavy JSON object-graph routes | 0 |
| Benchmark-only heavy JSON object-graph routes | 1 |
| `micro-rest` final current / anon | 66.71 MiB / 50.18 MiB |
| `micro-rest-plus` final current / anon | 66.81 MiB / 50.47 MiB |
| Conservative trim current / anon | 46.97 MiB / 31.87 MiB |

Use these as release evidence for route isolation and memory attribution. Do not read them as a
universal 200-only c1000 throughput claim. Heavy JSON still needs producer/direct/raw/native response
paths and route budgets; low-memory profiles are allowed to return bounded `503` under overload.

## OpenJ9 Runtime Surface Gate

`smaps RSS` includes clean pages mapped from OpenJ9, libc, libstdc++, the JDK module image, and other
files. Linux can reclaim those clean pages. Therefore a `73 MiB` process `smaps RSS` value does not
mean that the pod owns `73 MiB` of anonymous memory. Use all three signals together:

| Signal | What it answers |
|--------|-----------------|
| `smaps RSS` | Which resident mappings are currently present in the process? |
| `Private_Dirty` | How many process-private pages have actually been modified? |
| cgroup `memory.current` and `anon` | How much memory is charged to the container and how much is anonymous? |

The application-specific OpenJ9 `jlink` image is defined in
`benchmark/docker/minimal-production-jlink.Dockerfile`. Its current module set is deliberately small
but complete for the minimal REST probe:

```text
java.base,java.logging,java.management,java.sql,jdk.charsets,jdk.crypto.ec,jdk.unsupported
```

`java.sql` is required even when the sample does not use a database directly. DSL-JSON registers Java
time/SQL converters during initialization. The image build runs `JlinkRuntimeSmoke`; a missing runtime
module fails the build instead of producing an image that returns HTTP 500 later.

Build the diagnostic images after producing the core-runtime JAR:

```powershell
docker build -t rust-java-rest:openj9-jlink-zip0 `
  -f benchmark/docker/minimal-production-jlink.Dockerfile `
  --build-arg JLINK_COMPRESS=zip-0 .

docker build -t rust-java-rest:openj9-jlink-zip6 `
  -f benchmark/docker/minimal-production-jlink.Dockerfile `
  --build-arg JLINK_COMPRESS=zip-6 .
```

Latest normalized minimal-app evidence on this host:

| Runtime | Modules | Image | Final smaps RSS | Final cgroup current | Final cgroup anon |
|---------|--------:|------:|----------------:|---------------------:|------------------:|
| Full Semeru OpenJ9 JRE | 56 | 301.4 MiB | 70.48 MiB | 44.97 MiB | 38.23 MiB |
| App-specific `jlink`, `zip-0` | 9 | 169.0 MiB | 82.51 MiB | 44.78 MiB | 37.80 MiB |
| App-specific `jlink`, `zip-6` | 9 | 147.1 MiB | 75.70 MiB | 43.59 MiB | 37.11 MiB |

The result is intentionally not presented as a runtime-memory win. `zip-6` reduced the container image
by about 51% and cgroup anon by about 1.1 MiB, but increased process `smaps RSS` by about 5.2 MiB due to
clean module-file mappings. More importantly, the normalized paired gate failed: at c64 the small
direct JSON route had 16.37% lower useful RPS and 33.59% higher p99. The full OpenJ9 JRE remains the
default for latency-sensitive services. Treat `jlink` as an image/startup-surface option and promote it
only after the service's own endpoint matrix passes repeat `>=3` at c64/c256/c512.

Do not remove `libj9vm`, `libj9gc`, `libj9jit`, libc, or libstdc++ manually. They are runtime components,
not optional Java modules. Do not use `-Xnojit` as a general memory fix; previous gates showed a large
throughput loss on Java-heavy routes. If the goal is lower pod RSS without losing RPS, continue with
anonymous allocation, object-graph, thread/pool, and idle allocator-retention work instead.

Removing the `OpenJCEPlus` provider and Semeru crypto libraries was also tested and rejected. It cut
roughly 1.25 MiB from the system-native mapping category, but did not reduce final cgroup memory in the
smaps run. In the paired gate, small direct JSON useful RPS fell by 22.94% at c64 and 29.74% at c256;
p99 increased by 32% and 46.41%. It also removes an explicit Java security provider and is unsafe for
applications that require it, TLS, FIPS behavior, or provider-specific cryptography.

Primary path in this workspace is the container harness:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1
```

## Current Dubbo Sample Benchmarks

The scripts under `benchmark/dubbo_overhead` use the current sample projects rather than the
framework core JAR:

- consumer: `rest-sample-dubbo-consumer`, Maven profile `native-static-consumer`
- provider: `rest-sample-dubbo-provider`, Maven profile `catalog-static-provider`
- RPC endpoint: `GET /api/v1/catalog/nested`
- control endpoint in the same Dubbo-enabled process: `GET /app/health`

Run a short native-static consumer matrix:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\dubbo_overhead\run_dubbo_overhead.ps1 `
  -ConcurrencyValues "64,256,512" `
  -RuntimeProfile micro-dubbo `
  -DurationSeconds 8
```

Run the repeat gate or connection-pool matrix:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\dubbo_overhead\run_dubbo_overhead_repeat.ps1 `
  -RepeatCount 3 `
  -RuntimeProfile balanced-dubbo

powershell -ExecutionPolicy Bypass -File .\benchmark\dubbo_overhead\run_native_pool_benchmark.ps1 `
  -PoolSizes "1,2,4,8,16" `
  -ConcurrencyValues "64,256,512"
```

The health route is not a Dubbo-disabled baseline. It is only a non-RPC control route inside the
same process. Do not subtract the two endpoints and label the result as pure Dubbo overhead; their
workloads are different.

It builds both jars, builds local runtime images, starts both services as containers, runs the Rust `load-probe` from a separate benchmark container, and writes results to `benchmark/results/container_<timestamp>/summary.md`.

`results.csv` is checkpointed atomically after every completed case. If a long matrix is interrupted,
the completed rows remain available. `summary.md` is written only after the complete matrix finishes;
if it is missing, treat the run as incomplete and rerun the missing partition instead of presenting the
partial rows as a passed gate.

Default comparison:

- Runtime: `ibm-semeru-runtimes:open-21-jre-jammy`
- CPU: same limit for both services
- Runtime profile: `low-rss`
- Framework memory limit: `128m` in `low-rss`, `256m` in `throughput`
- Spring Boot memory limit: `512m`
- Endpoint classes:
  - `small-json`: common Rust-Java and Spring Boot endpoints, `GET /api/v1/candidates` and `POST /api/v1/echo`.
  - `annotated-generated-json`: declarative Java handler with generated route invocation and generated response writer, `GET /users/search?name=load&page=1`.
  - `dynamic-producer-json`: optimized DTO-shaped heavy JSON, Rust-Java `GET /api/v1/heavy/dto?items=100`. This is the recommended hot-route replacement once a DTO graph becomes too expensive.
  - `dynamic-dto-json`: benchmark-only legacy Java DTO graph endpoint, Rust-Java `GET /api/v1/heavy/dto/legacy?items=100` vs Spring Boot `GET /api/v1/heavy?items=100`. Use it only to quantify object-graph cost.
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

Latest JNI queue tuning, indexed minimal app, `small-direct`, c256/c512, repeat `3`:

| Case | Workers | Queue | Max Conn | c256 503 | c256 p99 | c512 503 | c512 p99 | Final current avg |
|------|--------:|------:|---------:|---------:|---------:|---------:|---------:|------------------:|
| Default `micro-rest` | 1 | 128 | 512 | 19.720% | 90.67 ms | 8.635% | 163.22 ms | 52.807 MiB |
| Candidate | 1 | 256 | 512 | 0.000% | 159.90 ms | 1.349% | 186.28 ms | 52.529 MiB |
| Small-direct recipe | 1 | 512 | 512 | 0.000% | 85.37 ms | 0.607% | 171.45 ms | 54.738 MiB |

Decision: `micro-rest` keeps one JNI worker, `queue-capacity=128`, and `max-connections=512`.
Queue `512` is a measured small/direct JSON recipe, not the default. It removes the c256 queue-full
rejection in the focused gate and materially reduces c512 503, with roughly a 2 MiB final
cgroup-current cost in that run. The full clean-index endpoint matrix rejected it as a global default:
direct-heavy, producer-heavy, dynamic-producer, and raw-heavy lost RPS and regressed p99/503.

Full clean-index gate signal, queue `512` versus default queue `128` at c512:

| Endpoint class | RPS change | p99 change | 503 change | Decision |
|----------------|-----------:|-----------:|-----------:|----------|
| `small-direct` | `-22.46%` | `+40.39%` | `9.51% -> 0.39%` | Useful only when 503 removal is worth the p99/RPS trade-off |
| `raw-heavy` | `-24.45%` | `+80.66%` | `0.36% -> 0.60%` | Reject as default |
| `direct-heavy` | `-15.05%` | `+20.80%` | `18.32% -> 27.65%` | Reject as default |
| `producer-heavy` | `-27.58%` | `+73.25%` | `7.84% -> 14.29%` | Reject as default |
| `dynamic-producer` | `-25.34%` | `+48.06%` | `8.29% -> 30.00%` | Reject as default |

Native static route alternative for immutable small-direct pressure:

```java
private static final RawResponse CANDIDATES_DIRECT =
        RawResponse.registeredJson(precomputedCandidateBytes());

@RustRoute(method = "GET", path = "/api/v1/candidates/direct",
        requestType = Void.class, responseType = RawResponse.class)
@NativeStaticRoute
public RawResponse candidatesDirect() {
    return CANDIDATES_DIRECT;
}
```

This is the correct path when the response is truly immutable or precomputed until restart. The route
is registered once at startup and served by Rust without Java handler invocation or JNI queue work.
For dynamic Java business logic, keep the direct writer/producer writer route and treat the JNI lane
as an explicit service-specific experiment only.

Focused JNI-admission matrix, `micro-rest`, global queue `128`, `small-json-direct`, c256/c512,
repeat `2`:

| maxConcurrent | queueTimeoutMs | c256 useful 200 RPS | c256 p99 | c256 503 | c512 useful 200 RPS | c512 p99 | c512 503 | JNI queue full |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `96` | `75` | `12490.74` | `58.02 ms` | `0.00%` | `8336.34` | `203.74 ms` | `0.64%` | `0` |
| `96` | `125` | `5739.70` | `141.31 ms` | `0.00%` | `5767.07` | `385.05 ms` | `0.86%` | `0` |
| `128` | `75` | `4033.92` | `177.98 ms` | `0.00%` | `3906.23` | `477.40 ms` | `3.73%` | `0` |
| `160` | `125` | `11061.64` | `56.10 ms` | `2.86%` | `5850.02` | `301.33 ms` | `2.22%` | non-zero |

Decision: do not make a local JNI lane the `micro-rest` default. It can reduce `small-direct` 503, but
the full endpoint matrix must be run for each service because the extra priority JNI worker changes
the pod scheduling profile. The bundled sample keeps the route bodyless/direct and leaves the lane
off by default.
Remaining c512 `503` is controlled overload, not a stale-DLL or JNI worker failure signal.

Follow-up `max-connections` check, same endpoint and repeat count:

| Case | Workers | Queue | Max Conn | c256 RPS | c256 p99 | c512 503 | c512 p99 | Final current avg |
|------|--------:|------:|---------:|---------:|---------:|---------:|---------:|------------------:|
| Small-direct recipe | 1 | 512 | 512 | 12070.24 | 73.51 ms | 0.662% | 153.50 ms | 54.174 MiB |
| Higher connection cap | 1 | 512 | 768 | 9020.22 | 98.21 ms | 0.072% | 142.77 ms | 56.939 MiB |

Decision: keep `max-connections=512` for `micro-rest`. Raising it to `768` helps c512 rejection, but
costs RSS and worsens the c256 operating point. Use `768` only as an explicit recipe when the pod is
sized for the extra memory and c512/c1000 productive throughput matters more than the smaller-pod
default.

JVM thread stack A/B:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\xss_anon_matrix.ps1 `
  -RuntimeProfile micro-rest `
  -AppMode minimal `
  -XssValues "256k,192k,160k,128k" `
  -ConcurrencyValues "512" `
  -DurationSeconds 5 `
  -IdleSeconds 2 `
  -FinalIdleSeconds 20 `
  -SkipBuild
```

Use this only for production-like stack sizing decisions. It runs the minimal app through the same
route smoke/load plan for each `-Xss` value, records Linux cgroup anon/current memory, checks logs
for `StackOverflowError`/OOM/native-thread failures, and treats `503` as route-admission overload
rather than a stack failure.

Latest local `micro-rest`, indexed minimal app, c512 run:

| Xss | Status | Baseline anon | Final anon | Peak anon | Final current | Peak current | Stack budget | Avg RPS | Avg p99 | Max p99 | 503 rate | 500 |
|-----|--------|--------------:|-----------:|----------:|--------------:|-------------:|-------------:|--------:|--------:|--------:|---------:|----:|
| `256k` | WARN | 26.379 MiB | 43.512 MiB | 43.512 MiB | 66.777 MiB | 74.500 MiB | 5.500 MiB | 9155.03 | 144.99 ms | 214.58 ms | 8.352% | 0 |
| `192k` | WARN | 26.379 MiB | 46.113 MiB | 46.117 MiB | 66.523 MiB | 68.695 MiB | 4.125 MiB | 10435.12 | 132.34 ms | 168.26 ms | 9.195% | 0 |
| `160k` | WARN | 26.574 MiB | 50.211 MiB | 50.211 MiB | 65.660 MiB | 66.105 MiB | 3.438 MiB | 10354.90 | 140.58 ms | 191.99 ms | 9.311% | 0 |
| `128k` | WARN | 26.430 MiB | 49.082 MiB | 49.094 MiB | 57.219 MiB | 57.484 MiB | 2.750 MiB | 10540.19 | 140.96 ms | 186.66 ms | 8.064% | 0 |

Interpretation: no `StackOverflowError`, OOM, native-thread error, or 500 was observed in this run.
All rows are `WARN` only because c512 intentionally reaches route admission and returns some `503`.
Lowering `-Xss` reduces theoretical stack budget, but it did not produce a stable dramatic anon
drop; in the indexed run, `256k` had the lowest final anon. Keep `-Xss256k` as the default. Treat
`192k` or `128k` as service-specific experiments only after the real service's deepest
route/RPC/JDBC call stack passes the same smoke test.

Framework-only JVM A/B test:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 `
  -FrameworkOnly `
  -RuntimeProfile micro-rest `
  -FrameworkJvmPreset cpu1 `
  -EndpointClasses "small-json-direct,dynamic-producer-json,dynamic-dto-json,direct-json-writer,producer-json,raw-json,native-cache-json" `
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

## Minimal Production RSS Attribution

For release/package documentation and pod-sizing decisions, the default RSS benchmark should be the
minimal production app, not the bundled framework sample app.

Use sample mode when you want to test framework demo routes:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\linux_smaps_breakdown.ps1 `
  -AppMode sample `
  -RuntimeProfile micro-rest `
  -ConcurrencyValues 64,256 `
  -DurationSeconds 4 `
  -IdleSeconds 3 `
  -FinalIdleSeconds 6
```

Use minimal mode when you want to measure a clean production classpath:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\linux_smaps_breakdown.ps1 `
  -AppMode minimal `
  -RuntimeProfile micro-rest `
  -ConcurrencyValues 64,256 `
  -DurationSeconds 4 `
  -IdleSeconds 3 `
  -FinalIdleSeconds 6
```

The minimal production benchmark image now builds the same startup index shape expected from a real
small application. Its Docker build compiles `com.reactor.benchmark.minimal` with
`ReactorStartupProcessor`, producing:

- `/app/classes/META-INF/reactor/components.idx`
- `/app/classes/META-INF/reactor/routes.idx`
- `/app/classes/META-INF/reactor/properties.idx`

The handler is registered explicitly, so it does not require a DI component scan. The route index is
still generated and validated. This matters for measurement hygiene: the benchmark should not pay
for a startup fallback that production apps are expected to avoid.

## Anon Evidence Gate

Use this gate when the question is not "what is the RSS number?" but "where does the anonymous
memory come from, and what should we attack next?" It runs the minimal production app through the
same Linux smaps attribution flow for the memory-first profiles, then runs a conservative native
trim A/B and captures optional OpenJ9 javacore/native evidence.

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\anon_evidence_gate.ps1 `
  -AppMode minimal `
  -ConcurrencyValues "64,256" `
  -DurationSeconds 5 `
  -IdleSeconds 3 `
  -FinalIdleSeconds 12
```

Fuller gate with c512:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\anon_evidence_gate.ps1 `
  -AppMode minimal `
  -ConcurrencyValues "64,256,512" `
  -DurationSeconds 5 `
  -IdleSeconds 3 `
  -FinalIdleSeconds 12 `
  -TrimFinalIdleSeconds 95 `
  -TrimFinalIdleSnapshotSeconds "35,95"
```

Fast pipeline smoke:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\anon_evidence_gate.ps1 -Quick
```

The report is written to `benchmark/results/anon_evidence_gate_<mode>_<timestamp>/` and contains:

- `anon_evidence_gate_report.md`: human-readable profile, trim, peak, and load signal.
- `anon_evidence_memory.csv`: final idle attribution.
- `anon_evidence_peaks.csv`: peak current/anon/RSS by run.
- `anon_evidence_load.csv`: RPS, p99, and `503%` by endpoint and concurrency.
- `anon_evidence_memory_all_rows.csv`: every smaps phase row for deeper analysis.
- `openj9_evidence/` inside the javacore run when `-SkipJavacore` is not used.

Interpretation rules:

- If `heap_used_mib` is small but `anon_residual_mib` is high, heap flags are not the primary fix.
  Look at thread/native pool sizing, allocator retention, classpath surface, and JVM runtime state.
- If `class_metadata_used_mib` or `non_heap_other_used_mib` grows with sample mode but not minimal
  mode, the production package/classpath is the target, not request handling.
- If conservative trim reduces final anon/current but p99 or `503%` worsens, keep trim as an
  opt-in policy for low-traffic idle pods.
- If Java-heavy endpoints push peak anon and p99, move those routes to `JsonProducerResponse`,
  direct writer, raw/read-model, or native serialization. Route admission can bound overload; it
  cannot remove object graph allocation.
- Native idle trim reads `reactor_native_http_user_requests_total`, not the raw request total.
  `/health`, `/metrics`, `/metrics/*`, and `/diagnostics/*` are excluded so the benchmark's own
  snapshots, Kubernetes probes, and Prometheus scrapes do not reset the idle window.

`micro-dubbo` in this minimal gate uses static discovery defaults so the framework can measure the
Dubbo-enabled runtime surface without requiring ZooKeeper. For real Kubernetes ZooKeeper-discovery
overhead, run the separate sample consumer benchmark because that includes the actual discovery
client, provider list behavior, and RPC route usage.

Use the idle native trim policy when you need to prove whether warmed native anonymous memory is
releasable without putting trim cost on request handlers:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\linux_smaps_breakdown.ps1 `
  -AppMode minimal `
  -RuntimeProfile micro-rest `
  -ConcurrencyValues 64,256,512 `
  -DurationSeconds 4 `
  -IdleSeconds 3 `
  -FinalIdleSeconds 20 `
  -ExtraJavaOpts "-Dreactor.rust.native-trim.enabled=true -Dreactor.rust.native-trim.initial-delay-ms=1000 -Dreactor.rust.native-trim.interval-ms=2000 -Dreactor.rust.native-trim.min-idle-ms=1000 -Dreactor.rust.native-trim.max-active-requests=0 -Dreactor.rust.native-trim.retain-small=16 -Dreactor.rust.native-trim.allocator-trim-enabled=true"
```

Report the trim metrics with RSS/anon:

- `reactor_native_trim_success_total`
- `reactor_native_trim_skipped_active_total`
- `reactor_native_trim_skipped_not_idle_total`
- `reactor_native_trim_last_duration_ms`

Local smoke evidence after switching the policy to native activity counters:

| Run | Result |
|-----|--------|
| Command shape | minimal app, `micro-rest`, c64, idle trim enabled, final idle `8s` |
| Active traffic behavior | `reactor_native_trim_skipped_active_total=53` |
| Final idle behavior | `reactor_native_trim_success_total=1`, duration `1ms` |
| Rust accounted retained | `0.109 MiB -> 0 MiB` after final idle trim |
| cgroup anon | `32.93 MiB after raw-heavy -> 29.88 MiB final idle` |

This is a smoke proof, not a release-grade benchmark. Run the normal c64/c256/c512 matrix before
enabling the policy in a production profile.

Current background trim uses a soft native path: it can keep a small response-pool floor while
reclaiming larger buckets. The manual `/diagnostics/native/trim` endpoint is still intentionally
full-trim for diagnostics.

Retain-floor/allocator policy matrix:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\native_trim_policy_matrix.ps1 `
  -RuntimeProfile micro-rest `
  -AppMode minimal `
  -RetainSmallValues "2,8,16" `
  -AllocatorTrimValues "true,false" `
  -ConcurrencyValues "64,512" `
  -EndpointSpecs "small-direct|/api/v1/candidates/direct,producer-heavy|/api/v1/heavy/producer?items=100,raw-heavy|/api/v1/heavy/raw" `
  -RepeatCount 1
```

Focused matrix signal: `allocator-trim-enabled=false` did not reclaim meaningful anon memory.
`retain-small=16` plus allocator trim gave the best current balance in the focused run: about
`-15.367 MiB` final cgroup anon, average p99 `-2.06%`, max p99 `+21.05%`, and max `503` delta
`0pp` versus trim-off.

Release-grade A/B gate:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\idle_trim_ab_gate.ps1 `
  -SkipInitialBuild `
  -RuntimeProfile micro-rest `
  -AppMode minimal `
  -ConcurrencyValues "64,256,512" `
  -RepeatCount 3 `
  -DurationSeconds 5 `
  -IdleSeconds 3 `
  -FinalIdleSeconds 12 `
  -ResultsDir "benchmark\results\idle_trim_ab_micro_rest_minimal_r3_20260606"
```

Result:

| Metric | Trim on - trim off |
|--------|-------------------:|
| Final cgroup current | `-14.543 MiB` |
| Final cgroup anon | `-14.484 MiB` |
| Average p99 | `+15.68%` |
| Max p99 | `+70.19%` |
| Max 503 | `+4.514 pp` |

Decision: keep idle trim disabled by default. The aggressive `1s` benchmark policy proved native
anon is reclaimable, and the retained-floor soft trim path preserves the same memory direction, but
short benchmark phases still show p99 trade-offs after idle. Use it only for low-call-rate or bursty
services with meaningful idle windows, and prefer conservative production intervals such as
`initial-delay-ms=30000`, `interval-ms=60000`, `min-idle-ms=10000`.

Focused soft-trim A/B after adding retained pool floors:

| Metric | Trim on - trim off |
|--------|-------------------:|
| Final cgroup current | `-14.404 MiB` |
| Final cgroup anon | `-14.607 MiB` |
| Average p99 | `+10.88%` |
| Max p99 | `+81.08%` |
| Max 503 | `+1.86 pp` |

This focused run used minimal app, `micro-rest`, c64/c512, repeat `2`, and endpoint set
`small-direct`, `producer-heavy`, `raw-heavy`.

Full endpoint repeat gate with `retain-small=16`:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\idle_trim_ab_gate.ps1 `
  -RuntimeProfile micro-rest `
  -AppMode minimal `
  -ConcurrencyValues "64,256,512" `
  -RepeatCount 3 `
  -DurationSeconds 5 `
  -IdleSeconds 3 `
  -FinalIdleSeconds 12 `
  -ResultsDir "benchmark\results\retain16_allocon_full_ab_micro_rest_minimal_r3_20260606"
```

Result:

| Metric | Trim on - trim off |
|--------|-------------------:|
| Final cgroup current | `-17.263 MiB` |
| Final cgroup anon | `-14.768 MiB` |
| Average p99 | `+4.89%` |
| Max p99 | `+27.37%` |
| Max 503 | `+3.021 pp` |

Conservative production-timing soak with `retain-small=16`:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\idle_trim_ab_gate.ps1 `
  -RuntimeProfile micro-rest `
  -AppMode minimal `
  -ConcurrencyValues "64,256,512" `
  -RepeatCount 1 `
  -DurationSeconds 5 `
  -IdleSeconds 3 `
  -FinalIdleSeconds 95 `
  -TrimOnJavaOpts "-Dreactor.rust.native-trim.enabled=true -Dreactor.rust.native-trim.initial-delay-ms=30000 -Dreactor.rust.native-trim.interval-ms=60000 -Dreactor.rust.native-trim.min-idle-ms=10000 -Dreactor.rust.native-trim.max-active-connections=0 -Dreactor.rust.native-trim.max-active-requests=0 -Dreactor.rust.native-trim.retain-small=16 -Dreactor.rust.native-trim.retain-medium=0 -Dreactor.rust.native-trim.retain-large=0 -Dreactor.rust.native-trim.retain-huge=0 -Dreactor.rust.native-trim.allocator-trim-enabled=true"
```

Result:

| Metric | Trim on - trim off |
|--------|-------------------:|
| Final cgroup current | `-20.687 MiB` |
| Final cgroup anon | `-20.844 MiB` |
| Average p99 | `-0.65%` |
| Max p99 | `+77.90%` |
| Max 503 | `+15.185 pp` |

Read this carefully: the conservative soak proves idle reclaim works under production timing
(`trim_success=1` only in final idle), but the single-run c512 rows are noisy. It is not a
high-throughput approval gate.

Long idle leak/retention soak:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\linux_smaps_breakdown.ps1 `
  -RuntimeProfile micro-rest `
  -AppMode minimal `
  -ConcurrencyValues "512" `
  -EndpointSpecs "small-direct|/api/v1/candidates/direct,direct-heavy|/api/v1/heavy?items=100,producer-heavy|/api/v1/heavy/producer?items=100,dynamic-producer|/api/v1/heavy/dto?items=100,raw-heavy|/api/v1/heavy/raw" `
  -DurationSeconds 5 `
  -IdleSeconds 2 `
  -FinalIdleSnapshotSeconds "300,1800"
```

Result, same c512 pressure, same container per variant:

| Phase | Trim on anon | Trim off anon | Delta |
|-------|-------------:|--------------:|------:|
| Baseline | `26.426 MiB` | `26.449 MiB` | `-0.02 MiB` |
| After load idle | `46.781 MiB` | `47.723 MiB` | `-0.94 MiB` |
| 5 min idle | `27.258 MiB` | `44.836 MiB` | `-17.58 MiB` |
| 30 min idle | `27.273 MiB` | `44.836 MiB` | `-17.56 MiB` |

Interpretation: after retained idle trim, anon did not grow back between 5 minutes and 30 minutes.
With trim disabled, idle alone did not release the retained anon. This points to allocator retention,
not an obvious leak in the framework response/native path.

When residual anon remains unclear, collect OpenJ9 evidence after the final idle phase:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\linux_smaps_breakdown.ps1 `
  -AppMode minimal `
  -RuntimeProfile micro-rest `
  -ConcurrencyValues 64,256 `
  -CollectJavacore
```

This writes an `openj9_evidence` folder with javacore files, thread snapshots, process limits, and
basic `jcmd` availability output. It is collected after RSS phases finish, so javacore generation
does not pollute the measured phase rows.

What each mode means:

| Mode | Classpath shape | Best for | Not for |
|------|-----------------|----------|---------|
| `sample` | `rust-java-rest-*-sample.jar` with demo handlers and benchmark routes | Exercising bundled endpoints and route examples | Production RSS claims |
| `minimal` | `core-runtime` plus a tiny user application fixture | Production-like baseline RSS and anon attribution | Claiming every sample endpoint has identical behavior |

Optional JVM anon experiments:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\linux_smaps_breakdown.ps1 `
  -AppMode minimal `
  -RuntimeProfile micro-rest `
  -CodeCacheMaxRAMPercentage 10

powershell -ExecutionPolicy Bypass -File .\benchmark\linux_smaps_breakdown.ps1 `
  -AppMode minimal `
  -RuntimeProfile micro-rest `
  -CodeCacheTotal 8m
```

These options are for A/B measurement, not default deployment. They target OpenJ9 JIT code-cache
commit, which appears inside Linux anon. Keep them out of production profiles until repeat latency
and p99 runs show no unacceptable regression for the service workload.

For repeat latency/p99 checks, use `container_benchmark.ps1` with the explicit parameter. Avoid
passing `-X...` values through generic shell quoting unless the summary proves the option reached
`JAVA_OPTS`.

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 `
  -RuntimeProfile micro-rest `
  -FrameworkCodeCacheTotal 8m `
  -EndpointClasses "small-json-direct,dynamic-producer-json,direct-json-writer,producer-json,raw-json" `
  -ConcurrencyLevels "64,256" `
  -Duration 6s -Warmup 2s -RepeatCount 3 `
  -FrameworkOnly
```

Latest full local gate, `micro-rest`, c64/c256/c512, sample repeat `3`, minimal smaps repeat `3`,
duration `6s` for sample rows:

| Gate | Result |
|------|--------|
| Optional JIT-cap usable for the common endpoint set | `FAIL` |
| Default profile candidate | `FAIL` |
| Minimal production RSS gain | `5.952 MiB` |
| p99 regression failures | `4` |
| Legacy dynamic DTO c256/c512 regressions | `2` |

Minimal production RSS/anon summary from the same gate:

| Metric | Default | `-Xcodecachetotal8m` | Delta |
|--------|--------:|---------------------:|------:|
| cgroup current | `57.358 MiB` | `51.406 MiB` | `-5.952 MiB` |
| cgroup anon | `45.301 MiB` | `45.024 MiB` | `-0.277 MiB` |
| non-heap committed | `36.109 MiB` | `24.453 MiB` | `-11.656 MiB` |
| JIT code committed | `22.000 MiB` | `10.000 MiB` | `-12.000 MiB` |
| anon residual | `23.568 MiB` | `23.767 MiB` | `+0.199 MiB` |
| Linux threads | `22` | `19` | `-3` |

Interpretation: `-Xcodecachetotal8m` reduced the cgroup RSS and JIT/code-cache commitment, but it
did not materially reduce total anon in this run. It also introduced p99 risk for some routes:
the legacy `dynamic-dto-json` path regressed at c256/c512, and `raw-json` regressed at c256/c512.
Direct writer, producer JSON, and small direct JSON mostly improved. The current gate default now
uses `dynamic-producer-json` for the recommended hot DTO-shaped route and keeps `dynamic-dto-json`
available for explicit legacy graph comparison.

Use the gate runner when deciding whether a service may use the JIT-cap option:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\jitcap_gate.ps1 `
  -RuntimeProfile micro-rest `
  -FrameworkCodeCacheTotal 8m `
  -ConcurrencyLevels "64,256,512" `
  -EndpointClasses "small-json-direct,dynamic-producer-json,direct-json-writer,producer-json,raw-json" `
  -RepeatCount 3 `
  -MinimalRepeatCount 3
```

Gate policy:

- `Optional jitcap usable` requires measured RSS gain and no endpoint p99 regression above the
  configured threshold.
- `Default profile candidate` is stricter: if the dynamic heavy JSON path regresses at c256/c512,
  the gate keeps `jitcap` optional even when most other routes improve.
- If the gate fails because of Java-heavy DTO routes, keep JIT-cap out of the default profile and
  move the hot heavy route to `JsonProducerResponse` or direct writer before retesting.
- If a read-mostly/raw service passes its own gate, JIT-cap can still be a local deployment choice.
  Do not generalize that result to mixed Java business workloads.

Latest local Linux smaps comparison, `micro-rest`, same host:

| Phase | Sample app RSS | Minimal app RSS | Delta | What changed |
|-------|---------------:|----------------:|------:|--------------|
| Baseline cgroup RSS | `42.863 MiB` | `30.164 MiB` | `-12.699 MiB` | Fewer app/sample classes before traffic |
| Baseline anon | `28.492 MiB` | `24.359 MiB` | `-4.133 MiB` | Lower runtime anon pressure |
| Baseline loaded classes | `2505` | `1965` | `-540` | Sample surface removed |
| Baseline class metadata | `10.480 MiB` | `8.499 MiB` | `-1.981 MiB` | Less class metadata |
| Final idle cgroup RSS | `58.723 MiB` | `45.219 MiB` | `-13.504 MiB` | Cleaner production-like classpath |
| Final idle anon | `43.852 MiB` | `38.789 MiB` | `-5.063 MiB` | Lower anon after warmup/load/idle |
| Final heap used | `9.490 MiB` | `2.720 MiB` | `-6.770 MiB` | Less sample object/runtime residue |

Interpretation:

- The main published library and `core-runtime` already exclude framework sample and benchmark
  packages. The cleanup is about how we measure and explain RSS.
- Sample/benchmark surface really was polluting production-like RSS measurement when the sample jar
  or framework `target/classes` was used as the benchmark application.
- The minimal app's `heavy/dto` fixture is intentionally small and direct-shaped; do not use it as
  an apples-to-apples replacement for the bundled sample's full dynamic DTO graph benchmark.
- For product claims, report both route latency and classpath mode. A good benchmark label includes
  `AppMode`, `RuntimeProfile`, JVM preset, concurrency, repeat count, and endpoint class.

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
  -EndpointClasses "direct-json-writer,dynamic-producer-json,dynamic-dto-json" `
  -RepeatCount 3 `
  -RandomSeed 20260603
```

Use `micro-rest-plus` as a benchmark/deployment recipe when `micro-rest` protects RSS but rejects too
much traffic on known heavy JSON routes. It is now a first-class runtime profile built on
`micro-rest`: same small runtime shape, conservative native connection cap, and measured
route-budget defaults for routes marked with `@RouteWorkload`.

Measured sample budgets:

| Budget key | Intended route shape | Profile default |
|---|---|---|
| `heavy-json-direct` | direct `ByteBuffer` writer with primitive binding | `maxConcurrent=80`, `timeout=150ms` |
| `heavy-json-producer` | optimized DTO-shaped `JsonProducerResponse` / producer-writer | `maxConcurrent=96`, `timeout=125ms` |
| `heavy-json-legacy` | legacy Java DTO graph comparison route | `maxConcurrent=48`, `timeout=100ms` |

Do not copy sample route keys blindly into another application. Mark your route with the closest
workload/budget, then validate `p99`, `503%`, useful `200 RPS`, and RSS together.

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
  -FrameworkOnly `
  -PlanPreWarm `
  -PlanPreWarmDuration 3s `
  -SkipBuild
```

Use this before changing a production default. The report writes
`route_admission_matrix.csv`, `route_admission_matrix_aggregate.csv`, and
`route_admission_matrix.md`, including useful `200 RPS`, `p99`, `503%`, RSS, route
rejected/timeout counters, and JNI queue saturation. Prefer the lowest `maxConcurrent`
that reaches the useful `200 RPS` target with acceptable `p99` and RSS. Raising
`maxConcurrent` only to hide `503` is usually an anti-pattern because it can push the
same pressure into latency, heap churn, or downstream services.

Current measured sample recipe for `direct-json-writer` at c256/c512 is the
`heavy-json-direct` budget: `maxConcurrent=80, queueTimeoutMs=150`. In the local repeat-3 gate this reduced direct-heavy c512
`503` from `28.83%` to `2.43%`, but it also reduced raw RPS and increased direct-heavy p99. Treat it
as a lower-reject route recipe, not as a faster route recipe.

Current measured sample recipe for `producer-json` remains conservative. The discovery matrix showed
candidate settings can move p99/503 in opposite directions depending on neighboring workloads, so do
not change producer route admission without a mixed endpoint gate.

Current measured sample recipe for `dynamic-producer-json` is
`maxConcurrent=96, queueTimeoutMs=125`. A single-route matrix favored `128/125`, but a later mixed
workload matrix showed that `96/125` is the safer production recipe for the sample app because it
keeps useful `200` RPS high while lowering p99 and RSS when neighboring heavy routes are active.

### Anon Retention Gate: Bounded Pools And Async Frames

The 2026-07-16 minimal-production A/B used the same Semeru OpenJ9 JRE, `micro-rest`, one CPU,
`c64/c256`, and `/api/v1/heavy/producer/async?items=100`. The candidate used bounded process-wide
async frames, shrinking request maps, disabled large/huge response-pool retention, and the corrected
async permit lifecycle.

| Metric | Before | Candidate | Delta |
|---|---:|---:|---:|
| Final cgroup `memory.current` | `53.00 MiB` | `46.93 MiB` | `-6.07 MiB` (`-11.45%`) |
| Final cgroup anon | `46.00 MiB` | `40.52 MiB` | `-5.48 MiB` (`-11.91%`) |
| Final smaps RSS | `78.34 MiB` | `72.05 MiB` | `-6.29 MiB` (`-8.03%`) |
| c64 RPS | `4,395` | `5,573` | `+26.81%` |
| c64 p99 | `46.79 ms` | `41.09 ms` | `-12.18%` |
| c256 useful `200` RPS | `6,708` | `6,985` | `+4.13%` |
| c256 `503` rate | `34.10%` | `25.94%` | `-8.16 pp` |
| c256 p99 | `63.68 ms` | `56.88 ms` | `-10.68%` |

The c256 total RPS is lower because the candidate returns fewer cheap `503` responses and more
successful `200` responses. Compare useful `200` RPS, not total response count alone.

`MALLOC_ARENA_MAX=1` was also tested against the candidate. It saved another `1.86 MiB` of final
anon, but c64 RPS fell from `5,573` to `3,571` and p99 rose from `41.09 ms` to `122.74 ms`.
Therefore the production Docker baseline remains `MALLOC_ARENA_MAX=2`. Arena `1` is not a default
low-RSS recommendation.

### 2026-08-10 ROM-Only SCC And Optional-State Gate

The second anon cycle tested each lever separately before combining them:

| Candidate | Anon result | Performance decision |
| --- | ---: | --- |
| OpenJ9 idle GC | No material reduction | Rejected as a generated-image default |
| `MALLOC_ARENA_MAX=1` | About `-0.46 MiB` against the fresh arena-2 control | Rejected as default after small/direct RPS loss |
| `-Xms4m -Xmx32m` | About `-1.85 MiB` | Kept as service-local experiment because small-route p99 was unstable |
| ROM-only SCC `4m` | About `-3.37 MiB` | Rejected; cache was 100% full and several route classes regressed |
| Java metrics disabled when routes are absent | About `-0.92 MiB` | Accepted; no request-path behavior change |
| Startup route details released with metrics disabled | About another `-0.20 MiB` | Accepted; route details remain when metrics are enabled |
| ROM-only SCC `8m`, combined low-anon image | `31.027 -> 28.207 MiB` final cgroup anon | Accepted as an opt-in image after the endpoint gates |

The long balanced small-route c64 gate reported useful 200 RPS `+11.43%`, p99 `-35.18%`, memory
`-2.11 MiB`, and no errors. The focused c256 small-route row and direct/producer/raw c64/c256 rows
also passed. AOT-bearing SCC was not accepted because direct-writer p99 crossed the 10% regression
limit.

`linux_smaps_breakdown.ps1` now records `-MallocArenaMax` and `-MallocTrimThreshold` in every report,
so allocator experiments are reproducible instead of relying on an image's implicit environment.

Compare DTO graph vs producer/direct writer paths:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 `
  -RuntimeProfile micro-rest-plus `
  -EndpointClasses "dynamic-producer-json,dynamic-dto-json,producer-json,direct-json-writer" `
  -ConcurrencyLevels "256,512" `
  -Duration 20s `
  -Warmup 5s `
  -RepeatCount 3 `
  -RandomSeed 20260603
```

Latest targeted dynamic-producer gate, `micro-rest-plus`, c256/c512, repeat `3`, duration `20s`:

| Class | C | Avg RPS | Avg useful 200 RPS | Avg p99 | Avg 503 % | Avg RSS after |
|-------|--:|--------:|-------------------:|--------:|----------:|--------------:|
| `dynamic-producer-json` | 256 | `2646.57` | `2633.17` | `176.84 ms` | `0.64%` | `82.40 MiB` |
| `dynamic-dto-json` legacy | 256 | `2148.17` | `1760.12` | `177.98 ms` | `18.18%` | `81.00 MiB` |
| `dynamic-producer-json` | 512 | `3432.30` | `2774.99` | `221.68 ms` | `19.44%` | `84.50 MiB` |
| `dynamic-dto-json` legacy | 512 | `4251.85` | `1515.68` | `207.06 ms` | `64.40%` | `84.00 MiB` |

Interpretation: the producer path is the correct hot-route direction because it increases useful
`200` throughput by `1.50x` at c256 and `1.83x` at c512 while keeping RSS broadly in the same band.
Do not read the legacy DTO graph's higher raw RPS at c512 as better throughput; most of that row is
fast `503`. The remaining work is route-admission tuning for producer c512 so the `503%` drops
without pushing p99/RSS outside the pod budget.

Single-route follow-up route-admission matrix for `dynamic-producer-json`, route key
`get.api.v1.heavy.dto`, c256/c512, repeat `3`, initially selected `128/125` before the mixed
workload check:

| C | Previous `80/125` useful 200 RPS | New `128/125` useful 200 RPS | p99 delta | 503 delta | RSS delta |
|---:|---:|---:|---:|---:|---:|
| 256 | `3313.37` | `4195.97` | `159.50ms -> 119.45ms` | `0.08% -> 0.00%` | `-0.03 MiB` |
| 512 | `3064.78` | `4120.37` | `213.70ms -> 194.07ms` | `13.89% -> 1.46%` | `+0.24 MiB` |

Full dynamic gate with the initial `128/125` recipe:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 `
  -FrameworkOnly `
  -RuntimeProfile micro-rest-plus `
  -EndpointClasses "dynamic-producer-json,dynamic-dto-json,direct-json-writer,raw-json" `
  -ConcurrencyLevels "256,512,1000" `
  -Duration 20s `
  -Warmup 5s `
  -RepeatCount 3 `
  -RandomizeOrder:$true `
  -RandomSeed 20260606 `
  -ResultsDir "benchmark\results\full_dynamic_gate_20260606"
```

Latest full dynamic gate result, `micro-rest-plus`, repeat `3`, duration `20s`:

| Class | C | Avg useful 200 RPS | Min useful 200 RPS | Avg p99 | Max p99 | Avg 503 % | Avg RSS after |
|-------|--:|-------------------:|-------------------:|--------:|--------:|----------:|--------------:|
| `dynamic-producer-json` | 256 | `3955.80` | `2670.65` | `130.85 ms` | `184.80 ms` | `0.03%` | `65.34 MiB` |
| `dynamic-dto-json` legacy | 256 | `2501.84` | `1918.73` | `145.75 ms` | `167.22 ms` | `5.01%` | `62.70 MiB` |
| `dynamic-producer-json` | 512 | `3311.67` | `1501.15` | `261.50 ms` | `405.86 ms` | `15.92%` | `66.01 MiB` |
| `dynamic-dto-json` legacy | 512 | `2529.80` | `2495.28` | `153.09 ms` | `160.48 ms` | `43.92%` | `65.07 MiB` |
| `dynamic-producer-json` | 1000 | `3089.42` | `3015.73` | `543.44 ms` | `577.56 ms` | `48.48%` | `68.91 MiB` |
| `dynamic-dto-json` legacy | 1000 | `1498.08` | `1470.64` | `591.86 ms` | `680.56 ms` | `74.34%` | `66.90 MiB` |
| `direct-json-writer` | 256 | `4669.37` | `4436.40` | `96.99 ms` | `102.55 ms` | `0.00%` | `65.51 MiB` |
| `direct-json-writer` | 512 | `4362.36` | `4152.90` | `195.09 ms` | `209.59 ms` | `1.27%` | `65.31 MiB` |
| `direct-json-writer` | 1000 | `2621.43` | `1500.05` | `740.05 ms` | `1210.00 ms` | `50.28%` | `68.11 MiB` |
| `raw-json` | 256 | `12922.75` | `12426.19` | `36.32 ms` | `39.59 ms` | `0.00%` | `67.13 MiB` |
| `raw-json` | 512 | `12131.33` | `10604.24` | `125.41 ms` | `207.74 ms` | `0.01%` | `65.08 MiB` |
| `raw-json` | 1000 | `9273.39` | `8655.77` | `660.10 ms` | `730.33 ms` | `1.82%` | `65.79 MiB` |

Interpretation: the `128/125` producer route is a better hot dynamic JSON path than the legacy DTO
graph at every tested concurrency when judged by useful `200` RPS. At c256 it is clean: higher useful
throughput, lower p99, and almost no `503`. At c512 it still has one outlier run, so treat this as a
measured recipe, not a universal default. At c1000 the system correctly enters controlled overload;
the target is bounded p99/RSS and higher useful throughput, not zero `503` at any concurrency.

Mixed workload route-admission matrix for `dynamic-producer-json`, route key
`get.api.v1.heavy.dto`, c512, repeat `3`, with `dynamic-producer-json`, legacy `dynamic-dto-json`,
`direct-json-writer`, and `raw-json` active in the same plan:

| maxConcurrent | queueTimeoutMs | Producer useful 200 RPS | Producer p99 | Producer 503 % | Avg RSS after | Decision |
|---:|---:|---:|---:|---:|---:|---|
| 96 | 125 | `3860.72` | `192.91 ms` | `3.42%` | `75.77 MiB` | Selected sample recipe |
| 112 | 125 | `3822.25` | `205.19 ms` | `2.98%` | `82.28 MiB` | Higher RSS, lower producer RPS |
| 128 | 125 | `3629.62` | `216.95 ms` | `3.60%` | `84.76 MiB` | Worse p99/RSS under mixed load |

Interpretation: single-route tuning can overfit. The mixed plan is closer to a real pod where
several heavy routes share the same JVM, Rust runtime, worker queues, and memory budget. For
`micro-rest-plus`, the sample optimized DTO-shaped route now uses `96/125`.

Command shape for the mixed route-admission matrix:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\route_admission_matrix.ps1 `
  -EndpointClass "dynamic-producer-json" `
  -BenchmarkEndpointClasses "dynamic-producer-json,dynamic-dto-json,direct-json-writer,raw-json" `
  -RouteAdmissionKey "get.api.v1.heavy.dto" `
  -ConcurrencyLevels "512" `
  -MaxConcurrentValues "96,112,128" `
  -QueueTimeoutMsValues "125" `
  -RuntimeProfile micro-rest-plus `
  -Duration 20s `
  -Warmup 5s `
  -RepeatCount 3 `
  -FrameworkOnly `
  -PlanPreWarm `
  -PlanPreWarmDuration 3s `
  -SkipBuild
```

Follow-up c512 profile validation after changing `micro-rest-plus` to `96/125`:

| Class | Avg useful 200 RPS | Avg p99 | Avg 503 % | Avg RSS after |
|-------|-------------------:|--------:|----------:|--------------:|
| `dynamic-producer-json` | `3703.11` | `198.30 ms` | `4.29%` | `85.67 MiB` |
| `dynamic-dto-json` legacy | `2077.79` | `180.69 ms` | `53.08%` | `85.34 MiB` |
| `direct-json-writer` | `3672.09` | `217.25 ms` | `3.23%` | `86.29 MiB` |
| `raw-json` | `10478.25` | `120.08 ms` | `0.00%` | `85.83 MiB` |

Interpretation: the profile validation kept the producer route stable enough for the memory-first
`micro-rest-plus` recipe, but RSS after depends on warmed state and run order. Use Linux smaps/cgroup
memory proof for pod sizing; do not infer a hard pod limit from one route-admission matrix.

Current full dynamic gate after changing `micro-rest-plus` to `96/125`, with `PlanPreWarm`,
`FrameworkOnly`, c256/c512/c1000, repeat `3`:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 `
  -FrameworkOnly `
  -RuntimeProfile micro-rest-plus `
  -EndpointClasses "dynamic-producer-json,dynamic-dto-json,direct-json-writer,raw-json" `
  -ConcurrencyLevels "256,512,1000" `
  -Duration 20s `
  -Warmup 5s `
  -RepeatCount 3 `
  -RandomSeed 20260610 `
  -PlanPreWarm `
  -PlanPreWarmDuration 3s `
  -ResultsDir "benchmark\results\full_dynamic_gate_profile96_prewarm_20260606"
```

| Class | C | Avg useful 200 RPS | Min useful 200 RPS | Avg p99 | Max p99 | Avg 503 % | Avg RSS after |
|-------|--:|-------------------:|-------------------:|--------:|--------:|----------:|--------------:|
| `dynamic-producer-json` | 256 | `3420.95` | `2094.27` | `149.09 ms` | `208.62 ms` | `0.14%` | `60.48 MiB` |
| `dynamic-producer-json` | 512 | `3774.52` | `3299.77` | `190.73 ms` | `204.87 ms` | `4.31%` | `63.10 MiB` |
| `dynamic-producer-json` | 1000 | `2399.13` | `2019.07` | `723.28 ms` | `981.59 ms` | `50.78%` | `64.72 MiB` |
| `dynamic-dto-json` legacy | 256 | `2288.17` | `2199.84` | `159.30 ms` | `160.35 ms` | `5.86%` | `61.05 MiB` |
| `dynamic-dto-json` legacy | 512 | `2247.21` | `2051.24` | `161.25 ms` | `171.46 ms` | `49.75%` | `60.41 MiB` |
| `dynamic-dto-json` legacy | 1000 | `1414.63` | `1361.43` | `630.10 ms` | `670.93 ms` | `74.82%` | `61.63 MiB` |
| `direct-json-writer` | 256 | `3911.24` | `3639.85` | `151.67 ms` | `160.22 ms` | `0.00%` | `60.19 MiB` |
| `direct-json-writer` | 512 | `3738.20` | `3216.71` | `210.66 ms` | `229.60 ms` | `3.66%` | `62.97 MiB` |
| `direct-json-writer` | 1000 | `2674.44` | `2576.28` | `659.72 ms` | `823.17 ms` | `48.47%` | `62.86 MiB` |
| `raw-json` | 256 | `11983.44` | `11669.94` | `49.80 ms` | `53.79 ms` | `0.00%` | `60.41 MiB` |
| `raw-json` | 512 | `10957.54` | `10491.07` | `103.09 ms` | `109.01 ms` | `0.00%` | `64.01 MiB` |
| `raw-json` | 1000 | `8711.00` | `8436.15` | `695.56 ms` | `790.06 ms` | `1.70%` | `60.36 MiB` |

Producer comparison against the earlier `128/125` full gate:

| C | `96/125` useful 200 RPS | `128/125` useful 200 RPS | Useful delta | `96/125` p99 | `128/125` p99 | 503 delta |
|---:|------------------------:|-------------------------:|-------------:|-------------:|--------------:|----------:|
| 256 | `3420.95` | `3955.80` | `-13.52%` | `149.09 ms` | `130.85 ms` | `0.14% vs 0.03%` |
| 512 | `3774.52` | `3311.67` | `+13.98%` | `190.73 ms` | `261.50 ms` | `4.31% vs 15.92%` |
| 1000 | `2399.13` | `3089.42` | `-22.34%` | `723.28 ms` | `543.44 ms` | `50.78% vs 48.48%` |

Decision: keep `96/125` as the sample `micro-rest-plus` recipe. It sacrifices some c256 headroom and
c1000 overload throughput, but materially stabilizes c512, which is the useful operating point for
this memory-first profile. If c1000 must stay productive, `micro-rest-plus` is the wrong profile;
use a larger route budget, a throughput profile, or split the hot route to direct/raw/read-model
serving.

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
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 -RuntimeProfile low-rss -Duration 20s -Warmup 5s -ConcurrencyLevels "64,256,512,1000" -RepeatCount 3 -EndpointClasses "dynamic-producer-json,dynamic-dto-json,direct-json-writer,raw-json"
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
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 -RuntimeProfile low-rss -Duration 10s -Warmup 2s -ConcurrencyLevels "64,256,512,1000" -RepeatCount 3 -RandomSeed 20260530 -EndpointClasses "small-json,dynamic-producer-json,dynamic-dto-json,direct-json-writer,raw-json,file-static"
```

Average c=1000 comparable endpoints:

| Endpoint | Rust-Java RPS | Spring Boot RPS | Ratio | Rust P99 | Spring P99 | Rust Max Mem | Spring Max Mem |
|----------|--------------:|----------------:|------:|---------:|-----------:|-------------:|---------------:|
| candidates | 7,995 | 2,601 | 3.07x | 579ms | 1.12s | 98 MiB | 313 MiB |
| echo | 6,912 | 2,193 | 3.76x | 824ms | 1.29s | 95 MiB | 295 MiB |
| heavy100 raw | 8,638 | 2,124 | 4.01x | 516ms | 1.25s | 97 MiB | 303 MiB |
| heavy100 dynamic DTO | 1,495 | 1,068 | 1.51x | 1.61s | 1.75s | 98 MiB | 303 MiB |

Interpretation: low-RSS protects memory well, but dynamic DTO and file/static high-concurrency tail latency still need route-specific throughput tuning.

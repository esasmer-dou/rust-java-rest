# Production Runtime Notes

This page explains the runtime knobs you normally touch in production. The short version:

- Java owns handlers, services, components, and business logic.
- Rust owns HTTP accept/parsing, body limits, file streaming, WebSocket framing, metrics, and backpressure.
- Start with a profile, then tune only the route class that needs it.

## Profile Selection

| Profile | Use when | Practical note |
|---------|----------|----------------|
| `micro-rest` | Very small REST service, Dubbo off, memory first | Lowest REST preset; bounded `503` under heavy route pressure |
| `micro-rest-plus` | Same small REST runtime, but known heavy JSON routes need fewer rejects | Uses route workload budgets; not a global worker/queue increase |
| `micro-dubbo` | Very small REST service with native Dubbo consumer enabled | Prefer static providers; pair with OpenJ9 micro JVM options |
| `low-rss` | General memory-first REST service | More headroom than `micro-rest`, still conservative |
| `balanced-dubbo` | Dubbo/RPC routes need smoother p99 | Higher RSS than `micro-dubbo`, more worker/connection headroom |
| `throughput` | Dedicated high-RPS service with larger pod budget | More retained buffers/workers |
| `fast-start` | Startup-sensitive service | Startup acceleration defaults; not a memory preset by itself |
| `ready-low-latency` | First requests should be warm | Prewarm-oriented; may retain more warm state |

Recommended first production-like baseline:

```properties
reactor.runtime.profile=micro-rest
reactor.websocket.enabled=false
reactor.static-files.enabled=false
reactor.rust.http.max-connections=512
reactor.rust.jni.workers=1
reactor.rust.jni.queue-capacity=128
reactor.rust.http.max-inflight-response-bytes=8388608
reactor.rust.file-stream.chunk-bytes=32768
reactor.rust.static-file.max-concurrent-streams=32
reactor.rust.native-cache.max-entries=0
reactor.rust.native-cache.max-bytes=0
reactor.rust.native-trim.enabled=false
reactor.rust.log.level=error
reactor.rust.java.log.level=warn
```

`micro-rest` and `micro-dubbo` are memory-first profiles. They keep the WebSocket registration path
and annotation-based static-file scanner off by default. If a service actually uses these features,
enable only that feature explicitly:

```properties
reactor.runtime.profile=micro-rest
reactor.websocket.enabled=true
```

or:

```properties
reactor.runtime.profile=micro-rest
reactor.static-files.enabled=true
```

Do not keep optional surfaces enabled "just in case" in a small pod. Each optional scanner/registry
adds class loading, metadata, and startup work, even if the route is never called.

For a hot bodyless JSON route, first ask whether the payload is actually dynamic. If the response is
immutable or intentionally precomputed until restart, use `RawResponse.registeredJson(...)` with
`@NativeStaticRoute`; Rust will serve the route without Java handler invocation or JNI queue work.

If the route is dynamic Java business logic and still shows queue-full rejection after it is
bodyless/direct, you may test a route-local JNI lane:

```properties
reactor.rust.jni-admission.get.api.v1.candidates.direct.max-pending=512
reactor.rust.jni-admission.get.api.v1.candidates.direct.queue-timeout-ms=0
```

This keeps `micro-rest` at `reactor.rust.jni.queue-capacity=128`, but it adds a priority JNI worker.
It is not the default production recipe. The permit is released when a JNI worker starts the job;
`@RouteAdmission` holds the permit until the response completes and should stay reserved for heavy or
slow routes. If the route is not hot or `reactor_native_jni_queue_full_total` is zero, do not add the
local lane just because the option exists.

For heavy JSON routes, prefer workload budgets before global queue or connection changes:

```java
@RustRoute(method = "GET", path = "/reports/heavy", requestType = Void.class, responseType = JsonProducerResponse.class)
@DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
@RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-direct")
public JsonProducerResponse heavyReport(int items) {
    return JsonProducerResponse.ok(new HeavyReportProducer(items));
}
```

`@DirectQueryInt` in this example is only the scalar parameter fast path. It does not make a large
DTO graph cheap. If a route is marked `HEAVY_JSON` and production gate
`reactor.optimizer.fail-on-heavy-json-object-graph=true` is enabled, the response path must be
producer/direct/raw/native. A direct query/path annotation with a large DTO return is still rejected.

Before enabling fail-fast, check:

```bash
curl http://localhost:8080/diagnostics/routes
```

Look for route-level `"heavy_json_object_graph":true`. Those endpoints are the first migration
targets. If the endpoint is fixed-shape dynamic JSON, use `JsonProducerResponse` or `JsonBodyProducer`.
If the payload is already serialized, use `RawResponse`. If it is immutable or file-backed, use native
static response or `FileResponse`. Only after this value is `false` should admission and queue knobs
be used to stabilize high concurrency.

If a route also has `"benchmark_only":true`, it is an explicit sample/comparison route. It is counted
under `benchmark_heavy_json_object_graph`, not the production `heavy_json_object_graph` summary. In
real production services, enable `reactor.optimizer.fail-on-benchmark-only-routes=true` so these
routes cannot ship accidentally.

Then start with:

```properties
reactor.runtime.profile=micro-rest-plus
```

`micro-rest-plus` inherits the low-memory `micro-rest` runtime shape and applies measured admission
budgets only to routes that declare a matching workload budget. If one route needs a different value,
override the route key directly:

```properties
reactor.rust.route-admission.get.reports.heavy.max-concurrent=80
reactor.rust.route-admission.get.reports.heavy.queue-timeout-ms=150
```

Do not turn this into a global `max-connections` or JNI queue increase. The mixed c512 gates showed
that broad changes can improve one route while regressing raw/static and producer routes.

If the heavy route is blocking JNI workers because it waits on RPC/database/read-model work, use an
async producer route instead of increasing the global worker count:

```java
@RustRoute(method = "GET", path = "/reports/heavy/async", requestType = Void.class, responseType = JsonBodyProducer.class)
@DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
@RouteAdmission(maxConcurrent = 80, queueTimeoutMs = 150)
public CompletionStage<JsonBodyProducer> heavyReportAsync(int items) {
    return AsyncHandlerExecutor.getInstance()
            .submit(() -> (JsonBodyProducer) new HeavyReportProducer(items));
}
```

This path supports direct `int` query binding, so the async bridge does not need to build request
query strings for that scalar. It is still an opt-in route design. Keep CPU-bound JSON sync by
default unless the route matrix proves async improves useful `200` RPS, p99, `503`, and RSS together.

Async completion buffers are heap-backed by default:

```properties
reactor.rust.async.direct-buffer.enabled=false
reactor.rust.async.frame-initial-bytes=8192
reactor.rust.async.frame-pool-capacity=2
reactor.rust.async.frame-retain-max-bytes=65536
```

The values above are the `micro-rest` recipe. The initial size is not the maximum response size.
Larger responses use a bounded capacity retry. Choose an initial value that covers the normal async
payload without making every concurrent request start with a large array. The pool is process-wide,
not thread-local, and its capacity is therefore a direct retained-memory budget.

For native response pools, a bucket capacity of `0` means "do not retain completed buffers in this
size class". It does not disable responses in that size class. `micro-rest` intentionally uses:

```properties
reactor.rust.response-pool.small-capacity=8
reactor.rust.response-pool.medium-capacity=2
reactor.rust.response-pool.large-capacity=0
reactor.rust.response-pool.huge-capacity=0
```

This keeps the frequent small working set reusable while returning one-off large allocations to the
native allocator. Do not increase large/huge retention to hide allocator churn without a mixed-route
RSS and p99 gate.

Set `reactor.rust.async.direct-buffer.enabled=true` only after a Linux smaps/anon gate. Direct async
buffers can reduce copy work, but they can also increase retained direct/native memory after bursts.
The matched minimal-production gate with heap async frames ended at `46.93 MiB` cgroup current,
`40.52 MiB` anon, and effectively zero direct-buffer attribution. Keep heap frames as the default
decision until your own route matrix proves otherwise.

Strict low-RSS services should also ship startup indexes instead of relying on classpath scanning:

```text
META-INF/reactor/components.idx
META-INF/reactor/routes.idx
```

The minimal production benchmark image follows this rule during Docker build: it compiles the user
classes with `ReactorStartupProcessor` and starts with the generated index on the classpath. If your
application logs a `classpath scan fallback is enabled and no component index is present` warning,
fix the build first; do not treat that run as the clean production RSS baseline.

Generate the index for the exact runnable surface. Explicitly constructed route handlers are included
even when they are not DI components. Multi-surface applications should use the
`reactor.codegen.excludePackages` and `reactor.codegen.excludeClasses` compiler options per Maven
profile. Duplicate HTTP method/path pairs fail compilation. This prevents
alternative sample, benchmark, or legacy handlers from polluting the production route index and its
loaded-class/RSS measurements.

For `micro-rest` and `micro-dubbo`, properties alone are not enough. The JVM must also be prevented
from sizing internal workers from a large host CPU count:

```bash
-Xms8m -Xmx48m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1
```

Use `startup/openj9-micro-rss.options` as the starting point. For very low traffic services only,
`startup/openj9-idle-rss.options` adds `-Xnojit`; this can reduce memory further, but it trades away
JIT-optimized Java execution and must be benchmarked before production use.

If javacore evidence shows many OpenJ9 JIT compilation threads in a small pod, test
`startup/openj9-micro-rss-jitthreads1.options`. It adds `-XcompilationThreads1`, keeping JIT enabled
while narrowing background compiler worker count. Do not default it blindly; it is a memory-first
candidate that must pass p99, `503`, and RSS gates for the service's real route mix.

Do not lower `-Xss256k` globally just to chase RSS. The latest minimal `micro-rest` c512 stack matrix
tested `256k/192k/160k/128k`: no stack/OOM/native-thread failure appeared, but cgroup anon did not
drop predictably enough to justify a new default. `192k` or `128k` can be tested for a very small
service, but only after the real deepest route path, RPC provider/consumer path, JDBC path, and
error-handling path pass smoke/load tests. Compare `256k`, `192k`, `160k`, and `128k` with the same
application image and c512 workload.

The production Docker baseline keeps `MALLOC_ARENA_MAX=2` and
`MALLOC_TRIM_THRESHOLD_=131072`. Do not change the arena count to `1` only because it lowers an idle
RSS snapshot. In the focused CPU=1 async-producer A/B, arena `1` reclaimed another `1.86 MiB` anon,
but c64 RPS fell from `5,573` to `3,571` and p99 rose from `41.09 ms` to `122.74 ms`. The allocator
became smaller but less concurrent; that violates the framework's latency/throughput objective.

## ROM-Only OpenJ9 Shared Cache

For a memory-first image, the Maven plugin can build a read-only OpenJ9 shared class cache. This
mode shares ROM class metadata but deliberately stores no AOT methods. Java business logic and JIT
compilation remain unchanged.

```xml
<plugin>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-maven-plugin</artifactId>
  <version>${rust-java-rest.version}</version>
  <configuration>
    <mainClass>com.example.Application</mainClass>
    <openJ9SharedClassCacheEnabled>true</openJ9SharedClassCacheEnabled>
    <openJ9SharedClassCacheName>orders_rom</openJ9SharedClassCacheName>
    <openJ9SharedClassCacheSize>8m</openJ9SharedClassCacheSize>
    <openJ9SharedClassCachePrefixes>com.example.</openJ9SharedClassCachePrefixes>
    <mallocArenaMax>2</mallocArenaMax>
    <mallocTrimThreshold>131072</mallocTrimThreshold>
  </configuration>
</plugin>
```

Run `mvn reactor:runtime-image` after packaging. The generated recipe is written under
`target/reactor-runtime/`. The build stage populates the cache with the same Semeru/OpenJ9 build
used by the runtime stage. Runtime opens it with `readonly,fatal`; an incompatible or missing cache
therefore fails early instead of silently changing memory behavior.

The generated multi-stage image installs `binutils` only in the build stage because
`jlink --strip-debug` needs `objcopy`. It is not copied into the runtime image. When SCC is enabled,
the recipe copies only OpenJ9's `libj9shr*.so` SCC library into the JLink runtime. The process runs
as user `10001`; `/app/.reactor/native` is reserved for native extraction and `/app/work` is the
only general writable working directory.

The 2026-08-10 minimal-production gate selected `8m`:

| Decision | Evidence |
| --- | --- |
| `8m`, ROM-only | Final cgroup anon moved from `31.027 MiB` to `28.207 MiB` with metrics disabled and startup route plans released |
| Long c64 small route | Useful 200 RPS `+11.43%`, p99 `-35.18%`, memory `-2.11 MiB`, no errors or `503` |
| c256 small route | Passed the balanced threshold; useful RPS `+1.74%`, p99 `-8.57%` |
| Direct, producer, and raw JSON | All c64/c256 rows passed the useful-RPS, p99, `503`, and memory thresholds |
| `4m` cache | Rejected: the cache reached 100% and small/raw/direct throughput regressed |
| AOT-bearing cache | Rejected: direct-writer p99 exceeded the 10% regression limit |

Keep this mode opt-in. Re-run the same endpoint matrix for the service image because class mix and
JIT warmup differ by application. Do not replace `8m` with a smaller cache just to reduce image
size; an undersized cache can be slower even when its anon number looks lower.

When built-in metrics are not needed, leave `reactor.metrics.collection-enabled=false` and
`reactor.optimizer.retain-route-plans=auto`. The framework then drops the unused Java metric
registry and startup-only route-plan objects. If `@ReactorApplication(metrics = true)` or
`RestApplication.Builder.metrics()` is used, metrics and route diagnostics remain fully available.

## Idle Native Trim

Native allocator retention can keep RSS/anonymous memory elevated after a burst even when the Java
heap is small. The framework exposes an optional idle-only policy for this case:

```properties
reactor.rust.native-trim.enabled=true
reactor.rust.native-trim.initial-delay-ms=30000
reactor.rust.native-trim.interval-ms=60000
reactor.rust.native-trim.min-idle-ms=10000
reactor.rust.native-trim.max-active-connections=0
reactor.rust.native-trim.max-active-requests=0
reactor.rust.native-trim.retain-small=16
reactor.rust.native-trim.retain-medium=0
reactor.rust.native-trim.retain-large=0
reactor.rust.native-trim.retain-huge=0
reactor.rust.native-trim.allocator-trim-enabled=true
```

Use it for `micro-rest`, `micro-dubbo`, or `low-rss` services that have idle windows and strict pod
memory limits. Do not enable it just because a service is high traffic. Trimming native allocators can
be expensive, so the policy waits until the native request counter is stable for `min-idle-ms`,
active connections are at or below `max-active-connections`, and active requests are at or below
`max-active-requests`. It then trims once for that idle request-count window and waits for new
traffic before trimming again.

The native request counter used for this idle decision is `reactor_native_http_user_requests_total`,
not the raw HTTP total. `/health`, `/metrics`, `/metrics/*`, and `/diagnostics/*` are excluded.
Kubernetes probes and Prometheus scrapes should not keep an otherwise idle pod from reclaiming native
anonymous memory.

If a trim tick sees recent user activity or an active connection/request, the next check is scheduled
at the next possible idle boundary instead of waiting the full `interval-ms`. This keeps conservative
production intervals (`30s/60s/10s`) without making a bursty low-traffic pod wait an unnecessary
extra minute before reclaiming native anonymous memory.

The idle policy is a soft native trim. `retain-small` leaves a tiny warm response-buffer floor, while
`retain-medium`, `retain-large`, and `retain-huge` are normally `0` for memory-first pods.
`allocator-trim-enabled=true` lets the platform allocator return idle native pages to the OS. If a
service is ultra latency-sensitive after long idle periods, benchmark with `allocator-trim-enabled=false`
before enabling it. The manual `/diagnostics/native/trim` endpoint remains a full diagnostic trim.

Environment variable form is automatic:

```yaml
env:
  - name: REACTOR_RUST_NATIVE_TRIM_ENABLED
    value: "true"
  - name: REACTOR_RUST_NATIVE_TRIM_INTERVAL_MS
    value: "60000"
  - name: REACTOR_RUST_NATIVE_TRIM_MIN_IDLE_MS
    value: "10000"
  - name: REACTOR_RUST_NATIVE_TRIM_MAX_ACTIVE_CONNECTIONS
    value: "0"
  - name: REACTOR_RUST_NATIVE_TRIM_MAX_ACTIVE_REQUESTS
    value: "0"
  - name: REACTOR_RUST_NATIVE_TRIM_RETAIN_SMALL
    value: "16"
  - name: REACTOR_RUST_NATIVE_TRIM_ALLOCATOR_TRIM_ENABLED
    value: "true"
```

Observability:

- `reactor_native_trim_attempts_total`: trim attempts.
- `reactor_native_trim_success_total`: completed trims.
- `reactor_native_trim_skipped_active_total`: skipped because connections were active.
- `reactor_native_trim_skipped_not_idle_total`: skipped because request activity changed recently.
- `reactor_native_trim_skipped_unchanged_total`: skipped because this idle window was already
  trimmed.
- `reactor_native_trim_last_duration_ms`: last trim duration.

Automatic native trimming is supported only through `reactor.rust.native-trim.*`. The policy runs
after an idle window and never attaches allocator trim work to an arbitrary user request.

Current gate signal: with a focused retained-floor soft trim policy, minimal `micro-rest` recovered
about `14.6 MiB` final cgroup anon, but p99 still regressed on part of the matrix. Treat idle trim as
a pod-memory tool for low-call-rate or bursty services, not as a throughput optimization and not as a
default profile setting.

The current measured starting point is `retain-small=16` with `allocator-trim-enabled=true`.
Allocator trim disabled did not produce meaningful anon reclaim in the focused matrix; it mainly
shrinks framework response pools but leaves allocator-held anonymous pages resident.

Latest P0 evidence:

- Full repeat gate, `micro-rest`, minimal app, full endpoint set, c64/c256/c512, repeat `3`: final
  cgroup anon `-14.768 MiB`, average p99 `+4.89%`, max p99 `+27.37%`, max `503` delta `+3.021pp`.
- Conservative soak, same endpoint set, repeat `1`, production timing `30s/60s/10s`, final idle
  `95s`: final cgroup anon `-20.844 MiB`, trim fired once in final idle.
- Long idle retention check, c512 pressure, 5 minute and 30 minute snapshots: trim-on anon stayed
  flat after reclaim (`27.258 MiB -> 27.273 MiB`), while trim-off stayed high (`44.836 MiB -> 44.836 MiB`).

Operational decision: enable this only for services where idle RSS matters more than maximum
next-burst throughput. For high-throughput pods, run the endpoint matrix first and keep it disabled
if c512/c1000 p99 or `503` behavior regresses.

For applications that use startup indexes, compile only the source/profile surface that will run.
For a tiny REST-only service, keep WebSocket and static-file components out of that Maven profile.
If they are part of the service contract, include them and enable the matching runtime property.

## Production Artifact Rule

Run production services with the lean framework artifact:

- Use the normal Maven dependency for application compile/runtime.
- Use `rust-java-rest-*-core-runtime.jar` in container images when you need a single framework
  runtime jar.
- Do not put `target/classes` from the framework project on a production or production-like
  validation classpath.
- Do not use the `sample` classifier for production. It intentionally contains demo handlers,
  DTOs, and a sample startup index.

The default jar, `core-runtime` jar, sources jar, and javadocs exclude
`com.reactor.rust.example` and `com.reactor.rust.dubbo.sample`.
The sample jar keeps those classes only so examples remain runnable.

Read this as a production rule, not only as a packaging detail:

| Artifact or classpath | Use it for | Do not use it for |
|-----------------------|------------|-------------------|
| Normal Maven dependency, `rust-java-rest` | Real applications and normal library consumption | Running the bundled sample app directly |
| `rust-java-rest-*-core-runtime.jar` | Single-jar framework runtime classpath in container images | Replacing your application code |
| `rust-java-rest-*-sample.jar` | Demo routes and local framework examples | Production, pod sizing, or RSS claims |
| Framework `target/classes` | Local framework debugging | Production-like validation |

The reason is simple: sample classes are useful for demos, but they are not part of a
real service contract. If they are placed on the classpath, they can load extra handlers, DTOs,
and startup-index entries. That makes RSS attribution noisy and can hide the
actual cost of the production framework.

Local verification from the `v3.2.2` package:

| Jar | Sample/benchmark package count |
|-----|-------------------------------:|
| `rust-java-rest-3.2.2.jar` | `0` |
| `rust-java-rest-3.2.2-core-runtime.jar` | `0` |
| `rust-java-rest-3.2.2-sample.jar` | demo/benchmark classes only |

For memory work, default to a minimal real production application. Capture baseline, c64/c256 load,
idle, and final-idle RSS plus `smaps_rollup` from the same container image.

Use the sample app only when the route under test exists only in the bundled sample. Do not mix
sample-route benchmark data with production-classpath RSS claims.

## Anonymous Memory Evidence Gate

Before lowering Kubernetes memory limits, collect anonymous-memory evidence from the minimal real
application at c64/c256/c512, followed by normal idle and conservative trim snapshots.

This gate runs:

- `micro-rest` minimal smaps.
- `micro-rest-plus` minimal smaps.
- `micro-dubbo` minimal smaps with static discovery defaults.
- Conservative idle native trim off/on A/B.
- OpenJ9 javacore/native evidence capture.

Read the output by category:

| Category | What it tells you |
|----------|-------------------|
| `heap_used_mib` | Live Java objects. If high, reduce DTO graph allocation or cache size. |
| `jit_code_used_mib` | OpenJ9 JIT/code footprint. If high, test JIT-cap with a latency gate. |
| `class_metadata_used_mib` | Loaded class surface. If high, remove optional deps or wrong classpath entries. |
| `direct_buffer_mib` | Direct buffer retention. If high, check response/file/native buffer pools. |
| `rust_accounted_mib` | Framework-native accounted pools/cache. Tune pool caps or trim floors. |
| `thread_stack_budget_mib` | Thread count times stack budget. Reduce pools before reducing stack unsafely. |
| `anon_residual_mib` | JVM/native/allocator residual that needs A/B evidence, not guessing. |

Production rule: never claim a lower pod limit from a single idle RSS value. Use baseline, warmup,
load, final idle, trim A/B, and p99/`503%` together. A lower memory number is not a win if it turns
overload into unstable tail latency.

For Kubernetes Dubbo consumers, run the separate sample consumer benchmark with ZooKeeper discovery
enabled after this minimal gate. The minimal `micro-dubbo` row isolates the framework's Dubbo-on
runtime surface; it does not include a real ZooKeeper client, provider churn, or business RPC mix.

Measured `v3.2.2` release-gate RSS guidance:

These values are recommended initial Kubernetes memory limits, not exact RSS promises. A service can
idle below the value in the table, but the pod needs headroom for native buffers, thread stacks, class
metadata, JIT/runtime state, request bursts, and route-specific payloads.

How to read this table:

- `RSS` is the current memory footprint of the process.
- `resources.requests.memory` is the memory Kubernetes uses for scheduling.
- `resources.limits.memory` is the hard memory cap; crossing it can produce `OOMKilled`.
- The table is the first safe `limits.memory` value to try, not the exact RSS target.
- Do not set `limits.memory` equal to the best idle RSS. The pod also needs room for request bursts,
  native buffers, response buffers, thread stacks, class metadata, and runtime/JIT state.

Example:

```yaml
resources:
  requests:
    memory: "64Mi"
    cpu: "100m"
  limits:
    memory: "96Mi"
    cpu: "500m"
```

For a tiny low-traffic REST service, this gives the pod room above an observed `66-75 MiB` RSS range.
For services with Dubbo, DB pools, cache, WebSocket, heavy JSON, or high concurrency, start higher and
lower only after load plus idle/soak measurements.

Latest minimal production anon attribution:

| Case | Current | Anon | Heap | JIT | Class metadata | Residual anon | How to use it |
|------|--------:|-----:|-----:|----:|---------------:|--------------:|---------------|
| `micro-rest` | 66.71 MiB | 50.18 MiB | 4.16 | 2.50 | 10.59 | 29.34 | Baseline memory-first REST |
| `micro-rest-plus` | 66.81 MiB | 50.47 MiB | 5.06 | 2.47 | 10.56 | 28.71 | Same RSS class, heavier route budgets |
| `micro-dubbo` | 70.92 MiB | 50.48 MiB | 3.45 | 2.55 | 10.59 | 30.11 | Dubbo-enabled minimal runtime surface |
| conservative idle trim on | 46.97 MiB | 31.87 MiB | 3.29 | 2.46 | 10.63 | 12.02 | Low-traffic idle reclaim only |

The dominant area is not Java heap. Heap is small in these runs; remaining pressure is mostly
class/runtime/native allocator residual anon. If you need lower steady RSS, first remove optional
classpath surface, move hot heavy JSON to producer/direct/raw/native paths, and use idle trim only
for pods that actually become idle.

| Service shape | Initial pod memory limit to try |
|---------------|--------------------:|
| Tiny low-traffic REST, no RPC/DB | 96 MiB |
| Small REST with normal JSON | 128 MiB |
| REST + native Dubbo consumer | 128-160 MiB |
| Heavy dynamic JSON | 160 MiB or more, then measure |
| Large file/download routes | Size by stream concurrency and file chunk settings |

Do not set the pod limit exactly at the best idle RSS. Leave headroom for native buffers, class
metadata, thread stacks, request bursts, and JIT/runtime state.

## Response Types

`RawResponse`

- Use for pre-serialized text or bytes such as `/metrics`.
- Bypasses JSON serialization, so Prometheus text is not quoted or escaped.
- Good for already serialized JSON, native cache, or immutable read-heavy responses.
- For ordinary business JSON, start with record DTOs.

`FileResponse`

- Use for static files, downloads, and export files.
- Java returns path plus headers; file bytes are streamed by Rust.
- This avoids large Java heap byte arrays and avoids moving file contents through JNI.

`JsonBufferWriter` / direct writer

- Use for a hot JSON route where DTO graph allocation is visible in benchmark data.
- Keep the response contract as a record, but write the hot response directly into the native buffer.

## Which Response Type Should I Pick?

| Use case | Recommended response | Annotation/API | Why |
|----------|----------------------|----------------|-----|
| Small JSON | Java record DTO | `@GetMapping` / `@PostMapping` or `@RustRoute`, `responseType = MyRecord.class` | Simple default for normal REST APIs |
| Dynamic business DTO | Java record graph + DSL-JSON | `responseType = MyRecord.class` | Best maintainability when the response is real business data |
| Already serialized JSON | `RawResponse.json(...)` | route `responseType = RawResponse.class` | Avoids deserialize/serialize roundtrip |
| Immutable config/read model | `RawResponse.registeredJson(...)` + `@NativeStaticRoute` | `@NativeStaticRoute` only when immutable until restart | Rust can serve without Java handler call |
| Repeated read-heavy JSON | `RawResponse.nativeJson(id)` from dynamic native cache | `NativeBridge.lookupDynamicResponse(...)`, `NativeBridge.registerDynamicResponse(...)` | Avoids repeated body build and repeated Java-to-Rust transfer on hits |
| Hot predictable JSON | `JsonBufferWriter` or direct writer | `@RustRoute` plus optional `@DirectQuery*` / `@DirectPath*` | Avoids DTO graph and serializer buffer |
| File/download/export | `FileResponse` | route `responseType = FileResponse.class` | File body stays out of Java heap |
| Immutable static file | `FileResponse` + `@NativeStaticFileRoute` | `@NativeStaticFileRoute` | Rust serves path directly after startup |

## Runtime Flow By JSON Path

Small JSON:

- Rust handles the HTTP connection and calls the Java handler.
- Java returns a record.
- DSL-JSON serializes the record and Rust writes the response.

Use it for normal APIs first. It is the safest default.

Raw/precomputed JSON:

- Java returns `RawResponse.json(...)`, `RawResponse.text(...)`, or `RawResponse.bytes(...)`.
- The framework skips DTO serialization.
- Rust writes the provided bytes.

Use it when JSON already exists before the handler returns.

Native cache JSON:

- Java or startup code registers response bytes in Rust native memory.
- Later requests return `RawResponse.nativeJson(id)`, or `@NativeStaticRoute` bypasses Java entirely for immutable routes.
- Rust serves cached bytes while respecting native cache caps and TTL.

Use it only for deliberate read-heavy responses with stable keys. Do not cache highly unique or
authorization-dependent responses by default.
For hot keys, the miss path should also avoid large DTO graphs. Prefer read-model bytes,
`JsonBodyProducer`, direct writer, or selected native serialization before registering the payload.

Direct JSON writer:

- Rust gives Java a direct response `ByteBuffer`.
- Java writes JSON with `JsonBufferWriter` or a generated direct writer and returns the byte count.
- Rust sends the buffer without building a DTO graph.
- For predictable prefix-plus-number strings, use `fieldStringAsciiPrefixInt(...)` or
  `stringAsciiPrefixInt(...)` instead of `"prefix" + i` inside loops.
- Return `JsonBodyProducer` directly for default `200 OK` JSON producer routes. Use
  `JsonProducerResponse` only when the route needs custom status or response headers.

Use it only for hot fixed-shape JSON where benchmarks show allocation or serialization cost.

Dynamic DTO:

- Java business code builds a record/list graph.
- DSL-JSON serializes the object graph.
- Rust writes the serialized response.

Use it for complex business responses. If RSS or p99 becomes a problem, optimize that route with direct
writer, raw/precomputed JSON, or native cache based on the actual access pattern.

## Body Limits

Global defaults are configured in `rust-spring.properties`.

- `reactor.rust.http.max-request-body-bytes=1048576`
- `reactor.rust.http.max-response-body-bytes=8388608`
- `reactor.rust.http.max-inflight-body-bytes=33554432`
- `reactor.rust.http.max-inflight-response-bytes=67108864`

Route-level overrides use annotations:

- `@MaxRequestBodySize(bytes = ...)`
- `@MaxResponseSize(bytes = ...)`

Do not raise per-request limits without also sizing in-flight caps. If one route needs a larger body,
prefer a route-level annotation over raising the global default. Large file/export paths should use
`FileResponse`; large JSON exports should use direct writer, precomputed `RawResponse`, or a streaming
design instead of one huge Java `String`.

## File Streaming

Use these for file/download routes:

```properties
reactor.rust.file-stream.chunk-bytes=65536
reactor.rust.static-file.inline-max-bytes=524288
reactor.rust.static-file.max-concurrent-streams=64
```

Guidance:

- `chunk-bytes`: `32768`-`65536` is a good low-RSS range.
- `inline-max-bytes`: keep small; inlined files are pinned in native memory.
- `max-concurrent-streams`: `32` or `64` is a safe low-RSS starting point for large files.
- Returning `503` when the stream bulkhead is full is intentional overload protection.

## Timeouts And Keep-Alive

- `reactor.rust.http.max-request-header-bytes=16384`
- `reactor.rust.http.max-request-headers=64`
- `reactor.rust.http.header-read-timeout-ms=5000`
- `reactor.rust.http.request-body-timeout-ms=10000`
- `reactor.rust.http.idle-timeout-ms=30000`
- `reactor.rust.http.keep-alive-enabled=true`

Slow body timeout closes the connection after bounded waiting. Do not depend on flushing an application response to a client that is still trickling an incomplete request body.

## WebSocket Limits

- `reactor.rust.websocket.max-frame-bytes=1048576`
- `reactor.rust.websocket.outbound-queue-capacity=1024`
- `reactor.rust.websocket.send-timeout-ms=5000`

Path params and query params are passed to `WebSocketSession` during `@OnOpen`.

Example:

```java
@WebSocket("/ws/chat/{roomId}")
public class ChatHandler {
    @OnOpen
    public void onOpen(WebSocketSession session) {
        String roomId = session.getPathParams().get("roomId");
        String token = session.getQueryParams().get("token");
    }
}
```

## Metrics

`GET /metrics` returns Prometheus text and includes native metrics:

- `reactor_native_http_requests_total`
- `reactor_native_http_request_duration_p50_us`
- `reactor_native_http_request_duration_p95_us`
- `reactor_native_http_request_duration_p99_us`
- `reactor_native_jni_queue_duration_p95_us`
- body byte counters
- backpressure and rejection counters
- WebSocket outbound/frame counters

The percentile metrics are fixed bucket estimates to avoid hot-path allocation.

## Logging

Native log level:

- `reactor.rust.log.level=error`

Java framework log level:

- `reactor.rust.java.log.level=warn`

Accepted values: `off`, `error`, `warn`, `info`, `debug`.

Production should stay at `warn` or lower. Avoid per-request logging on hot routes because it works
against the latency/RSS target.
For debugging, temporarily raise the level and reproduce with low concurrency. Do not leave hot-path
debug logging enabled in production.

## Shutdown

`NativeBridge.stopHttpServer()` stops the native accept loop. The example application installs a JVM shutdown hook, so Kubernetes `SIGTERM` stops accepting new connections and existing connections drain according to configured keep-alive/idle timeouts.

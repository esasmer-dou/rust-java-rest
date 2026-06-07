# Performance Guide

This guide helps you pick the right API shape and runtime profile before tuning numbers. The framework
is optimized for Java business logic with a Rust HTTP I/O plane. The goal is low allocation, bounded
native memory, predictable overload behavior, and low RSS.

Not every endpoint should use the same response path. A normal CRUD endpoint should be simple Java
records. A large file should be `FileResponse`. A hot read-heavy JSON endpoint may deserve
`RawResponse`, `@NativeStaticRoute`, or a direct writer.

## Quick Choice

| If your route is... | Start with | Upgrade to | Main properties |
|---------------------|------------|------------|-----------------|
| Normal JSON API | Java record DTOs | Direct primitive query/path bindings | body/response limits, profile |
| Dynamic business JSON | Java records + DSL-JSON | `JsonProducerResponse` or direct writer for the hot response | `json.direct-writer-enabled`, route workload budgets |
| Repeated read-heavy JSON | `RawResponse.json(...)` | `RawResponse.registeredJson(...)` + `@NativeStaticRoute` | `native-cache.max-*` |
| Large download/export | `FileResponse` | `@NativeStaticFileRoute` for immutable files | `file-stream.chunk-bytes`, `static-file.max-concurrent-streams` |
| WebSocket push | Java WebSocket API | Tune bounded outbound queue | `websocket.*` |
| Very memory-sensitive service | `low-rss` profile | Lower per-route limits and stream fanout | max connections, in-flight bytes, response pool caps |
| Burst traffic with long idle windows | Keep normal route path | Idle native trim policy | `native-trim.*`, p99 gate |

## Measurement Discipline

Before comparing latency or RSS, decide what you are measuring:

| Question | Correct benchmark shape | Avoid |
|----------|-------------------------|-------|
| "What is the framework's clean production baseline?" | Minimal production app, `core-runtime`, only app classes | Framework `sample` jar |
| "How does the bundled demo endpoint behave?" | Sample app, explicit endpoint class | Treating sample RSS as production baseline |
| "How does my service behave?" | Your application image with the normal Maven dependency | Reusing framework sample numbers as a pod limit |
| "Can I claim lower RSS?" | Linux container smaps/cgroup evidence after warmup, load, and idle | Windows working set only |

The published normal jar and `core-runtime` jar do not include framework demo handlers, benchmark
routes, or Dubbo sample classes. Those classes exist in `rust-java-rest-*-sample.jar` only. This is
important because class metadata, startup indexes, sample DTOs, and benchmark route state can add
noise to RSS attribution.

The `-AppMode minimal` benchmark image is intentionally closer to a real production app than the
bundled sample jar. It now compiles a tiny user application and generates
`META-INF/reactor/components.idx` plus `META-INF/reactor/routes.idx` during the Docker build. That
keeps strict low-RSS checks clean and avoids measuring a classpath-scan fallback that production apps
should not rely on.

Use this command for production-like RSS attribution:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\linux_smaps_breakdown.ps1 `
  -AppMode minimal `
  -RuntimeProfile micro-rest `
  -ConcurrencyValues 64,256 `
  -DurationSeconds 4 `
  -IdleSeconds 3 `
  -FinalIdleSeconds 6
```

Use `-AppMode sample` only when the benchmark intentionally depends on bundled sample routes. Always
include the app mode in reports.

## JVM Thread Stack Sizing

Start memory-first OpenJ9 pods with `-Xss256k`. Smaller Java thread stacks look attractive on paper,
but reserved stack budget is not the same as resident Kubernetes RSS. The stack pages only matter
when they become resident, and lowering the limit too far can break deep service/RPC/JDBC call
chains.

Use the stack matrix before changing this value:

```powershell
powershell -ExecutionPolicy Bypass -File .\benchmark\xss_anon_matrix.ps1 `
  -RuntimeProfile micro-rest `
  -AppMode minimal `
  -XssValues "256k,192k,160k,128k" `
  -ConcurrencyValues "512" `
  -DurationSeconds 5 `
  -IdleSeconds 2 `
  -FinalIdleSeconds 20
```

Latest local c512 indexed minimal-app evidence showed no stack/runtime failures for `256k`, `192k`,
`160k`, or `128k`, but every row reached route admission and returned some `503`. The measured final
cgroup anon values were `43.512 MiB`, `46.113 MiB`, `50.211 MiB`, and `49.082 MiB` respectively. In
that clean-index run, lowering `-Xss` did not lower final anon; `256k` was best. Keep `256k`; try
`192k` or `128k` only for a specific service after its deepest route path, Dubbo/JDBC path, and error
handlers pass smoke/load tests.

ANTI-PATTERN: making `-Xss128k` a global low-RSS default because one synthetic route survived. The
first failure mode will be a production-only `StackOverflowError` in a deeper user call stack.

## Idle Native Trim Policy

Native allocator retention can be the difference between "RSS drops after load" and "RSS stays warm"
even when Java heap is small. If Linux smaps shows most remaining pressure in anonymous memory and
manual `/diagnostics/native/trim` proves it is releasable, use the idle policy instead of trimming on
request paths:

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

BEST use case: small `micro-rest` or `micro-dubbo` pod with bursty traffic and idle periods. The
policy waits for stable native request counters, checks active connections and active requests, trims
once per idle request-count window, and exposes `reactor_native_trim_*` metrics.

The idle request counter is a user-traffic counter, not the raw HTTP total. `/health`, `/metrics`,
`/metrics/*`, and `/diagnostics/*` are excluded so Kubernetes probes and Prometheus scrapes do not
keep a genuinely idle low-traffic pod permanently in `skipped_not_idle`.

When a background tick sees active or not-yet-idle traffic, it retries at the next idle boundary
instead of sleeping the entire `interval-ms`. That preserves conservative trim timing while reducing
how long post-burst anonymous memory stays warm in low-traffic services.

The background policy uses a soft trim path. It can retain a small response-buffer floor while
reclaiming larger warmed buffers and optionally asking the allocator to return idle pages. Keep
`retain-small=16` as the current starting point for bursty JSON services; it costs only a tiny small
buffer floor and reduced the next-burst p99 risk in the focused matrix. Set it lower only if RSS is
more important than the next burst's cold-start p99. The manual `/diagnostics/native/trim` endpoint
is still a full trim for diagnostics.

ACCEPTABLE: enable it during memory proof runs to validate pod sizing after load plus idle.

ANTI-PATTERN: calling native trim every N requests or from a response path. That can turn allocator
cleanup into a user-visible p99 spike.

Benchmark trim changes with both memory and latency:

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

Pass condition for production: RSS/anon drops enough to matter, p99 does not regress in the normal
benchmark matrix, and trim metrics show skips during active traffic rather than trims during load.

Focused soft-trim A/B, minimal app, `micro-rest`, c64/c512, repeat `2`: retained-floor idle trim
reduced final cgroup current by `14.404 MiB` and final cgroup anon by `14.607 MiB`, but average p99
still regressed by `10.88%` and max p99 by `81.08%` on one raw c64 run. Decision: do not make idle
trim a default profile behavior. Use it as an explicit low-traffic/idle-service RSS reclaim policy
with conservative intervals, and run the normal endpoint matrix before enabling it in a pod profile.

Retain-floor focused matrix, minimal app, `micro-rest`, c64/c512, repeat `1`: allocator trim off did
not reclaim meaningful anon memory. Allocator trim on reclaimed about `15 MiB` final cgroup anon.
Among the tested floor values, `retain-small=16` gave the best current balance: final cgroup anon
`-15.367 MiB`, average p99 `-2.06%`, max p99 `+21.05%`, and max `503` delta `0pp` versus trim-off.
Treat this as tuning evidence, not a universal profile default.

Full endpoint A/B gate, minimal app, `micro-rest`, c64/c256/c512, repeat `3`, using aggressive
`1s` benchmark intervals with `retain-small=16`: final cgroup anon improved by `-14.768 MiB`, final
cgroup current by `-17.263 MiB`, average p99 moved `+4.89%`, max p99 moved `+27.37%`, and max
`503` delta was `+3.021pp`. This confirms the reclaim path is real, but also confirms it is an
opt-in RSS policy rather than a default latency profile behavior.

Conservative soak A/B, same endpoint matrix, repeat `1`, using the production timing
`initial-delay-ms=30000`, `interval-ms=60000`, `min-idle-ms=10000`, final idle `95s`: final cgroup
anon improved by `-20.844 MiB` and final current by `-20.687 MiB`. The trim fired only in final idle
(`trim_success=1`), which is the intended behavior. Because this was a single run and c512 heavy
routes still showed noisy p99/503 movement, use it as soak evidence, not as a high-throughput
approval.

Current native metric note: the framework exposes `reactor_native_http_user_requests_total` in
addition to `reactor_native_http_requests_total`. Idle trim uses the user counter so management
scrapes do not reset the idle window.

Long idle retention check, c512 pressure with 5 minute and 30 minute snapshots: trim-on anon dropped
from `46.781 MiB` after load to `27.258 MiB` at 5 minutes and stayed flat at `27.273 MiB` at 30
minutes. Trim-off stayed at `44.836 MiB` at both 5 and 30 minutes. This is strong evidence that the
post-load anon plateau is allocator retention that idle trim can reclaim, not an obvious growing leak.

## Anon Attribution Gate

When RSS is still above target, do not guess. Run the anon evidence gate and use the output to pick
the next engineering target:

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

What it runs:

- `micro-rest` minimal smaps, to measure the default memory-first REST surface.
- `micro-rest-plus` minimal smaps, to measure the heavy JSON route-budget profile.
- `micro-dubbo` minimal smaps, to measure Dubbo-enabled framework surface without external
  ZooKeeper.
- Conservative trim off/on A/B, using `30s/60s/10s` idle timing instead of request-path trim.
- OpenJ9 javacore/native evidence on the minimal app, so non-heap/runtime behavior has a concrete
  artifact.

How to act on the result:

| Dominant field | Meaning | BEST next action |
|----------------|---------|------------------|
| `heap_used_mib` | Java objects are genuinely retained or live during load | Reduce DTO graph allocation, use producer/direct writer, inspect caches |
| `jit_code_used_mib` | JIT/code cache is meaningful in the memory budget | Test `jitcap`; do not default it without p99 gate |
| `class_metadata_used_mib` | Loaded class surface is too large | Remove optional deps, use `core-runtime`, avoid sample/debug classpath |
| `direct_buffer_mib` | Direct buffers are retained | Tighten pools, stream/file limits, and native trim policy |
| `rust_accounted_mib` | Rust framework pools/cache are retaining memory | Lower response/cache/file-stream pool caps or trim retained floors |
| `thread_stack_budget_mib` | Thread count/stack budget dominates | Reduce worker pools or test `-XcompilationThreads1` / `-Xss` with service stack smoke |
| `anon_residual_mib` | Native/JVM/allocator residual not directly attributed | Compare trim A/B, classpath, JIT cap, JIT thread count, thread pools, and long idle soak |

ANTI-PATTERN: lowering `-Xmx` because RSS is high while `heap_used_mib` is already small. That does
not attack the dominant memory area and can worsen GC or p99.

For Dubbo in Kubernetes, this gate is not a substitute for the sample consumer benchmark with
ZooKeeper discovery enabled. `micro-dubbo` here isolates framework/Dubbo-on runtime cost; the real
consumer project must still be measured with its registry, provider set, RPC method mix, and route
admission settings.

## Endpoint Classes

| Class | Use when | Preferred API | Watch point |
|-------|----------|---------------|-------------|
| `small-json` | Common small JSON request/response | Java record DTOs, primitive direct bindings for hot params | Usually safe default |
| `dynamic-producer-json` | Large DTO-shaped route is hot but the JSON contract should stay stable | `JsonProducerResponse` + `JsonBufferWriter` | Recommended hot-path replacement |
| `dynamic-dto-json` | Legacy Java DTO graph + DSL-JSON comparison path | Java records + DSL-JSON | Object graph cost can dominate under concurrency |
| `direct-json-writer` | Hot endpoint can write predictable JSON without DTO graph | `JsonBufferWriter` or `DirectJsonWriterRegistry` | Writer must stay exact and tested |
| `raw-json` | JSON is already serialized or precomputed | `RawResponse.json(...)`, `RawResponse.registeredJson(...)`, optional `@NativeStaticRoute` | Static response must really be static |
| `file-static` / `file-stream` | File/export path | `FileResponse`, optional `@NativeStaticFileRoute` | Large fanout needs stream bulkhead tuning |

## Response Path Selection

Use this table before changing code. Each path removes a different cost; picking the wrong path can
make the code more complex without improving p99 or RSS.

| Path | Choose it when | Cost removed | Required API/annotation | Watch point |
|------|----------------|--------------|-------------------------|-------------|
| Small JSON | Normal small request/response JSON | None beyond normal optimized record serialization | `@GetMapping` / `@PostMapping` or `@RustRoute`, Java record DTOs | Default path; do not over-optimize early |
| Raw/precomputed JSON | JSON/text/bytes already exist before the handler returns | DTO creation and DSL-JSON serialization | `RawResponse.json(...)`, `RawResponse.text(...)`, route `responseType = RawResponse.class` | Java still passes bytes per request |
| Native cache JSON | Same JSON repeats with explicit key/TTL or is immutable until restart | Repeated body build, repeated Java-to-Rust body transfer | `RawResponse.registeredJson(...)`, `RawResponse.nativeJson(id)`, `NativeBridge.registerDynamicResponse(...)`, optional `@NativeStaticRoute` | Native memory must be capped; not for per-user unique data |
| Direct JSON writer | Hot fixed-shape JSON where allocation/serialization is measured | DTO graph allocation and serializer temporary buffer | `JsonBufferWriter`, `DirectJsonWriterRegistry`, `@DirectQuery*` / `@DirectPath*` where useful | More manual code; needs golden JSON tests |
| Dynamic producer JSON | Existing DTO-shaped route is hot under load | DTO graph allocation while keeping response shape | `JsonProducerResponse` + `JsonBufferWriter` | Must be kept in sync with DTO contract tests |
| Dynamic DTO | Business response is naturally nested/domain-shaped | Nothing; preserves maintainability | Java records with `responseType = MyRecord.class` | Object graph can dominate at high concurrency |

### Small JSON

Start here for normal APIs. Use records for request and response bodies. Add direct primitive
query/path binding only when the route is hot and the scalar parse/allocation cost is visible.
Use `@DirectQuery*` for query params and `@DirectPath*` for path params.

```java
@GetMapping(value = "/products/{id}", requestType = Void.class, responseType = ProductResponse.class)
public ProductResponse product(@PathVariable("id") String id) {
    return productService.find(Long.parseLong(id));
}
```

### Raw/precomputed JSON

Use this when JSON is already produced by a read model, cache, RPC provider, or native component.

```java
@GetMapping(value = "/catalog/raw", requestType = Void.class, responseType = RawResponse.class)
public RawResponse catalogRaw() {
    return RawResponse.json(catalogReadModel.getJsonBytes());
}
```

### Native cache JSON

Use this only for deliberate read-heavy caching. Immutable responses can use `@NativeStaticRoute`.
Dynamic repeated responses should use a bounded key and TTL.

```java
@GetMapping(value = "/catalog/cache", requestType = Void.class, responseType = RawResponse.class)
@DirectQueryInt(value = "version", defaultValue = 1, min = 1, max = 1000)
public RawResponse catalogCache(int version) {
    String key = "catalog:v" + version;
    int nativeId = NativeBridge.lookupDynamicResponse(key);
    if (nativeId > 0) {
        return RawResponse.nativeJson(nativeId);
    }
    byte[] payload = catalogReadModel.renderJson();
    int registeredId = NativeBridge.registerDynamicResponse(
            key,
            payload,
            "Content-Type: application/json\n",
            200,
            300_000L
    );
    return registeredId > 0 ? RawResponse.nativeJson(registeredId) : RawResponse.json(payload);
}
```

Production rule: if the cache key is almost always unique, do not use native cache. Use dynamic DTO,
direct writer, or a proper external cache/read model instead.

Cache miss rule: the miss path still matters. If a native cache miss builds a large Java DTO graph
and only then serializes it, the first request for every key still pays the RSS/GC cost. Prefer one
of these miss producers:

- Read-model bytes from Redis/PostgreSQL/Elasticsearch/materialized storage.
- `JsonBodyProducer` or `JsonBufferWriter` that renders bytes without building the DTO list.
- A small Java decision object plus Rust/native serializer for selected payloads.

### Direct JSON Writer

Use this for hot fixed-shape JSON. The handler returns the byte count written to the direct buffer.
Negative return values are handled by the framework as a capacity retry signal.

```java
@RustRoute(method = "GET", path = "/stats", requestType = Void.class, responseType = StatsResponse.class)
@DirectQueryInt(value = "limit", defaultValue = 10, min = 1, max = 100)
public int stats(ByteBuffer out, int offset, int limit) {
    return JsonBufferWriter.reusable(out, offset)
            .beginObject()
            .fieldString("status", "ok")
            .comma()
            .fieldInt("limit", limit)
            .endObject()
            .result();
}
```

### Dynamic DTO

Keep this for normal business APIs and complex response shapes. It is the maintainability-first path.
If it becomes hot, optimize the specific route rather than changing the whole service style.

```java
@GetMapping(value = "/catalog", requestType = Void.class, responseType = CatalogResponse.class)
public CatalogResponse catalog(@RequestParam("category") String category) {
    return catalogService.buildCatalog(category);
}
```

### Dynamic Producer JSON

Use this when the DTO route is already proven hot and the route must keep the same JSON shape without
allocating the whole object graph per request.

```java
private static final byte[] ITEM_PREFIX = "item-".getBytes(StandardCharsets.US_ASCII);

@RustRoute(method = "GET", path = "/catalog/hot", requestType = Void.class, responseType = JsonBodyProducer.class)
@DirectQueryInt(value = "limit", defaultValue = 100, min = 1, max = 1000)
@RouteAdmission(maxConcurrent = 80, queueTimeoutMs = 150)
public JsonBodyProducer hotCatalog(int limit) {
    return (out, offset) -> {
        JsonBufferWriter writer = JsonBufferWriter.reusable(out, offset);
        writer.beginObject()
                .fieldInt("limit", limit)
                .comma()
                .fieldName("items")
                .beginArray();
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                writer.comma();
            }
            writer.beginObject()
                    .fieldInt("id", i)
                    .comma()
                    .fieldStringAsciiPrefixInt("name", ITEM_PREFIX, i)
                    .endObject();
        }
        return writer.endArray().endObject().result();
    };
}
```

Do not hide allocation inside the writer. `String` concatenation in the loop still creates a Java
object per row. Prefer `fieldStringAsciiPrefixInt(...)`, `stringAsciiPrefixInt(...)`, or explicit
raw ASCII fragments for predictable prefix-plus-primitive values.

Use `JsonBodyProducer` for default `200 OK` JSON. Use `JsonProducerResponse` when the route needs
custom status codes or custom response headers.

### Async Producer JSON

Async producer routes now support direct `int` query binding, so the Rust async bridge can start a
route such as `CompletionStage<JsonBodyProducer> handler(int items)` without building request/query
strings for that scalar. This reduces JNI worker blocking when the route is genuinely asynchronous.

```java
@RustRoute(method = "GET", path = "/catalog/hot/async", requestType = Void.class, responseType = JsonBodyProducer.class)
@DirectQueryInt(value = "limit", defaultValue = 100, min = 1, max = 1000)
@RouteAdmission(maxConcurrent = 80, queueTimeoutMs = 150)
public CompletionStage<JsonBodyProducer> hotCatalogAsync(int limit) {
    return AsyncHandlerExecutor.getInstance()
            .submit(() -> (JsonBodyProducer) new HotCatalogProducer(limit));
}
```

BEST: use this for routes that otherwise block a JNI worker on bounded remote work, native RPC,
database/read-model access, or a measured async producer executor.

ACCEPTABLE: test it for CPU-bound heavy JSON at c512/c1000 only as an opt-in route recipe. It can
increase useful `200` RPS in some pressure points, but it may also raise p99 or retained memory.

ANTI-PATTERN: making all direct/producers async by default. CPU-bound JSON still needs CPU, and an
extra async queue can hide overload until p99 or `503` behavior gets worse.

Low-RSS default:

```properties
reactor.rust.async.direct-buffer.enabled=false
```

This keeps async response frames heap-backed. Direct async buffers are an opt-in experiment:

```properties
reactor.rust.async.direct-buffer.enabled=true
```

Only enable it when the anon evidence gate shows acceptable `direct_buffer_mib`, RSS-after, p99, and
`503` behavior. In short local gates, direct async buffers improved some copy behavior but could keep
direct/native memory warm enough to distort the RSS target.

Short rebuild gate after switching the default to heap async frames, minimal app, `micro-rest`,
c256/c512, repeat `1`: final cgroup current was `60.285 MiB`, final cgroup anon was `52.625 MiB`,
and `direct_buffer_mib` was `0.008 MiB`. In the same run, async producer routes reduced c512 `503`
versus the sync producer variants, but p99 movement was route-dependent. Treat async producer as a
route-local pressure valve, not a profile-wide default.

The bundled benchmark app uses `/api/v1/heavy/dto` for this optimized DTO-shaped path and keeps
`/api/v1/heavy/dto/legacy` for the real object graph comparison.

Targeted local gate after this split, `micro-rest-plus`, c256/c512, repeat `3`: the producer path
improved useful `200` throughput by `1.50x` at c256 and `1.83x` at c512 versus the legacy DTO graph.
RSS stayed broadly in the same band. The c512 producer path still returned about `19%` `503`, so the
next tuning step is route admission for the producer route, not returning to DTO graph allocation.

The first follow-up route-admission matrix tested the optimized DTO-shaped route by itself and
favored `maxConcurrent=128`, `queueTimeoutMs=125`. That was useful but incomplete: a later mixed
workload matrix showed that the sample app behaves better with `96/125` when neighboring heavy
routes are active in the same pod.

Initial `128/125` full dynamic gate, `micro-rest-plus`, c256/c512/c1000, repeat `3`: the producer
path stayed ahead of the legacy DTO graph on useful `200` RPS at every tested concurrency, but c512
still had too much `503` for the intended `micro-rest-plus` operating point:

| C | Producer useful 200 RPS | Legacy DTO useful 200 RPS | Gain | Producer p99 | Producer 503 % |
|---:|------------------------:|--------------------------:|-----:|-------------:|---------------:|
| 256 | `3955.80` | `2501.84` | `1.58x` | `130.85 ms` | `0.03%` |
| 512 | `3311.67` | `2529.80` | `1.31x` | `261.50 ms` | `15.92%` |
| 1000 | `3089.42` | `1498.08` | `2.06x` | `543.44 ms` | `48.48%` |

Read the c512 row carefully: producer throughput is better, but there was one outlier run with high
`503` and p99. This is exactly why route admission must be measured per service. The framework should
reject overload before object graphs and serializers push the pod into unbounded latency or memory
growth. If the service requirement is "nearly every request must be 200 at c512", use a larger pod,
a less memory-first profile, or move that route further toward direct writer/raw/read-model serving.

Mixed workload c512 route-admission matrix for the same producer route:

| Producer maxConcurrent | queueTimeoutMs | Useful 200 RPS | p99 | 503 % | RSS after |
|---:|---:|---:|---:|---:|---:|
| 96 | 125 | `3860.72` | `192.91 ms` | `3.42%` | `75.77 MiB` |
| 112 | 125 | `3822.25` | `205.19 ms` | `2.98%` | `82.28 MiB` |
| 128 | 125 | `3629.62` | `216.95 ms` | `3.60%` | `84.76 MiB` |

Current sample guidance: use `96/125` for `dynamic-producer-json` in `micro-rest-plus`. This is not
because `96` is a universal magic value. It is because the mixed workload preserved useful producer
throughput with lower p99 and RSS than `112` or `128`. For a real service, rerun the matrix with the
actual route set and pod budget.

Follow-up c512 profile validation with the updated `96/125` recipe produced `3703.11` useful
producer `200 RPS`, `198.30ms` p99, and `4.29%` `503`. Treat that as the sample app's measured
starting point, not a global SLO guarantee. RSS after was higher in that validation run than in the
matrix, so memory limits still require a separate Linux smaps/cgroup proof.

Current full dynamic gate with `96/125`, `PlanPreWarm`, c256/c512/c1000, repeat `3`:

| C | Producer useful 200 RPS | Producer p99 | Producer 503 % | Decision signal |
|---:|------------------------:|-------------:|---------------:|-----------------|
| 256 | `3420.95` | `149.09 ms` | `0.14%` | Good, but lower headroom than `128/125` |
| 512 | `3774.52` | `190.73 ms` | `4.31%` | Best current operating point |
| 1000 | `2399.13` | `723.28 ms` | `50.78%` | Controlled overload, not a throughput target |

Compared with the earlier `128/125` full gate, the updated recipe improved c512 useful producer
RPS by `13.98%`, lowered p99 by `70.77ms`, and reduced `503` from `15.92%` to `4.31%`. It reduced
c256 and c1000 producer throughput. That trade-off is intentional for `micro-rest-plus`: stabilize
the expected heavy-route operating range instead of chasing c1000 throughput inside an `80m` memory
budget.

JIT-cap note: if a service uses `openj9-micro-rss-jitcap.options` or `-Xcodecachetotal8m`, do not let
a hot dynamic DTO graph be the deciding route for the whole profile. The measured safer production
move is to keep the normal DTO route for maintainability and add a hot-path `JsonProducerResponse`
or direct writer route for the high-concurrency payload. The `jitcap` gate default now uses
`dynamic-producer-json`; include `dynamic-dto-json` explicitly when you want to test legacy object
graph behavior.

JIT-thread note: `openj9-micro-rss-jitthreads1.options` adds `-XcompilationThreads1`. Use it only
when javacore or `/proc` thread evidence shows JIT compiler workers are a meaningful part of the
small-pod anon budget. The current minimal probe reduced Linux threads from the mid-20s to 17 and
kept direct-buffer attribution near zero, but this is not a universal default. Run the same endpoint
matrix and reject it if warmup, p99, or useful `200` RPS regresses for Java-heavy routes.

Latest full local gate before the dynamic-producer split, `micro-rest`, c64/c256/c512, sample repeat
`3`, minimal smaps repeat `3`: minimal production RSS improved by `5.952 MiB`, but the gate result
was `FAIL` for both common optional use and default-profile candidacy. The reason was not RSS; it
was p99 risk. The legacy dynamic DTO graph regressed at c256/c512, and four endpoint/concurrency
rows crossed the configured p99 regression threshold. Treat JIT-cap as a measured per-service
experiment only and rerun the gate with `dynamic-producer-json` for the optimized hot path.

## Route Design Rules

- Keep normal business handlers in Java and use record DTOs for ordinary endpoints.
- Use direct primitive query/path bindings for hot scalar parameters: `int`, `long`, `boolean`, `double`, `short`.
- Do not treat direct primitive binding as a heavy JSON fix by itself. It removes parameter parsing
  overhead, not the response object graph. A `HEAVY_JSON` route should use producer/direct/raw/native
  response paths when `reactor.optimizer.fail-on-heavy-json-object-graph=true` is enabled.
- Use `RawResponse` when the payload is already bytes/text/JSON and should not be serialized again.
- Add `@NativeStaticRoute` only when a registered `RawResponse` is immutable and can be served by Rust without entering Java.
- Use direct writers only where object graph allocation or serializer temporary buffers are measurable.
- Keep dynamic DTO responses for normal business endpoints when p99 and RSS are inside SLO.
- Avoid large JSON export as a huge Java `String` or `byte[]` per request.
- Avoid increasing one per-request limit without also checking max connections, queue capacity, and total in-flight bytes.

Use `GET /diagnostics/routes` before changing tuning knobs. The route JSON contains
`heavy_json_object_graph` at both summary and route level. A route-level `true` value means the
route is explicitly classified as `HEAVY_JSON`, but the response still goes through Java object graph
serialization. That is a migration candidate, not an admission-tuning problem. Move that route to a
producer/direct/raw/native response path first, then tune `@RouteAdmission` or workload budgets.

Benchmark/demo comparison routes can be marked `@BenchmarkOnlyRoute`. They remain visible in the
route list, but production summary fields and production gates ignore them unless
`reactor.optimizer.fail-on-benchmark-only-routes=true` is enabled. For production decisions, read
`production_legacy` and summary `heavy_json_object_graph`. For sample comparison runs, read
`benchmark_legacy` and `benchmark_heavy_json_object_graph`.

## FileResponse Stream Tuning

`FileResponse` keeps file bytes out of Java heap and JNI frames. Rust opens the file and streams it with
a bounded read chunk controlled by:

```properties
reactor.rust.file-stream.chunk-bytes=65536
```

Recommended: keep low-RSS services around `32 KiB` to `64 KiB` and raise only after measuring p99
and RSS. `128 KiB` to `256 KiB` can make sense in throughput profiles for large downloads. Avoid
globally setting `1 MiB` chunks for high-concurrency downloads without a memory proof run.

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

Recommended: inline only assets/exports small enough to fit your RSS budget. Low-RSS defaults to
`512 KiB`. Avoid setting this to tens or hundreds of MiB to chase throughput; that pins file bodies in
native memory and works against the low-RSS goal. Larger files should stay streamed.

Disk-backed static file streams are protected by:

```properties
reactor.rust.static-file.max-concurrent-streams=128
```

Recommended: size this bulkhead from file size, disk/network capacity, and pod memory budget. Low-RSS
keeps it bounded so overload returns `503` instead of creating unbounded file descriptor and I/O-worker
pressure. Throughput profiles can raise it when the service is dedicated to downloads.

## Runtime Profiles

| Profile | Pick this when | Good default for | Trade-off |
|---------|----------------|------------------|-----------|
| `micro-rss` | You are proving minimum memory on tiny services | Health/config style services | Very strict limits, not for fanout |
| `ultra-low-rss` | You need more room than micro but still prioritize memory | Small APIs with predictable traffic | Can fail fast under concurrency |
| `low-rss` | Memory is the main production constraint | Most first pilots, CRUD/small JSON, bounded file routes | Controlled `503` under overload is expected |
| `balanced` | Java handler blocks on DB/RPC or p99 needs more headroom | Mixed APIs, RPC consumers, heavier handlers | More workers/queues, higher RSS |
| `throughput` | Service is dedicated to high RPS and has a larger pod budget | Gateway/read-heavy services, benchmark runs | More retained memory and worker activity |

The profile is not cosmetic. `low-rss` intentionally prefers fail-fast behavior over hiding overload
inside larger queues. That is useful for memory-sensitive pods because it keeps p99 and RSS bounded.

Low-RSS admission limits still need headroom above expected concurrency. A max connection limit equal
to benchmark concurrency causes false 503s from keep-alive and health-check jitter; current low-RSS
presets use `reactor.rust.http.max-connections=1024` for 512-concurrency gates.

For the smaller `micro-rest` profile, the measured default remains one JNI worker with
`reactor.rust.jni.queue-capacity=128` and `reactor.rust.http.max-connections=512`. Queue `512` was
tested as a focused `small-direct` optimization and improved that one endpoint class, but the full
clean-index endpoint matrix rejected it as a global default because direct-heavy, producer-heavy,
dynamic-producer, and raw-heavy regressed on RPS/p99/503. Use queue `512` only as an explicit
small/direct JSON recipe after your own c256/c512 gate passes. Do not raise `max-connections` unless
connection-reject metrics are non-zero. A follow-up `768` connection cap reduced c512 `503`, but
worsened c256 RPS/p99 and added roughly `+2.8 MiB` final cgroup current, so it remains an explicit
throughput recipe rather than the `micro-rest` default.

The rejection criterion was not theoretical. In the clean-index c512 matrix, queue `512` reduced
`small-direct` 503 from `9.51%` to `0.39%`, but also reduced useful operating headroom on the heavier
routes: `raw-heavy` RPS fell `24.45%`, `producer-heavy` RPS fell `27.58%`, and `dynamic-producer`
503 rose from `8.29%` to `30.00%`. That is the exact reason the profile default remains conservative.

For heavy direct JSON, the latest measured route-local recipe is `maxConcurrent=80` and
`queueTimeoutMs=150`. This is not a faster setting; it is a lower-reject setting. In the c256/c512
repeat-3 gate, direct-heavy c512 `503` moved from `28.83%` to `2.43%`, while direct-heavy c512 RPS
fell from `5151.98` to `3739.78` and p99 moved from `229.24ms` to `274.73ms`. Use it only when the
service needs fewer overload responses and accepts lower raw throughput.

```properties
reactor.runtime.profile=micro-rest-plus
reactor.rust.route-budget.heavy-json-direct.route-admission.max-concurrent=80
reactor.rust.route-budget.heavy-json-direct.route-admission.queue-timeout-ms=150
```

BEST: mark the route with `@RouteWorkload(value = HEAVY_JSON, budget = "heavy-json-direct")` and let
`micro-rest-plus` apply the measured budget. ACCEPTABLE: use a route-specific override when one real
route needs a different value. ANTI-PATTERN: make it a global connection/JNI queue default or combine
it blindly with higher global queues/workers.

Precedence is explicit and should stay that way:

```text
@RouteAdmission defaults < route-workload defaults < named route-budget defaults < route-specific properties
```

Use workload defaults for broad classes such as all `HEAVY_JSON` routes. Use named route budgets when
direct writer, producer writer, and legacy DTO routes need different measured behavior. Use
route-specific properties only after your own matrix proves a single endpoint needs a different
budget.

## Tuning by Use Case

### Hot Small Direct JSON

If a tiny bodyless route returns the same payload until restart, do not send it through Java/JNI at
all. Register it once and let Rust serve it from the native static response table.

```java
private static final RawResponse CANDIDATES_DIRECT =
        RawResponse.registeredJson(CandidateResponseJsonWriter.INSTANCE.precomputedBytes());

@RustRoute(
        method = "GET",
        path = "/api/v1/candidates/direct",
        requestType = Void.class,
        responseType = RawResponse.class
)
@NativeStaticRoute
public RawResponse candidatesDirect() {
    return CANDIDATES_DIRECT;
}
```

Use this for immutable config/read-model payloads, precomputed lookup responses, and health/config
JSON that does not depend on the request. Do not use it for personalized data, query-dependent data,
authorization-dependent data, DB/RPC calls, or any route whose value changes per request.

If the route is dynamic but still hot and bodyless, keep it as a direct writer first. If metrics show
`reactor_native_jni_queue_full_total` and the service explicitly accepts an extra priority JNI worker
to reduce `503`, test this property override without changing code:

```properties
reactor.rust.jni-admission.get.api.v1.candidates.direct.max-pending=512
reactor.rust.jni-admission.get.api.v1.candidates.direct.queue-timeout-ms=0
```

Use this only when metrics show `reactor_native_jni_queue_full_total` on the route's workload and a
full endpoint matrix proves heavy/raw routes do not regress. Measure c256/c512 with `200` counts,
p99, `reactor_native_jni_admission_*`, `reactor_native_jni_priority_*`, and RSS. Unlike
`@RouteAdmission`, this permit is released when a JNI worker starts the job. In the local gates, the
lane reduced `503` but was not accepted as a new `micro-rest` default; keep it service-specific.
instead of poisoning the global JNI queue.

### Normal JSON API

Start with:

```properties
reactor.runtime.profile=low-rss
reactor.rust.http.max-request-body-bytes=1048576
reactor.rust.http.max-response-body-bytes=8388608
reactor.rust.http.max-inflight-response-bytes=16777216
reactor.rust.jni.queue-capacity=512
```

Move to `balanced` when the Java handler spends time in DB/RPC calls and the JNI queue starts showing
tail latency. Do not raise queue capacity first; it can hide overload and increase memory.

### Read-Heavy JSON

Use `RawResponse` when the response is already serialized. Use `@NativeStaticRoute` only when the
response is immutable until restart.

```properties
reactor.rust.native-cache.max-entries=256
reactor.rust.native-cache.max-bytes=4194304
reactor.rust.native-cache.ttl-ms=300000
```

Raise cache size only when the hit ratio is high and idle RSS proves the cache is inside budget.

### Large File or Export

Use `FileResponse`, not Java `byte[]`. For immutable file identity, add `@NativeStaticFileRoute`.

```properties
reactor.rust.file-stream.chunk-bytes=65536
reactor.rust.static-file.inline-max-bytes=524288
reactor.rust.static-file.max-concurrent-streams=64
```

For low-RSS pods, start with `32` or `64` concurrent streams. Raise only after measuring p99, RSS,
file descriptors, and `503` rate. If clients require eventual success, put retry/queue behavior at the
caller or gateway layer instead of making the framework queue unbounded file work.

### Hot JSON Without DTO Graph

Use direct writer only for routes where DTO allocation is visible in benchmark data.

```properties
reactor.rust.json.direct-writer-enabled=true
reactor.rust.json.writer-initial-bytes=4096
reactor.rust.json.writer-retain-max-bytes=65536
```

If writer retain size is too high, RSS may stay elevated after load. If it is too low, the route may
retry/grow buffers more often. Tune with the actual response size.

### WebSocket Push

Use bounded queues by default:

```properties
reactor.rust.websocket.max-frame-bytes=1048576
reactor.rust.websocket.outbound-queue-capacity=1024
reactor.rust.websocket.send-timeout-ms=5000
```

For slow consumers, smaller queues plus close/fail behavior is usually safer than large queues that
retain memory.

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

## Latest v3.2.2 Production Gate

Run ids:

- Route matrix: `release_gate_routes_20260607_151500`
- Anon evidence: `anon_gate_minimal_20260607_154000`

Settings: `micro-rest-plus`, OpenJ9/Semeru 21, `cpu1`, framework-only route matrix,
c64/c256/c512/c1000, repeat `3`, plan pre-warm enabled. The anon gate used the minimal production
app, c64/c256/c512, `micro-rest`, `micro-rest-plus`, `micro-dubbo`, trim off/on A/B, and javacore
evidence.

Route diagnostics:

| Diagnostic | Result |
|------------|-------:|
| Production routes | 45 |
| Benchmark-only routes | 1 |
| Production heavy JSON object-graph routes | 0 |
| Benchmark heavy JSON object-graph routes | 1 |

Current route matrix:

| Class | C | Avg RPS | Avg p99 | Reject % | Avg RSS after |
|-------|--:|--------:|--------:|---------:|--------------:|
| `raw-json` | 64 | 8764 | 21.49 ms | 0.00% | 83.16 MiB |
| `raw-json` | 256 | 8345 | 70.63 ms | 0.00% | 82.93 MiB |
| `raw-json` | 512 | 7942 | 148.83 ms | 0.16% | 82.90 MiB |
| `raw-json` | 1000 | 4774 | 1270 ms | 2.82% | 82.21 MiB |
| `dynamic-producer-json` | 256 | 2319 | 227.06 ms | 0.77% | 82.35 MiB |
| `dynamic-producer-json` | 512 | 3198 | 291.03 ms | 22.74% | 83.43 MiB |
| `direct-json-writer` | 256 | 2293 | 219.08 ms | 0.50% | 82.95 MiB |
| `direct-json-writer` | 512 | 2798 | 376.16 ms | 16.57% | 84.04 MiB |
| `dynamic-dto-json` legacy | 512 | 3875 | 380.14 ms | 70.07% | 83.29 MiB |

Anon attribution:

| Case | Current | Anon | Heap | JIT | Class metadata | Residual anon | Decision |
|------|--------:|-----:|-----:|----:|---------------:|--------------:|----------|
| `micro-rest` | 66.71 MiB | 50.18 MiB | 4.16 | 2.50 | 10.59 | 29.34 | Baseline memory-first REST |
| `micro-rest-plus` | 66.81 MiB | 50.47 MiB | 5.06 | 2.47 | 10.56 | 28.71 | Similar RSS with heavier budgets |
| `micro-dubbo` | 70.92 MiB | 50.48 MiB | 3.45 | 2.55 | 10.59 | 30.11 | Dubbo-enabled minimal surface |
| conservative trim on | 46.97 MiB | 31.87 MiB | 3.29 | 2.46 | 10.63 | 12.02 | Low-traffic idle reclaim only |

Decision:

- BEST: use raw/precomputed/native/static paths for read-heavy JSON.
- BEST: use `JsonBodyProducer` or `JsonProducerResponse` for hot DTO-shaped JSON before increasing
  workers or queues.
- ACCEPTABLE: use `micro-rest-plus` when c256/c512 heavy JSON needs lower reject rates than
  `micro-rest`, but still accept controlled overload.
- ANTI-PATTERN: claiming this profile is 200-only at c512/c1000. The measured behavior is bounded
  overload, not infinite queueing.
- ANTI-PATTERN: lowering `-Xmx` to solve RSS. Heap is not the dominant area in the anon evidence.

## Historical Low-RSS Benchmark Gate

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

Recommended: use `32` or `64` as the low-RSS starting point for large immutable file routes. Higher stream
limits accept more file work but worsen p99 and RSS. `503` under overload is a deliberate backpressure
signal and should be surfaced to clients with retries or queueing at the caller/gateway layer.

## Required Release Checks

```powershell
mvn -q test
cargo test
powershell -ExecutionPolicy Bypass -File .\benchmark\container_benchmark.ps1 -RuntimeProfile low-rss -Duration 10s -Warmup 2s -ConcurrencyLevels "64,256,512,1000" -RepeatCount 3 -RandomSeed 20260531 -EndpointClasses "small-json-legacy,small-json-direct,echo-parse,echo-raw,dynamic-producer-json,dynamic-dto-json,direct-json-writer,rust-json-writer,raw-json,native-cache-json,file-static,file-stream"
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

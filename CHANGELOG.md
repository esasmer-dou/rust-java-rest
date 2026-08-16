# Changelog

All notable changes to the Rust-Java REST Framework will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

## [4.5.3] - 2026-08-16

### Changed

- Packed enabled HTTP telemetry capture state into one 32-bit request field, reducing the native
  request guard from 32 bytes to at most 24 bytes.
- Simplified the exact rotating sampling window while preserving bounded frequency, periodic-route
  coverage, sampled aggregates, and exact 5xx accounting.
- Moved the isolated exporter to low-priority batch scheduling on Linux and the lowest normal
  thread priority on Windows so bounded export work yields to the HTTP data plane.

### Compatibility

- Java annotations, handlers, services, validation, records, and business logic are unchanged.
- REST native ABI remains `29`, Glowroot native ABI remains `3`, Dubbo ABI remains `7`, and Redis ABI
  remains `6`.
- Windows x64 and Linux glibc x64 binaries come from the same clean `rust-spring v4.5.3` source
  revision and are validated by the packaged SHA-256 provenance manifest.

## [4.5.2] - 2026-08-16

### Fixed

- Restored the proven embedded collector lifecycle: validate the collector at startup, release the
  startup h2 connection, and reconnect only for each bounded export window. This prevents an idle
  collector connection from competing with the Hyper request plane under a one-vCPU budget.
- Kept the standalone Spring exporter on one reused bounded connection and retained the lower
  operating-system scheduling priority in both modes.

### Compatibility

- Java annotations, handlers, services, validation, records, and business logic are unchanged.
- REST native ABI remains `29`, Glowroot native ABI remains `3`, Dubbo ABI remains `7`, and Redis ABI
  remains `6`.
- Windows x64 and Linux glibc x64 binaries come from the same clean `rust-spring v4.5.2` source
  revision and are validated by the packaged SHA-256 provenance manifest.

## [4.5.1] - 2026-08-16

### Changed

- Prepared the bounded Glowroot collector connection before the first aggregate boundary so initial
  DNS, TCP, HTTP/2, and collector-init work does not compete with first-minute application traffic.
- Lowered the isolated Rust exporter thread's operating-system scheduling priority while keeping it
  separate from Hyper/Tokio request workers.

### Compatibility

- Java annotations, handlers, services, validation, records, and business logic are unchanged.
- REST native ABI remains `29`, Glowroot native ABI remains `3`, Dubbo ABI remains `7`, and Redis ABI
  remains `6`.
- Windows x64 and Linux glibc x64 binaries come from the same clean `rust-spring v4.5.1` source
  revision and are validated by the packaged SHA-256 provenance manifest.

## [4.5.0] - 2026-08-14

### Added

- Added runtime-switchable bounded Glowroot profiles through `GlowrootTelemetry`: `micro`, `jvm`,
  `sql`, `full`, and `diagnostic`.
- Added generation-aware reusable SQL descriptors, automatic annotated-handler error capture, JVM
  memory/GC gauges, and authorized thread-dump, heap-histogram, and heap-dump requests.
- Added profile release diagnostics for active/retired native bytes, JNI probe ownership, transition
  ids, release latency, allocator trim, and timeout state.
- Added `configuredProfile()` and `restoreConfiguredProfile()` for returning from a temporary
  incident profile to the startup baseline.

### Changed

- Isolated Glowroot export and profile reclamation on one dedicated `256 KiB` Rust thread instead of
  sharing Hyper/Tokio server workers.
- Moved JVM bean discovery, heap/non-heap/pool/GC sampling, diagnostic orchestration, and diagnostic
  file I/O from Java helper code into the isolated Rust telemetry runtime.
- Made profile downgrade synchronous and transactional. Returning to `micro` drops optional native
  queues/slots, Rust-owned JNI JVM-probe references, and in-flight profile-derived export data
  before the API returns.
- Widened SQL profile tokens to a positive 32-bit generation namespace and included error-frame
  structures plus String allocator metadata in the startup memory ceiling.

### Fixed

- Made telemetry route/SQL registration and Throwable inspection fail open so agent failures cannot
  alter handler responses or replace the original business exception.
- Replaced exception-class route labels with one fixed native `Java Error` identity; profile
  downgrade no longer leaves error metadata in the permanent HTTP route table.
- Made pending profile reclamation restart-safe when an exporter stops after consuming the original
  release notification.

### Compatibility

- REST native ABI is `29` and Glowroot native ABI is `3`; Dubbo ABI remains `7` and Redis ABI
  remains `6`.
- Windows x64 and Linux glibc x64 binaries come from the same clean `rust-spring v4.5.0` source
  revision and are validated by the packaged SHA-256 provenance manifest.

---

## [4.4.1] - 2026-08-13

### Fixed

- Replaced both packaged native libraries with clean CI artifacts built from `rust-spring v4.4.2`.
- Aligned the native runtime and Maven provenance manifest on the complete 40-character source
  commit SHA.
- Added a packaged-runtime regression test that validates the real native build information before
  server startup.

### Compatibility

- REST ABI remains `28`, Dubbo ABI remains `7`, Redis ABI remains `6`, and Glowroot ABI remains `1`.
- Java annotations, handlers, services, validation, route behavior, and business logic are
  unchanged.
- Deploy only the DLL/SO packaged with `rust-java-rest:4.4.1`.

---

## [4.4.0] - 2026-08-13

### Added

- Added the opt-in Rust-first Glowroot micro telemetry plane for bounded HTTP, native Dubbo/Redis,
  process, exporter-health, and sampled slow/error trace data.
- Added `/diagnostics/glowroot`, Prometheus exporter metrics, strict capability selection, and
  bilingual configuration/release guidance.
- Added clean-CI native metadata and checksum ingestion with full source-revision provenance.

### Changed

- Advanced REST native ABI to `28` and added Glowroot ABI `1`; Dubbo ABI remains `7` and Redis ABI
  remains `6`.
- Moved native telemetry configuration before route registration so route slots are allocated once.
- Updated benchmark gates to isolate runner/application CPU roles and compare paired RSS, cgroup,
  RPS, p99, error, thread, and startup deltas.

### Compatibility

- Java REST annotations, handlers, services, records, validation, and business logic are unchanged.
- Spring Boot support is delivered by the separate `java-rust-glowroot-spring-boot-starter:0.2.0`;
  no Spring dependency is added to the REST runtime.
- Deploy only the DLL/SO packaged with the coordinated `4.4.0` artifact.

---

## [4.3.0] - 2026-08-11

### Added

- Added build-time route metadata, native artifact digest validation, and OpenJ9 shared class cache
  preparation without adding request-time reflection.
- Added resident-image, crossover, and startup benchmark gates for generated invocation, echo,
  small direct responses, RSS, and startup time.
- Added explicit generated writer diagnostics and tests for packaged native extraction and startup
  provenance.

### Changed

- Resolved generated route invokers and explicit direct JSON writers before traffic starts.
- Made `@GenerateDirectJsonWriter` the explicit opt-in for generated direct response writers;
  `@Response` remains a normal DTO marker.
- Reduced startup registry retention, empty-frame work, generated primitive binding overhead, JSON
  buffer retention, and duplicate native static-response lookup/header copying.
- Rebuilt Windows and Linux native artifacts from clean native source revision `b26285a1d973`.

### Compatibility

- Java handlers, services, records, REST annotations, validation, and business logic are unchanged.
- REST ABI remains `26`; Dubbo ABI remains `7`; Redis ABI remains `6`.
- Use the DLL/SO packaged with `4.3.0` even though the ABI is unchanged, because the native
  implementation and artifact hashes changed.

---

## [4.2.0] - 2026-08-10

### Added

- Added the `rust-java-platform-parent`, BOM, focused starters, Maven build gates, and integration
  smoke module.
- Added generated configuration binding, validation, exception handlers, primitive bindings,
  request guards, health contributors, OpenAPI routes, HTTP clients, schedulers, security, and
  tracing extension points.
- Added `HttpResponse` and `ProblemDetail` for explicit typed success and error responses.
- Added bilingual project, platform, example, sample, benchmark, and native maintainer guides.

### Changed

- Made generated startup descriptors and immutable native route lookup the normal production path.
- Moved optional compatibility scanning behind the separate `rust-java-platform-compat` module.
- Updated the project generator to use the platform parent and the smallest starter for each service
  shape.
- Advanced REST native ABI from `24` to `26`; Dubbo ABI remains `7` and Redis ABI remains `6`.

### Compatibility

- Java handlers, services, records, REST annotations, validation rules, and business logic remain in
  Java.
- Existing explicit `RestApplication.Module` applications remain supported.
- The packaged DLL/SO must be upgraded with the Java artifact because REST ABI `26` is startup-gated.

---

## [4.1.0] - 2026-08-03

### Added

- Added `@ReactorApplication` and `@RestController` as the normal declarative application model.
- Added build-time constructor, `@Bean`, and direct route invoker generation.
- Added strict generated DI selection with `@Primary` and `@Qualifier` support.
- Added `LongKeyAdmission` for bounded per-key command concurrency.
- Added generated project shapes for REST, cache reader/writer, static Dubbo, and ZooKeeper Dubbo
  applications.

### Changed

- Kept annotation processor classes and processor service metadata in the build-only `codegen` JAR.
- Made explicit `scanBasePackages` replace, rather than extend, the application package root.
- Improved generated startup errors for ambiguous beans and checked constructor or `@Bean` failures.
- Reduced handwritten wiring in the Dubbo and cache sample applications.

### Compatibility

- Java handlers, services, records, REST annotations, response types, and native ABI `24/7/6` are
  unchanged.
- Existing `RestApplication.Module` configurations remain supported for intentionally isolated
  runtime artifacts.

---

## [4.0.0] - 2026-07-17

### Added

- Added build-time startup, route, direct JSON writer, and JDBC record mapper generation in a
  separate `codegen` classifier.
- Added explicit application descriptors, runtime profile plans, dependency health probes, and
  compile-verified standalone examples.
- Added English and Turkish configuration, operations, troubleshooting, and declarative development
  guides.
- Made generated default package names safe when an artifact segment is a Java keyword.

### Changed

- Kept runtime and core-runtime artifacts free of annotation processor and sample classes.
- Replaced handwritten startup indexes and repeated framework wiring with generated artifacts.
- Tightened route validation, component construction, low-allocation parameter binding, and native
  idle-memory configuration.

### Removed

- Removed obsolete `FastMapV2`, `CleanerUtils`, `MemoryOptimizedConfig`, manual
  `StartupIndexGenerator`, and `RestApplication.sleepForever()` compatibility APIs.
- Removed duplicate allocation-based primitive parser helpers and the old native trim property alias.

### Compatibility

- Java handlers, services, records, REST annotations, business rules, and native ABI `24/7/6` remain
  unchanged.
- Applications using a removed compatibility helper must migrate using the table in the README.

---

## [3.4.1] - 2026-07-16

### Changed

- Refreshed the packaged Windows and Linux native runtime from a clean `rust-spring` source
  revision.
- Advanced the aligned Dubbo native contract from ABI `6` to ABI `7`; REST ABI `24` and Redis ABI
  `6` are unchanged.
- Added stale idle Dubbo connection expiry and liveness validation to the shared native runtime.
- Updated native provenance, SHA-256 validation, and release workflow gates for the `24/7/6` ABI
  line.

### Compatibility

- REST annotations, handler signatures, DTO serialization, runtime profiles, and response APIs are
  unchanged.
- Applications that use native Dubbo must upgrade `rust-java-rest` and `java-rust-dubbo` together.

---

## [3.3.1] - 2026-07-15

### Added

- Added `RestApplication.run(...)`, `runStandard(...)`, and async launcher overloads for explicit
  named modules without exposing the advanced builder in normal application entry points.

### Changed

- Reduced the bundled sample entry point to one module-selection call while keeping handler and
  resource composition explicit in `ReactorRustHyperModule`.
- Registered the nested feature sample records with DSL-JSON code generation so the documented
  feature endpoints return JSON instead of a missing-serializer `500` response.
- Kept the existing builder API source-compatible for custom ports, containers, tests, and unusual
  lifecycle wiring.
- Native REST, Dubbo, and Redis ABI versions are unchanged; this is a Java API and documentation
  release.

---

## [3.2.5] - 2026-07-03

### Changed

- Refreshed packaged Windows and Linux native resources for the current REST, Redis cache, and Dubbo
  companion library line.
- Documented the supported version set: `rust-java-rest:3.2.5`, `java-rust-cache:0.2.1`, and
  `java-rust-dubbo:0.2.0`.
- Clarified that Dubbo native response handles require the current native resource package and must
  not be published over an older Maven version.

---

## [3.2.2] - 2026-06-07

### Changed

- Made `JsonProducerResponse` and `DirectJsonResponse` header maps lazy. Default no-extra-header
  producer/direct JSON responses no longer allocate a `LinkedHashMap` before encoding the standard
  JSON content type; mutable `getHeaders().put(...)` behavior is preserved.
- Added `JsonBufferWriter.fieldStringAsciiPrefixInt(...)` and
  `JsonBufferWriter.stringAsciiPrefixInt(...)` so hot direct/producer JSON loops can write
  predictable prefix-plus-int strings without caller-side `String` concatenation.
- Added direct `JsonBodyProducer` response support. Hot producer routes can now return
  `JsonBodyProducer` directly for default `200 OK` JSON, avoiding the per-request
  `JsonProducerResponse` wrapper allocation. `JsonProducerResponse` remains the API for custom
  status codes and custom headers.
- Added opt-in async `JsonBodyProducer` direct query-int support. Routes returning
  `CompletionStage<JsonBodyProducer>` or `CompletionStage<JsonProducerResponse>` can now use
  `@DirectQueryInt` scalar invocation without building query/header strings for that hot scalar.
- Extended direct scalar `int` binding to `RawResponse handler(int value)` for read-model/cache
  routes that already return serialized bytes or native cached payload ids.
- Changed async response frame buffers to heap-backed by default via
  `reactor.rust.async.direct-buffer.enabled=false`. Direct async buffers remain available as an
  explicit RSS/p99-gated optimization instead of a low-RSS default.
- Added short rebuild-gate evidence for the heap-backed async default: minimal `micro-rest`
  c256/c512 ended with `60.285 MiB` cgroup current, `52.625 MiB` cgroup anon, and
  `0.008 MiB` direct-buffer attribution. Async producer routes reduced some c512 overload rows, but
  remain opt-in and route-local.
- Added optional OpenJ9 startup preset `openj9-micro-rss-jitthreads1.options`. It keeps JIT enabled
  and adds `-XcompilationThreads1` for services where javacore evidence shows JIT compiler thread
  surface is part of the anonymous memory budget. It is an A/B preset, not a default.
- Added explicit route workload metadata with `@RouteWorkload`. Heavy JSON routes can now declare a
  workload class and named budget such as `heavy-json-direct` or `heavy-json-producer`; startup route
  diagnostics expose the workload and budget for each route.
- Promoted `micro-rest-plus` from a benchmark-only recipe to a runtime profile. It inherits
  `micro-rest` sizing and applies measured route-budget defaults for heavy JSON routes without
  increasing global JNI queue, worker, or connection limits.
- Added optional production gate `reactor.optimizer.fail-on-heavy-json-object-graph`. When enabled,
  routes marked `HEAVY_JSON` fail startup if they still use a legacy/exact object-graph serialization
  path instead of direct writer, `JsonProducerResponse`, `RawResponse`, or native static response.
- Tightened the heavy JSON production gate so direct query/path primitive bindings no longer hide a
  DTO object-graph response. Direct scalar binding removes parameter parsing overhead only; heavy
  JSON routes still need producer/direct/raw/native response paths to pass the gate.
- Added route diagnostics and startup metric visibility for heavy JSON object-graph routes.
  `/diagnostics/routes` now exposes `heavy_json_object_graph` at summary and route level, and
  Prometheus metrics include `reactor_route_plan_heavy_json_object_graph`.
- Added `@BenchmarkOnlyRoute` for sample/benchmark comparison routes. Benchmark-only routes stay
  visible in diagnostics, but production route summaries and production gates separate them through
  `benchmark_only`, `benchmark_legacy`, and `benchmark_heavy_json_object_graph`. The optional
  `reactor.optimizer.fail-on-benchmark-only-routes=true` gate can reject them in real production
  services.
- Replaced stale benchmark/memory helper overrides that still carried the rejected `128/125`
  direct-heavy route recipe with the new `micro-rest-plus` profile budget source.
- Split the bundled heavy DTO benchmark route into two explicit paths:
  - `/api/v1/heavy/dto` now returns the same DTO-shaped JSON through `JsonProducerResponse`, avoiding
    per-request `HeavyResponse -> HeavyItem -> HeavyMetadata` object graph allocation on the hot path.
  - `/api/v1/heavy/dto/legacy` keeps the real Java DTO graph + DSL-JSON path for apples-to-apples
    regression comparison.
- Added `dynamic-producer-json` as the recommended benchmark endpoint class for hot dynamic JSON.
  The older `dynamic-dto-json` class now points to the legacy DTO graph path.
- Updated JIT-cap gate defaults to measure the recommended dynamic producer path by default while
  still allowing explicit legacy DTO graph testing.
- Added targeted c256/c512 repeat-3 benchmark evidence for the split: `dynamic-producer-json`
  improves useful `200` throughput by `1.50x` at c256 and `1.83x` at c512 versus legacy
  `dynamic-dto-json`, with RSS staying in the same broad band.
- Tuned the sample optimized DTO-shaped route admission to `maxConcurrent=128`,
  `queueTimeoutMs=125` after a c256/c512 repeat-3 matrix. Compared with the previous `80/125`
  setting, c512 useful `200 RPS` improved from `3064.78` to `4120.37` and average `503%` dropped
  from `13.89%` to `1.46%`.
- Ran the full dynamic gate with the selected `128/125` recipe across
  `dynamic-producer-json`, legacy `dynamic-dto-json`, `direct-json-writer`, and `raw-json` at
  c256/c512/c1000, repeat `3`. The optimized producer path stayed ahead of the legacy DTO graph on
  useful `200 RPS`: `1.58x` at c256, `1.31x` at c512, and `2.06x` at c1000. c512 still showed one
  overload outlier, so the recipe remains measured guidance rather than a universal default.
- Added optional plan-level benchmark pre-warm and extended the route-admission matrix runner to
  support mixed endpoint sets with per-class aggregate output. This exposed that single-route
  admission tuning overfit the sample producer route.
- Moved the bundled `GET /api/v1/candidates/direct` sample route to
  `RawResponse.registeredJson(...) + @NativeStaticRoute`. This route is intentionally immutable and
  now demonstrates the correct Rust-served path for hot bodyless/static JSON instead of spending JNI
  capacity on a precomputed payload.
- Removed the remaining DTO graph build from the benchmark native-cache miss path. The bundled
  heavy cache sample now generates miss payload bytes through the direct heavy JSON writer before
  registering them in the native cache; the legacy DTO graph remains only on
  `/api/v1/heavy/dto/legacy` for explicit comparison.
- Re-ran the `micro-rest` small-direct repeat-3 gate after the native static change. Metrics showed
  `reactor_native_jni_queue_full_total=0` and only the metrics scrape entering JNI; remaining c512
  `503` came from `reactor_native_http_connections_rejected_total`, not JNI queue pressure.
- Re-ran the mixed c256/c512 clean-index matrix after the native static change. The rejected
  priority JNI lane remains opt-in only; native static improves the small static route without adding
  a global queue or extra priority worker.
- Rejected broad heavy-route fixes that traded one bottleneck for another:
  `get.api.v1.heavy=128/125` lowered direct-heavy c512 `503` but regressed RPS/p99 across the mixed
  matrix; `jni.workers=2, queue=256` helped producer/dynamic routes but hurt raw/small paths and did
  not fix direct-heavy. The accepted recipe is route-local and explicit: direct-heavy
  `maxConcurrent=80`, `queueTimeoutMs=150` for services that prefer fewer `503` over maximum RPS.
- Updated the sample `dynamic-producer-json` route recipe from `128/125` to `96/125` after the mixed
  c512 matrix. Under the mixed plan, `96/125` produced `3860.72` useful `200 RPS`, `192.91ms` p99,
  `3.42%` `503`, and `75.77 MiB` RSS, beating `128/125` on useful RPS, p99, and RSS.
- Revalidated the updated `micro-rest-plus` profile at c512 with the mixed dynamic endpoint set:
  `dynamic-producer-json` produced `3703.11` useful `200 RPS`, `198.30ms` p99, and `4.29%` `503`.
  RSS after was higher in this run, so docs now keep route-admission tuning separate from pod memory
  sizing proof.
- Ran the current full dynamic gate with `96/125`, `PlanPreWarm`, c256/c512/c1000, repeat `3`.
  The producer route improved c512 versus the earlier `128/125` full gate: useful `200 RPS`
  `3311.67 -> 3774.52`, p99 `261.50ms -> 190.73ms`, and `503%` `15.92% -> 4.31%`.
  c256 and c1000 throughput decreased, which is accepted for the memory-first `micro-rest-plus`
  recipe.
- Documentation now distinguishes production package contents from bundled sample/benchmark
  artifacts more explicitly. The existing `3.2.1` package already excludes sample/benchmark classes
  from the normal jar, `core-runtime`, sources, and javadocs.
- Benchmark documentation now recommends the minimal production app as the default RSS attribution
  target. The bundled sample app remains the correct target only for bundled demo-route behavior.
- Removed the legacy request-count native trim branch from Java handler entry points. Native memory
  release is no longer attached to arbitrary user requests.
- Added optional `reactor.rust.native-trim.*` idle policy. It runs from a daemon thread only after
  native request activity is stable and active connections/requests are below the configured
  thresholds, with `reactor_native_trim_*` metrics for attempts, skips, success, errors, and last
  duration.
- Changed the background idle trim path to call ABI `21`
  `NativeBridge.releaseNativeMemoryRetaining(...)`, so memory-first services can keep a tiny
  response-buffer warm floor (`retain-small` default `2`) while reclaiming larger pools and
  optionally trimming the platform allocator. The manual `/diagnostics/native/trim` endpoint remains
  a full diagnostic trim.
- Rebuilt Windows DLL and Linux SO native resources for ABI `21`, and added a Java native ABI smoke
  test so stale bundled binaries fail early.
- Extended `linux_smaps_breakdown.ps1` to capture `/metrics` per phase and include native idle trim
  attempts/skips/success/duration in the summary CSV and markdown report.
- Added `idle_trim_ab_gate.ps1` for trim off/on A/B validation across repeat runs. The current
  minimal `micro-rest` c64/c256/c512 repeat-3 gate showed final cgroup anon improvement
  `-14.484 MiB`, but average p99 regression `+15.68%`; idle trim remains explicit and disabled by
  default.
- Reran a focused retained-floor soft-trim A/B on minimal `micro-rest`, c64/c512, repeat `2`:
  final cgroup anon improved by `-14.607 MiB`, average p99 regressed by `+10.88%`, and max p99
  regressed by `+81.08%` on one raw c64 run. The production decision remains conservative opt-in,
  not a default profile behavior.
- Added `native_trim_policy_matrix.ps1` and measured retain-small `2/8/16` with allocator trim
  on/off. Allocator trim off did not reclaim meaningful anon memory; `retain-small=16` with
  allocator trim on is now the opt-in starting point because the focused matrix showed about
  `-15.367 MiB` final cgroup anon with lower p99 risk than smaller floors.
- Completed the full retained-trim A/B gate for `retain-small=16`, minimal `micro-rest`,
  c64/c256/c512, repeat `3`: final cgroup anon `-14.768 MiB`, final cgroup current `-17.263 MiB`,
  average p99 `+4.89%`, max p99 `+27.37%`, max `503` delta `+3.021pp`.
- Completed the conservative production-timing soak with `30s/60s/10s`, final idle `95s`,
  repeat `1`: final cgroup anon `-20.844 MiB`, final current `-20.687 MiB`, trim fired once in
  final idle. This remains low-traffic/idle-service evidence, not a throughput default.
- Extended `linux_smaps_breakdown.ps1` with `-FinalIdleSnapshotSeconds` for same-container long
  idle soak snapshots. A c512 5-minute/30-minute soak showed trim-on anon stayed flat after reclaim
  (`27.258 MiB -> 27.273 MiB`), while trim-off stayed high (`44.836 MiB -> 44.836 MiB`).
- Added `-JvmXss` support to `linux_smaps_breakdown.ps1` and introduced `xss_anon_matrix.ps1` for
  stack-size A/B evidence across `256k/192k/160k/128k` without duplicate JVM `-Xss` flags.
- Updated the minimal production benchmark image to generate `META-INF/reactor/components.idx` and
  `META-INF/reactor/routes.idx` during Docker build. The minimal image now mirrors the strict
  low-RSS production expectation instead of emitting the classpath-scan fallback warning.
- Completed the current indexed minimal `micro-rest` c512 stack matrix. No stack/OOM/native-thread
  failure or 500 was observed. Lowering `-Xss` did not improve final anon in the clean-index run
  (`256k=43.512 MiB`, `192k=46.113 MiB`, `160k=50.211 MiB`, `128k=49.082 MiB`), so the default
  remains `256k`; smaller values remain service-specific experiments.
- Tested `micro-rest` JNI queue capacity `512` as a focused `small-direct` optimization while keeping
  one JNI worker and `max-connections=512`. The focused indexed minimal-app c256/c512 repeat-3 matrix
  showed c256 `503` dropping from `19.720%` to `0%`, and c512 `503` from `8.635%` to `0.607%`.
  Full clean-index endpoint matrix rejected it as the global `micro-rest` default: direct-heavy,
  producer-heavy, dynamic-producer, and raw-heavy regressed on RPS/p99/503. The default remains
  `reactor.rust.jni.queue-capacity=128`; queue `512` is documented only as an explicit small/direct
  JSON tuning recipe.
- Added opt-in route-local JNI queue admission (`@JniQueueAdmission` / `reactor.rust.jni-admission.*`)
  backed by a bounded priority JNI lane. The bundled `small-direct` sample keeps it disabled by
  default because the full clean-index gate reduced `503` but did not pass as a general `micro-rest`
  default. `@RouteAdmission` remains the right tool for heavy or slow route work.
- Release documentation now states the package immutability rule: do not overwrite a published Maven
  version; cut a new patch version if packaged bytes must change.
- Added optional OpenJ9 JIT code-cache cap evidence flow through `openj9-micro-rss-jitcap.options`,
  `linux_smaps_breakdown.ps1`, and `container_benchmark.ps1`.
- Added `jitcap_gate.ps1` to compare `micro-rest` against JIT-capped `micro-rest` with explicit
  RSS and p99 pass/fail criteria.
- Extended Linux smaps evidence collection with optional post-measurement OpenJ9 javacore capture.
- Documented the full local JIT-cap gate result: `-Xcodecachetotal8m` reduced minimal production
  cgroup RSS by `5.952 MiB`, but failed default candidacy due p99 regressions on mixed endpoint
  workload, especially the legacy dynamic DTO graph at c256/c512.
- Added `micro-dubbo` support to `linux_smaps_breakdown.ps1`, using static discovery defaults so
  the minimal production app can isolate the Dubbo-enabled runtime surface without requiring
  ZooKeeper.
- Added `anon_evidence_gate.ps1`, a single evidence entrypoint that runs minimal `micro-rest`,
  `micro-rest-plus`, `micro-dubbo`, conservative trim off/on A/B, and OpenJ9 javacore/native
  evidence, then writes final attribution, peak memory, load signal, and all smaps rows to one
  report folder.
- Added native `reactor_native_http_user_requests_total` and Java `http_user_requests_total`.
  `/health`, `/metrics`, `/metrics/*`, and `/diagnostics/*` no longer reset the idle native trim
  window. This prevents Kubernetes probes, Prometheus scrapes, and benchmark diagnostics from
  keeping an otherwise idle pod in `skipped_not_idle`.
- Changed idle native trim scheduling so `skipped_not_idle` and `skipped_active` retry at the next
  idle boundary instead of blindly waiting the full `interval-ms`. Conservative production settings
  still avoid request-path trim, but warmed RSS can now be reclaimed sooner after a burst.
- Rebuilt Windows DLL and Linux SO resources after the native user-request metric change.
- Documentation now describes anon attribution as a production decision gate: use heap, JIT/code,
  class metadata, direct buffer, Rust-accounted pools, thread stack budget, and residual anon fields
  to choose the next code target instead of lowering heap or changing defaults blindly.

---

## [3.2.1] - 2026-06-04

### Changed

- Maven package version bumped to `3.2.1`.
- Production-like benchmark images can now use `-FrameworkArtifactMode core-runtime`, so
  `rust-java-rest-*-core-runtime.jar` is used instead of framework `target/classes`.
- `jvm_baseline_rss_matrix.ps1` now forwards the framework artifact mode, keeping repeated JVM/RSS
  checks aligned with production classpath rules.
- Published sources and javadocs now exclude framework sample, benchmark, and Dubbo sample packages.
- Runtime profile defaults no longer override keys explicitly configured in `rust-spring.properties`;
  JVM system properties and environment variables still have highest priority.
- README, benchmark docs, and production runtime docs now document the production artifact rule:
  use the normal dependency or `core-runtime`; do not use the `sample` classifier in production.

### Fixed

- Prevented sample/example classes and sample startup indexes from leaking into production-like
  consumer RSS measurements.
- Added regression coverage for explicit property values overriding runtime profile defaults.

### Validation

- `mvn -q test`
- `mvn -q -DskipTests package`
- Jar policy check: main jar, `core-runtime`, and sources jar contain no sample/benchmark packages
  and no sample startup index.
- Native-static Dubbo consumer smoke RSS check with `-FrameworkArtifactMode core-runtime`.
- A/B smoke check for `core-runtime` versus framework `target/classes` benchmark classpath.

### Benchmark Notes

- Latest native-static Dubbo consumer smoke, `cpu1`, idle `5s`:
  - `core-runtime`: ready RSS `57.27 MiB`, after first RPC RSS `58.28 MiB`, Docker memory ready
    `30.00 MiB`, image build context `5.01 MB`.
  - `classes`: ready RSS `58.75 MiB`, after first RPC RSS `59.88 MiB`, Docker memory ready
    `31.48 MiB`, image build context `9.99 MB`.
- This is a packaging and measurement-correctness patch, not a broad JVM RSS breakthrough. RSS can
  stay close because unloaded classes do not always become live RSS.

---

## [3.2.0] - 2026-06-03

### Added

- Added startup phase diagnostics via `/diagnostics/startup` and Prometheus gauges.
- Added optional `META-INF/reactor/components.idx` support to bypass component classpath scanning.
- Added optional `META-INF/reactor/routes.idx` validation as a startup production gate.
- Added native DLL/SO extraction cache keyed by ABI, platform, and SHA-256.
- Added `fast-start` and `ready-low-latency` runtime profiles.
- Added readiness prewarm hooks for handler descriptors, DSL-JSON writer state, and direct writer lookup.
- Added OpenJ9/Semeru startup tuning docs and a startup benchmark runner.
- Added tests for startup timeline metrics/JSON, startup index parsing, and index generation.
- Added optional OpenJ9 CRIU/Semeru InstantOn checkpoint hook and Docker Desktop image flow.
- Added `ready_since_restore_ms` startup diagnostics for CRIU-restored containers.
- Added WSL/Linux InstantOn benchmark script for normal vs restored container startup comparison.
- Added `@RouteAdmission` for route-level native admission control before the JNI worker queue.
- Added route admission matrix benchmark runner and memory proof runner.
- Added `JsonProducerResponse` and `JsonBodyProducer` for heavy dynamic JSON without building Java
  DTO list graphs.
- Added direct primitive query/path binding expansion for hot numeric parameters.
- Added user-facing REST cookbook with GET, GET-by-id, POST, PUT, PATCH, and DELETE examples.
- Added richer profile/RSS decision guidance for `micro-rest`, `micro-dubbo`, `low-rss`,
  `balanced-dubbo`, `throughput`, `fast-start`, and `ready-low-latency`.
- Added lean production artifact behavior: the default jar excludes sample/benchmark classes and
  sample code is attached as a separate classifier.

### Changed

- Route index validation now detects both missing routes and unexpected runtime routes.
- Native extraction cache now verifies cached file content by SHA-256, not only by file size.
- Classpath component scanning loads candidate classes without static initialization.
- Native ABI is now `20`; use the DLL/SO from this package.
- Maven package version bumped to `3.2.0`.
- README and release notes now avoid broad "always faster" or "always 50 MiB" claims and describe
  workload-specific profile decisions.
- Heavy producer sample route now uses measured route admission defaults
  `maxConcurrent=80`, `queueTimeoutMs=150`.
- `RawResponse`, `JsonProducerResponse`, and `FileResponse` cache encoded headers to avoid repeated
  UTF-8/header encoding work on hot response paths.

### Validation

- `mvn -q test`
- `mvn -q -DskipTests package`
- Full repeat benchmark: `micro-rest-plus`, endpoint classes
  `small-json-legacy,small-json-direct,dynamic-dto-json,producer-json,direct-json-writer,raw-json,native-cache-json,file-static`,
  concurrency `64/256/512/1000`, repeat `3`.
- Idle/soak memory proof with mixed small/raw/heavy/cache/export endpoints.
- Route admission full matrix for producer JSON at c256/c512 with `64/80/96/128` concurrency limits
  and `75/125/150 ms` queue timeouts.

### Benchmark Notes

- At c512, small JSON averaged `9153 RPS`, `115.83 ms` p99, and `68.75 MiB` sampled RSS versus
  Spring Boot `3459 RPS`, `368.60 ms` p99, and `314.33 MiB` sampled RSS.
- At c512, raw/precomputed JSON averaged `9659 RPS`, `114.80 ms` p99, and `68.56 MiB` sampled RSS
  versus Spring Boot `3875 RPS`, `623.98 ms` p99, and `274.13 MiB` sampled RSS.
- At c512, dynamic DTO JSON averaged `3338 RPS`, `309.68 ms` p99, and `67.82 MiB` sampled RSS
  versus Spring Boot `1524 RPS`, `776.47 ms` p99, and `309.98 MiB` sampled RSS. Dynamic DTO remains
  supported, but the Java object graph cost is still real.
- Memory proof baseline was `66.11 MiB`, peak was `91.35 MiB`, and final idle RSS was `75.59 MiB`.
- For heavy dynamic JSON and high concurrency, release guidance is to use route admission and at
  least `128 MiB` pod headroom unless a service-specific soak test proves lower memory is safe.

---

## [3.1.0] - 2026-06-01

### Changed

- Clarified user-facing documentation with use-case driven API choices, runtime profile guidance,
  low-RSS tuning recipes, and softer release notes language.
- Added a response path playbook for Small JSON, Raw/precomputed JSON, Native cache JSON, Direct
  JSON writer, and Dynamic DTO selection.
- Promoted the measured `3.1.0-rc5` feature set to stable `3.1.0`.
- Maven package version bumped to `3.1.0`.

### Release Positioning

- Stable for pilot and production adoption where route-level tuning, bounded overload behavior, and
  low-RSS profile selection are treated as part of deployment.
- Best fit remains small JSON, raw/precomputed JSON, direct/Rust JSON writer, native static/cache,
  and file response paths.
- Dynamic Java DTO graphs remain supported, but should be measured and tuned on hot routes when
  RSS or p99 latency is a hard requirement.

---

## [3.1.0-rc5] - 2026-05-31

### Added

- Added `@NativeStaticRoute` for immutable `RawResponse.registered*` routes. Rust can now serve
  explicitly static/native responses without invoking the Java handler or JNI queue per request.
- Added `reactor.rust.file-stream.chunk-bytes` for bounded `FileResponse` stream tuning. The
  native runtime reports the active value through Prometheus metrics and memory diagnostics.
- Added `@NativeStaticFileRoute` for immutable `FileResponse` routes. The handler is invoked once
  during startup; runtime requests are streamed directly by Rust without entering the Java handler.
- Added native static file observability through `reactor_native_static_file_response_bytes` and
  `static_responses.file_bytes` in diagnostics.
- Added `reactor.rust.static-file.inline-max-bytes`. Immutable `@NativeStaticFileRoute` files at
  or below this threshold are loaded into native memory once and served without disk I/O.
- Added `reactor.rust.static-file.max-concurrent-streams` bulkhead for disk-backed native static
  file streams. Overload returns `503` and increments native stream rejection metrics instead of
  allowing unbounded disk/file-descriptor fanout.
- Added `file-stream-large` benchmark class and `/api/v1/export/file-large` sample endpoint for
  8 MiB file-stream bulkhead measurement.
- Added `-FrameworkJavaOptsAppend` to the container benchmark script so profile overrides can be
  measured without editing the benchmark harness.

### Changed

- Native ABI bumped to `19`; Windows DLL and Linux SO must match this Java build.
- Maven package version bumped to `3.1.0-rc5`.
- Increased low-RSS HTTP connection headroom from `512` to `1024` so c512 benchmark gates do not
  fail from admission-limit jitter.
- Increased benchmark-only ultra-low-rss HTTP connection headroom from `512` to `640`.
- Changed the default native `FileResponse` stream chunk from the small response buffer size to
  `64 KiB`; throughput profile can raise it while micro/low-RSS profiles keep it bounded.
- `@NativeStaticFileRoute` now caches file length and parsed response headers at startup, removing
  per-request file `metadata()` and encoded-header parsing from the static file hot path.

### Validation

- `mvn -q test`
- `cargo test`
- `cargo build --release` on Windows
- `cargo build --release` on Linux via WSL
- Container benchmark `container_20260531_051255`: low-RSS profile, CPU `2`, Rust-Java memory `96m`,
  Spring Boot memory `512m`, endpoint classes `raw-json,file-static`, concurrency `64/256/512`,
  repeat `3`, randomized order.
- Memory proof `memory_proof_low-rss_20260531_052414`: `heavy/raw` and `export/static` with
  post-load idle snapshots and native trim checks.
- Admission headroom check `container_20260531_053510`: `export_static_registered` at c512 with
  `max-connections=1024`, `0` connection rejections, `0` response backpressure, and `0` 5xx.
- Native static file route check `container_20260531_060646`: `export_file_stream` at c512 produced
  `1553 RPS`, `1.08s` p99, `0` connection rejections, `0` response backpressure, `0` 5xx, and only
  startup/diagnostic JNI activity (`reactor_native_jni_requests_total=1` for the endpoint metrics snapshot).
- Native static file inline check `container_20260531_063622`: low-RSS c512 with
  `reactor.rust.static-file.inline-max-bytes=524288` produced `1926 RPS`, `944.03ms` p99, `0`
  connection rejections, `0` response backpressure, `0` 5xx, and
  `reactor_native_static_file_inline_bytes=302608`.
- Static file stream bulkhead smoke: inline disabled, `reactor.rust.static-file.max-concurrent-streams=1`,
  `/api/v1/export/file` returned `200`, `reactor_native_static_file_stream_limit=1`,
  `reactor_native_static_file_stream_started_total=1`, and diagnostics exposed `stream_limit`.
- Current full benchmark `current_full_20260531_090441`: low-RSS profile, CPU `2`, Rust-Java memory
  `96m`, Spring Boot memory `512m`, concurrency `64/256/512/1000`, repeat `1`, randomized order.
- Large file stream matrix `stream_matrix_{32,64,128,256}_20260531_085157`: 8 MiB file, inline
  disabled, stream limits `32/64/128/256`, concurrency `256/512/1000`.

### Benchmark Notes

- At c512, `candidates` reached `4.87x` Spring Boot RPS with `125.69ms` p99 and `80.46 MiB` max
  sampled container memory.
- At c512, `echo_parse_business` reached `4.39x` Spring Boot RPS with `98.38ms` p99 and `90.04 MiB`
  max sampled container memory.
- At c512, `heavy100_raw` reached `2.00x` Spring Boot RPS with `142.44ms` p99 and `91.22 MiB` max
  sampled container memory.
- At c512, `heavy100_native_cache` reached `14970 RPS` with `102.03ms` p99 on the Rust-Java path.
- Large file stream results favor `32` or `64` max concurrent streams for low-RSS services; higher
  limits increase p99/RSS and should be reserved for dedicated download profiles.

---

## [3.1.0-rc4] - 2026-05-30

### Added

- Split container benchmark endpoint classes into `dynamic-dto-json`, `direct-json-writer`, `rust-json-writer`, `raw-json`, `native-cache-json`, and `file-static` so optimized paths are not mixed into one heavy JSON number.
- Added `-EndpointClasses` benchmark filter for targeted repeated runs.
- Added `balanced` container benchmark runtime profile between `low-rss` and `throughput`.
- Added direct primitive route bindings for query/path `double` and `short`, matching the existing `int`, `long`, and `boolean` fast paths.
- Added `DirectJsonWriterRegistry` and `DirectJsonWriterProvider` so generated/manual DTO writers can bypass DSL-JSON and write directly into the response `ByteBuffer`.
- Added `reactor.rust.json.direct-writer-enabled` property.

### Changed

- Native ABI bumped to `13`; Windows DLL and Linux SO must match this Java release.
- `JsonBufferWriter` now supports double values.
- Route diagnostics now expose the new direct primitive strategy types.

### Validation

- `mvn -q test`
- `cargo test`
- `cargo build --release` on Windows
- `cargo build --release` on Linux via WSL
- Container benchmark `container_20260530_154237`: low-RSS profile, CPU `2`, Rust-Java memory `128m`, Spring Boot memory `512m`, concurrency `64/256/512/1000`, repeat `3`, randomized order.
- Profile comparison gates: `container_20260530_165249` for `balanced` c=1000 and `container_20260530_172402` for `throughput` c=1000.

### Benchmark Notes

- At concurrency `1000`, average Rust-Java RPS ratios were `3.07x` for `candidates`, `3.76x` for `echo`, `4.01x` for `heavy100 raw`, and `1.51x` for `heavy100 dynamic DTO`.
- Rust-Java max sampled container memory stayed around `95-98 MiB` for the comparable c=1000 endpoints, while Spring Boot was around `295-313 MiB`.
- `file-static` is not yet a high-concurrency throughput winner in the low-RSS profile; it needs separate stream/sendfile tuning before being used as a throughput claim.

---

## [3.0.0] - 2026-03-17

### Performance Improvements

#### Phase 5 Latency Optimization
- **MethodMetadata Cache** - Pre-computed annotation metadata at startup (~200ns → ~5ns)
- **FastMapV2** - Robin-Hood hashing for O(1) parameter lookup (was O(n))
- **Zero-Copy Header Encoding** - Direct byte encoding in Rust (no String allocation)
- **ThreadLocal Buffer Pools** - Zero-allocation parameter parsing

#### Benchmark Results
| Endpoint | Before | After | Improvement |
|----------|--------|-------|-------------|
| GET /health | 8-12ms | 5-8ms | 33-40% faster |
| POST /order/create | 8-15ms | 6-11ms | 25-35% faster |
| Concurrent 10 req | 8-15ms | 4-6ms | 50% faster |

### Added

#### Java Side
- `MethodMetadata.java` - Pre-computed method parameter metadata cache
- `FastMapV2.java` - Robin-Hood hashing implementation for O(1) lookup
- `ParamInfo` class - Cached parameter information (type, name, defaultValue)
- `ParamType` enum - Parameter type classification (PATH_VARIABLE, REQUEST_PARAM, etc.)
- Pre-allocated error byte arrays in DslJsonService for fast error responses
- `writeErrorToBuffer()` method in DslJsonService

#### Rust Side
- `encode_headers_zero_copy()` - Zero-copy header encoding to Vec<u8>
- `encode_path_params_zero_copy()` - Zero-copy path parameter encoding
- Thread-local buffer pools for header and path encoding
- Pre-calculated buffer sizes to avoid borrow checker issues

#### Docker
- `Dockerfile.ultra` - Ultra-low memory container (149MB image, 28MB runtime)
- Multi-stage build with Rust 1.85 and JDK 21
- jlink minimal JRE (~25MB)
- Ultra-low memory JVM options (4-24MB heap)

### Changed

#### HandlerRegistry.java
- Integrated MethodMetadata cache for zero-overhead annotation lookup
- Replaced HashMap with ThreadLocal FastMapV2 pools
- Added `parseParamsFast()` and `parseHeadersFast()` methods
- Added `resolveArgumentsFast()` with pre-computed parameter info
- Added lazy debug logging (only when `-Dhandler.debug=true`)

#### DslJsonService.java
- Added pre-allocated ERROR_PREFIX and ERROR_SUFFIX byte arrays
- Added `writeErrorToBuffer()` for fast error responses
- Added `escapeJson()` helper for JSON string escaping
- Removed verbose initialization logging

#### lib.rs (Rust)
- Changed header encoding from String to Vec<u8> (zero-copy)
- Fixed borrow checker issues in buffer size calculation
- Removed unused imports (AtomicPtr, HashMap)
- Fixed doc comments on thread_local! macro

### Memory Improvements

| Metric | v2.0.0 | v3.0.0 |
|--------|--------|--------|
| Per-request allocation | ~2KB | ~0 bytes |
| Container memory | 27-35 MB | 26-29 MB |
| JRE size | 35 MB | ~25 MB |

### Project Rules Updated

Added 2 new project rules:

- **Rule #17: Docker Image & JRE Ultra Low Size**
  - Docker Image target: < 150 MB
  - JRE target: < 30 MB (via jlink)
  - Runtime memory: < 50 MB

- **Rule #18: Test Strategy - Docker vs Local**
  - Load/Benchmark/Stress tests → Docker Container
  - Functional/Unit tests → Local (mvn test)

### Dependencies Added

```toml
# Rust (Cargo.toml)
rayon = "1.10"        # Parallel iterator and thread pool
radix_trie = "0.2"    # Radix trie for route matching
smallvec = "1.13"     # Stack-allocated small vectors
```

### Docker

```bash
# Build ultra-low memory image
docker build -t rust-java-rest:ultra -f src/main/resources/container/Dockerfile.ultra .

# Run with 50MB memory limit
docker run -d -p 8080:8080 --memory=50m --name rust-java rust-java-rest:ultra
```

### Breaking Changes

None. All v2.0.0 code is compatible with v3.0.0.

---

## [2.0.0] - 2026-03-12

### Added
- Zero-overhead Dependency Injection container
- `@Component`, `@Service`, `@Repository`, `@Configuration` annotations
- `@Bean` methods for bean production
- `@Autowired` for dependency injection
- `@PostConstruct` and `@PreDestroy` lifecycle callbacks
- `@Primary` and `@Qualifier` for bean selection
- O(1) bean lookup with ConcurrentHashMap

### Performance
- Bean lookup: ~0.4 microseconds
- Memory overhead: ~50-100 bytes/bean
- Zero runtime reflection

---

## [1.0.0] - 2026-03-01

### Added
- Initial release
- Rust Hyper HTTP server with JNI
- Spring Boot-like annotations (@GetMapping, @PostMapping, etc.)
- DSL-JSON 2.0.2 integration
- Parameter annotations (@PathVariable, @RequestParam, @HeaderParam, @RequestBody)
- ResponseEntity<T> support
- WebSocket support (/ws/echo, /ws/chat/{roomId})
- Docker images (74MB minimal)

### Performance
- ~27 MB memory (vs Spring Boot ~94 MB)
- 3,257 RPS (vs Spring Boot ~1,150 RPS)
- 33 ms latency (vs Spring Boot ~144 ms)

---

[4.5.3]: https://github.com/esasmer-dou/rust-java-rest/compare/v4.5.2...v4.5.3
[4.5.2]: https://github.com/esasmer-dou/rust-java-rest/compare/v4.5.1...v4.5.2
[4.5.1]: https://github.com/esasmer-dou/rust-java-rest/compare/v4.5.0...v4.5.1
[4.5.0]: https://github.com/esasmer-dou/rust-java-rest/compare/v4.4.1...v4.5.0
[4.4.1]: https://github.com/esasmer-dou/rust-java-rest/compare/v4.4.0...v4.4.1
[4.0.0]: https://github.com/esasmer-dou/rust-java-rest/compare/v3.4.1...v4.0.0
[3.4.1]: https://github.com/esasmer-dou/rust-java-rest/compare/v3.4.0...v3.4.1
[3.2.5]: https://github.com/esasmer-dou/rust-java-rest/compare/v3.2.4...v3.2.5
[3.2.2]: https://github.com/esasmer-dou/rust-java-rest/compare/v3.2.1...v3.2.2
[3.2.1]: https://github.com/esasmer-dou/rust-java-rest/compare/v3.2.0...v3.2.1
[3.2.0]: https://github.com/esasmer-dou/rust-java-rest/compare/v3.1.0...v3.2.0
[3.1.0]: https://github.com/esasmer-dou/rust-java-rest/compare/v3.0.0...v3.1.0
[3.0.0]: https://github.com/esasmer-dou/rust-java-rest/compare/v2.0.0...v3.0.0
[2.0.0]: https://github.com/esasmer-dou/rust-java-rest/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/esasmer-dou/rust-java-rest/releases/tag/v1.0.0

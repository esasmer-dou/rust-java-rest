# Changelog

All notable changes to the Rust-Java REST Framework will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

No unreleased changes yet.

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

[3.2.0]: https://github.com/esasmer-dou/rust-java-rest/compare/v3.1.0...v3.2.0
[3.1.0]: https://github.com/esasmer-dou/rust-java-rest/compare/v3.0.0...v3.1.0
[3.0.0]: https://github.com/esasmer-dou/rust-java-rest/compare/v2.0.0...v3.0.0
[2.0.0]: https://github.com/esasmer-dou/rust-java-rest/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/esasmer-dou/rust-java-rest/releases/tag/v1.0.0

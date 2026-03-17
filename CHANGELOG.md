# Changelog

All notable changes to the Rust-Java REST Framework will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[3.0.0]: https://github.com/esasmer-dou/rust-java-rest/compare/v2.0.0...v3.0.0
[2.0.0]: https://github.com/esasmer-dou/rust-java-rest/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/esasmer-dou/rust-java-rest/releases/tag/v1.0.0

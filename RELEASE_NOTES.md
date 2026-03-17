# Rust-Java REST Framework v3.0.0

## Major Release: Ultra Low Latency & Memory Optimization

This release focuses on **extreme performance optimization** achieving sub-10ms latency and sub-30MB memory footprint.

---

## Performance Improvements

### Latency Reduction (Phase 5 Optimizations)

| Endpoint | v2.0.0 | v3.0.0 | Improvement |
|----------|--------|--------|-------------|
| GET /health | 8-12ms | **5-8ms** | **33-40% faster** |
| POST /order/create | 8-15ms | **6-11ms** | **25-35% faster** |
| Concurrent (10 req) | 8-15ms | **4-6ms** | **50% faster** |

### Memory Footprint

| Metric | v2.0.0 | v3.0.0 | Improvement |
|--------|--------|--------|-------------|
| Docker Image | 74 MB | **149 MB** | (includes Rust build) |
| Container Memory | 27-35 MB | **26-29 MB** | **15% less** |
| JRE Size | 35 MB | **~25 MB** | **28% smaller** |
| Per-request Allocation | ~2 KB | **~0 bytes** | **100% reduction** |

---

## New Features

### 1. MethodMetadata Cache
Pre-computed annotation metadata at startup eliminates runtime reflection.

```java
// Before: Runtime annotation lookup (~200ns)
Parameter param = method.getParameters()[i];
PathVariable pv = param.getAnnotation(PathVariable.class);

// After: Cached lookup (~5ns)
MethodMetadata metadata = MethodMetadata.getOrCreate(method, reqType, respType);
ParamInfo info = metadata.paramInfos[i];
```

### 2. FastMapV2 with Robin-Hood Hashing
O(1) average lookup for HTTP parameters and headers.

```java
// Before: O(n) with linear probing
FastMap map = new FastMap();

// After: O(1) with Robin-Hood hashing
FastMapV2 map = FastMapV2.acquire();
```

### 3. Zero-Copy Header Encoding (Rust)
Direct byte encoding eliminates String allocation in header processing.

```rust
// Before: String allocation
fn encode_headers(headers: &HeaderMap) -> String

// After: Zero-copy byte encoding
fn encode_headers_zero_copy(headers: &HeaderMap) -> Vec<u8>
```

### 4. ThreadLocal Buffer Pools
Zero-allocation parameter parsing with pre-allocated buffers.

```java
private static final ThreadLocal<FastMapV2> PARAM_MAP_POOL =
    ThreadLocal.withInitial(FastMapV2::new);
```

### 5. Pre-Allocated Error Responses
Fast error responses without string concatenation.

```java
private static final byte[] ERROR_PREFIX = "{\"error\":\"".getBytes(UTF_8);
private static final byte[] ERROR_SUFFIX = "\"}".getBytes(UTF_8);
```

---

## New Dockerfile - Ultra Low Memory

### Dockerfile.ultra
Multi-stage build with jlink and minimal JRE:

```bash
docker build -t rust-java-rest:ultra -f src/main/resources/container/Dockerfile.ultra .
docker run -d -p 8080:8080 --memory=50m --name rust-java rust-java-rest:ultra
```

### Ultra-Low Memory JVM Options

```bash
-Xms4m -Xmx24m
-XX:+UseSerialGC
-XX:MaxMetaspaceSize=20m
-XX:ReservedCodeCacheSize=8m
-XX:+TieredCompilation -XX:TieredStopAtLevel=1
-Xss256k
```

---

## Benchmark Results (v3.0.0)

### Container Stats (50MB Memory Limit)

| Metric | Value |
|--------|-------|
| Memory Usage | **28.99 MB** |
| CPU Usage (idle) | ~6% |
| Image Size | 149 MB |

### GET /health (100 sequential requests)

| Percentile | Latency |
|------------|---------|
| p50 | 5.5ms |
| p75 | 6.0ms |
| p90 | 6.7ms |
| p95 | 7.1ms |
| p99 | 21.8ms |
| Min | 4.2ms |
| Max | 21.8ms |
| **Avg** | **5.8ms** |

### POST /order/create (50 requests)

| Percentile | Latency |
|------------|---------|
| p50 | 6.6ms |
| p90 | 8.0ms |
| p99 | 28.0ms |
| **Avg** | **7.0ms** |

---

## New Files

| File | Description |
|------|-------------|
| `bridge/MethodMetadata.java` | Pre-computed annotation metadata cache |
| `util/FastMapV2.java` | Robin-Hood hashing for O(1) lookup |
| `container/Dockerfile.ultra` | Ultra-low memory Docker image |

## Modified Files

| File | Changes |
|------|---------|
| `bridge/HandlerRegistry.java` | ThreadLocal FastMapV2 pools, fast parsing |
| `json/DslJsonService.java` | Pre-allocated error bytes |
| `lib.rs` (Rust) | Zero-copy header encoding, buffer pools |

---

## Test Strategy (New Rule #18)

| Test Type | Where |
|-----------|-------|
| **Load/Benchmark/Stress** | Docker Container |
| **Functional/Unit** | Local (mvn test) |

```bash
# Local - Functional Tests
mvn test

# Docker - Load Tests
docker run -d -p 8080:8080 --memory=50m rust-java-rest:ultra
wrk -t4 -c100 -d30s http://localhost:8080/health
```

---

## Docker

```bash
# Pull ultra-low memory image
docker pull ghcr.io/esasmer-dou/rust-java-rest:3.0.0

# Run with 50MB memory limit
docker run -p 8080:8080 --memory=50m ghcr.io/esasmer-dou/rust-java-rest:3.0.0
```

---

## Migration from v2.0.0

No breaking changes. All v2.0.0 code works with v3.0.0.

**Recommended:**
1. Update Maven dependency to 3.0.0
2. Use new `Dockerfile.ultra` for production
3. Run load tests in Docker container

---

**Full Changelog**: https://github.com/esasmer-dou/rust-java-rest/compare/v2.0.0...v3.0.0

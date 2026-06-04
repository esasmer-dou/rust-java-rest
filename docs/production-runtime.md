# Production Runtime Notes

This page explains the runtime knobs you normally touch in production. The short version:

- Java owns handlers, services, components, and business logic.
- Rust owns HTTP accept/parsing, body limits, file streaming, WebSocket framing, metrics, and backpressure.
- Start with a profile, then tune only the route class that needs it.

## Profile Selection

| Profile | Use when | Practical note |
|---------|----------|----------------|
| `micro-rest` | Very small REST service, Dubbo off, memory first | Lowest REST preset; bounded `503` under heavy route pressure |
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
reactor.rust.http.max-inflight-response-bytes=8388608
reactor.rust.file-stream.chunk-bytes=65536
reactor.rust.static-file.max-concurrent-streams=64
reactor.rust.native-cache.max-entries=0
reactor.rust.native-cache.max-bytes=0
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

For `micro-rest` and `micro-dubbo`, properties alone are not enough. The JVM must also be prevented
from sizing internal workers from a large host CPU count:

```bash
-Xms8m -Xmx48m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1
```

Use `startup/openj9-micro-rss.options` as the starting point. For very low traffic services only,
`startup/openj9-idle-rss.options` adds `-Xnojit`; this can reduce memory further, but it trades away
JIT-optimized Java execution and must be benchmarked before production use.

For applications that use startup indexes, generate the component index for the actual feature set.
For a tiny REST-only service, exclude optional WebSocket/static-file components from the index:

```bash
java -cp "app.jar:lib/*" com.reactor.rust.startup.StartupIndexGenerator \
  --output target/classes \
  --packages com.example.api \
  --exclude-websocket \
  --exclude-static-files
```

If WebSocket or `@StaticFiles` is part of the service contract, do not exclude it.

## Production Artifact Rule

Run production services with the lean framework artifact:

- Use the normal Maven dependency for application compile/runtime.
- Use `rust-java-rest-*-core-runtime.jar` in benchmark images when you need a single framework
  runtime jar.
- Do not put `target/classes` from the framework project on a production or production-like
  benchmark classpath.
- Do not use the `sample` classifier for production. It intentionally contains demo handlers,
  DTOs, and a sample startup index.

The default jar, `core-runtime` jar, sources jar, and javadocs exclude
`com.reactor.rust.example`, `com.reactor.rust.benchmark`, and `com.reactor.rust.dubbo.sample`.
The sample jar keeps those classes only so examples and local benchmarks remain runnable.

Measured `v3.2.1` release-gate RSS guidance:

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

Direct JSON writer:

- Rust gives Java a direct response `ByteBuffer`.
- Java writes JSON with `JsonBufferWriter` or a generated direct writer and returns the byte count.
- Rust sends the buffer without building a DTO graph.

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

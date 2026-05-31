# Production Runtime Notes

This page explains the runtime knobs you normally touch in production. The short version:

- Java owns handlers, services, components, and business logic.
- Rust owns HTTP accept/parsing, body limits, file streaming, WebSocket framing, metrics, and backpressure.
- Start with a profile, then tune only the route class that needs it.

## Profile Selection

| Profile | Use when | Practical note |
|---------|----------|----------------|
| `low-rss` | Memory is the main constraint | Good default for pilots and small/medium APIs |
| `balanced` | Java handler waits on DB/RPC or p99 needs more headroom | More memory than `low-rss`, smoother under blocking work |
| `throughput` | Dedicated high-RPS service with larger pod budget | More retained buffers/workers |
| `micro-rss` / `ultra-low-rss` | Very small services or experiments | Too strict for high-concurrency downloads |

Recommended first production-like baseline:

```properties
reactor.runtime.profile=low-rss
reactor.rust.http.max-connections=1024
reactor.rust.http.max-inflight-response-bytes=16777216
reactor.rust.file-stream.chunk-bytes=65536
reactor.rust.static-file.max-concurrent-streams=64
reactor.rust.log.level=error
reactor.rust.java.log.level=warn
```

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

| Use case | Recommended response | Why |
|----------|----------------------|-----|
| CRUD JSON | Java record DTO | Simple and maintainable |
| Already serialized JSON | `RawResponse.json(...)` | Avoids deserialize/serialize roundtrip |
| Immutable config/read model | `RawResponse.registeredJson(...)` + `@NativeStaticRoute` | Rust can serve without Java handler call |
| File/download/export | `FileResponse` | File body stays out of Java heap |
| Immutable static file | `FileResponse` + `@NativeStaticFileRoute` | Rust serves path directly after startup |
| Hot predictable JSON | `JsonBufferWriter` or direct writer | Avoids DTO graph and serializer buffer |

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

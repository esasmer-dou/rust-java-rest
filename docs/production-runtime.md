# Production Runtime Notes

This framework keeps HTTP accept, parsing, body limits, WebSocket framing, file streaming, metrics, and backpressure in Rust Hyper. Java owns business logic only.

## Response Types

`RawResponse`

- Use for pre-serialized text or bytes such as `/metrics`.
- Bypasses JSON serialization, so Prometheus text is not quoted or escaped.
- Not intended for normal business DTO JSON; DTOs should stay on the DSL-JSON path.

`FileResponse`

- Use for static files, downloads, and export files.
- Java returns path plus headers; file bytes are streamed by Rust.
- This avoids large Java heap byte arrays and avoids moving file contents through JNI.

## Body Limits

Global defaults are configured in `rust-spring.properties`.

- `reactor.rust.http.max-request-body-bytes=1048576`
- `reactor.rust.http.max-response-body-bytes=8388608`
- `reactor.rust.http.max-inflight-body-bytes=67108864`
- `reactor.rust.http.max-inflight-response-bytes=134217728`

Route-level overrides use annotations:

- `@MaxRequestBodySize(bytes = ...)`
- `@MaxResponseSize(bytes = ...)`

Do not raise per-request limits without also sizing in-flight caps. Large JSON/export paths should use `FileResponse` or a future chunked streaming API.

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

Production should stay at `warn` or lower. Per-request logging is an anti-pattern for this latency/RSS target.

## Shutdown

`NativeBridge.stopHttpServer()` stops the native accept loop. The example application installs a JVM shutdown hook, so Kubernetes `SIGTERM` stops accepting new connections and existing connections drain according to configured keep-alive/idle timeouts.

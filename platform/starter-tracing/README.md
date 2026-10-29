# Tracing Starter

[English](README.md) | [Turkish](README.tr.md)

Carry a request's trace ID from a Java handler to a downstream HTTP service.
The starter is optional. Java business code and the native HTTP transport stay unchanged.

## Contents

- [Start with one route](#start-with-one-route)
- [Keep headers through retries](#keep-headers-through-retries)
- [Write to a caller-owned buffer](#write-to-a-caller-owned-buffer)
- [Configuration and cost](#configuration-and-cost)
- [Working example](#working-example)

## Start With One Route

Add `com.reactor:rust-java-starter-tracing` using your platform BOM version.
For a generated HTTP client, also add `rust-java-starter-http-client`.

```properties
reactor.tracing.enabled=true
reactor.tracing.annotated-only=true
reactor.tracing.sample-ratio=0.01
```

```java
@GetMapping("/orders")
@Traced("orders.list")
public CompletionStage<RawResponse> list() {
    return inventory.list().thenApply(RawResponse::json);
}
```

`inventory` is a generated `@ReactorHttpClient`. Start its call inside the handler.
The header provider reads the current context before the handler returns its stage.
No manual trace header or dynamic proxy is needed.

For a valid incoming `traceparent`, the handler keeps its trace ID and creates a new
local span ID. The incoming span ID becomes `TraceSpan.parentSpanId()`.
The downstream header contains the **local** span ID, not the incoming parent ID.
Missing or invalid headers start a new root. All-zero IDs, uppercase hex and version
`ff` are rejected. Version `00` requires exactly 55 characters; future versions
`01..fe` may contain extension fields after the valid prefix.

## Keep Headers Through Retries

The HTTP client builds a logical request on the calling thread once. Its retries
reuse that request and the captured headers, including `traceparent` and an
application-provided idempotency key. Clearing or changing a worker's context does
not change an in-flight retry. Each *new* logical request captures its own context.

Tracing does not enable retries. Enable them only for an idempotent operation:

```java
@HttpExchange(path = "/inventory", timeoutMs = 2000, retries = 2, idempotent = true)
CompletionStage<byte[]> list();
```

An idempotency-key header does not itself make a write safe. The downstream service
must implement the corresponding duplicate-write protection. Retry limits, timeouts,
backoff and the outbound bulkhead still apply. An executor boundary also needs explicit
context propagation, for example `executor.execute(TraceContext.wrap(task))`.

## Write To A Caller-Owned Buffer

```java
TraceContext context = TraceContext.currentOrNull();
if (context != null) {
    int written = W3cTraceparent.write(context, target, offset);
    // written == 55; target and offset belong to your bounded output buffer.
}
```

`write` validates first and writes 55 ASCII bytes without a temporary array or String.
It checks the full output range before writing. The String-based HTTP header API
still creates a String; parsing, span creation and HTTP request construction are
**not** zero-allocation operations. The zero-allocation guarantee is specific to
`W3cTraceparent.write` with an existing context and buffer.

## Configuration And Cost

| Property | Default | What it changes |
| --- | --- | --- |
| `reactor.tracing.enabled` | `false` | Installs tracing guards when enabled. Disabled mode does not load an exporter. |
| `reactor.tracing.annotated-only` | `false` | With `true`, only `@Traced` routes are selected. |
| `reactor.tracing.sample-ratio` | `0.01` | Root-span sampling probability from `0.0` to `1.0`. A valid incoming sampled bit takes precedence. |

Use annotated-only selection when only a few routes need tracing. A remote sampled
request is exported even with a local ratio of zero; this setting is not a hard
telemetry rate limit. Unsampled requests still propagate their context.

By default completed sampled spans update local framework metrics. To deliver them
elsewhere, provide exactly one `TraceExporter` through `ServiceLoader`. Keep it bounded
and nonblocking; it runs on the completing thread, not on a new starter-owned pool.
Do not retain an unbounded list of spans. This starter does not install the Glowroot
agent or capture request bodies, secrets or arbitrary application logs.

Memory-first profiles can disable Java metric collection. In that case the default
metric exporter does not accumulate counters. Enable `reactor.metrics.collection-enabled=true`
when those counters are required, or provide your own bounded exporter.

`TraceSpan` includes `parentSpanId` as its eighth component. Existing seven-argument
constructor calls still work and receive a null parent. Native ABI is unchanged.

## Working Example

The cache-reader repository includes a separate `trace-propagation` Maven profile.
It demonstrates real inbound-to-outbound propagation, an async response and a
controlled `503 -> 503 -> 200` downstream retry. It does not add tracing classes or
dependencies to the default Redis reader artifact.

See [the example guide](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/TRACE_PROPAGATION.md).

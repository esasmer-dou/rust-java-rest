# Configuration

[English](configuration.md) | [Türkçe](configuration.tr.md)

Configuration precedence is explicit: JVM `-D` and environment values override external property
files; external files override classpath `rust-spring.properties`; `RuntimeProfilePlan` supplies only
missing application defaults.

| Goal | Start with | Change only after measuring |
| --- | --- | --- |
| Lowest practical REST RSS | `reactor.runtime.profile=micro-rest` | JNI workers, response pools, max connections |
| Heavy producer JSON | `micro-rest-plus` and a named route budget | `max-concurrent`, `queue-timeout-ms` |
| Maximum RPS | `balanced` or `throughput` | worker and queue sizes with pod CPU/RSS limits |
| Large files | `FileResponse` | `reactor.rust.static-file.max-concurrent-streams` |
| WebSocket | enable only in that artifact | max frame bytes and outbound queue capacity |

Do not copy the full framework property file into every service. Keep local defaults small. Put
production gates in an external overlay and advanced tuning in a separate measured overlay.

Use `META-INF/reactor/properties.idx` for build-time `@RustProperty` inventory. It is metadata, not a
secret store and not a runtime configuration source.

| Production gate | Default | Enable when |
| --- | --- | --- |
| `reactor.optimizer.fail-on-reflection-route-metadata` | `false` | `/diagnostics/routes` shows generated metadata for every production route |
| `reactor.optimizer.fail-on-fallback` | `false` | All required routes use an intended compiled/direct/native strategy |
| `reactor.optimizer.fail-on-heavy-json-object-graph` | `false` | Heavy routes have moved to producer/direct/raw/file/native response paths |

Turn these gates on after migration. They fail startup; they are not per-request checks and do not
add hot-path overhead.

## Optional Runtime Retention

These settings remove observability state only when the service does not expose or consume it.

| Property | Default | What it does | When to change it |
| --- | --- | --- | --- |
| `reactor.metrics.collection-enabled` | `false` | Keeps Java counters, gauges, and histograms active even when built-in metrics routes are not registered | Set `true` only when application code reads `Metrics` without using `@ReactorApplication(metrics = true)` or `RestApplication.Builder.metrics()` |
| `reactor.optimizer.retain-route-plans` | `auto` | `auto` retains detailed plans when metrics or runtime route metrics are enabled; otherwise it releases startup-only plan objects after validation | Use `true` for custom runtime diagnostics without built-in metrics; use `false` only when route details are intentionally unavailable after startup |

`@ReactorApplication(metrics = true)` and `RestApplication.Builder.metrics()` always enable Java
metric collection. Disabling metrics does not remove native HTTP counters from Rust; it only avoids
retaining optional Java registry state that no endpoint can read.

## Glowroot Micro Agent (4.4.1)

The published `4.4.1` runtime uses REST ABI `28` and Glowroot ABI `1`. Use only the DLL/SO packaged
with the coordinated `4.4.1` artifact.

This is an application-side integration. Keep the existing Glowroot Central/collector unchanged.
The strict low-memory path needs no agent JAR: enable the coordinated native capability with system
properties, environment variables, or `rust-spring.properties`. The optional
`java-rust-glowroot-agent.jar` only translates `-javaagent` arguments and is measured separately.
Do not install the benchmark mock collector or a custom collector plugin in production.

| Property | Default | Allowed value | Purpose |
| --- | ---: | --- | --- |
| `reactor.glowroot.enabled` | `false` | boolean | Enables bounded telemetry state and exporter |
| `reactor.glowroot.profile` | `micro` | `micro` | Rejects accidental use of an unbounded profile |
| `reactor.glowroot.collector.address` | `http://127.0.0.1:8181` | plaintext `host:port` or `http://host:port` | Glowroot Central h2 endpoint |
| `reactor.glowroot.agent.id` | empty | 1-256 bytes | Required stable pod/rollup identity |
| `reactor.glowroot.application.name` | application name | 1-128 bytes | Name displayed by Glowroot |
| `reactor.glowroot.hostname` | `HOSTNAME` | up to 255 bytes | Pod or host identity |
| `reactor.glowroot.export.interval-ms` | `60000` | 60000-3600000, multiple of 60000 | Aggregate and gauge interval |
| `reactor.glowroot.connect-timeout-ms` | `1000` | 100-30000 | TCP/h2 connect limit |
| `reactor.glowroot.request-timeout-ms` | `2000` | 100-30000 | Whole unary gRPC lifecycle limit |
| `reactor.glowroot.trace.slow-threshold-ms` | `500` | 1-3600000 | Slow trace threshold |
| `reactor.glowroot.http.sample-rate` | `256` | power of two, 1-1024 | Samples successful HTTP aggregates; `5xx` stays exact |
| `reactor.glowroot.trace.capacity` | `0` | 0-32 | Bounded slow/error trace queue; `0` allocates no trace state |
| `reactor.glowroot.max-routes` | `64` | 1-64 | Maximum HTTP route slots in the 1 MiB profile |
| `reactor.glowroot.max-export-bytes` | `65536` | 16384-65536 | Hard encoded request limit in the 1 MiB profile |

If `reactor.native.capabilities` is explicit, add `glowroot` when the agent is enabled:

```properties
reactor.native.capabilities=http,dubbo,redis,glowroot
reactor.glowroot.enabled=true
```

The default is aggregate-first: sample rate `256` and trace capacity `0`. Test `64` or `128` only
when staging needs a denser successful-request latency distribution. Enable a bounded trace capacity
such as `16` only for an explicit diagnostic use case. Do not change these values without a
disabled/enabled p99, useful-RPS, `503`, process-RSS, and cgroup-memory gate.

Every key has the normal uppercase environment form. For example,
`reactor.glowroot.http.sample-rate` maps to `REACTOR_GLOWROOT_HTTP_SAMPLE_RATE`.

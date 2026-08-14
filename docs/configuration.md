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

## Glowroot Telemetry

The stable `4.5.0` runtime uses REST ABI `29` and Glowroot ABI `3`. It supports bounded runtime
profile switching. Never combine these Java classes with a REST `4.4.x` ABI `28` DLL/SO.

Glowroot Central and Cassandra remain unchanged. Rust-Java REST needs no agent JAR. The native
runtime owns protobuf encoding, the collector connection, profile state, and one isolated `256 KiB`
exporter thread. It does not consume Hyper workers. The optional `java-rust-glowroot-agent.jar` only
maps early `-javaagent` arguments.

| Property | Default | Allowed value | Purpose |
| --- | ---: | --- | --- |
| `reactor.glowroot.enabled` | `false` | boolean | Enables bounded telemetry state and exporter |
| `reactor.glowroot.profile` | `micro` | `micro`, `jvm`, `sql`, `full`, `diagnostic` | Selects the startup profile; the API can change it later |
| `reactor.glowroot.profile.release-timeout-ms` | `5000` | 100-60000 | Maximum synchronous wait for retired profile state |
| `reactor.glowroot.collector.address` | `http://127.0.0.1:8181` | plaintext `host:port` or `http://host:port` | Glowroot Central gRPC over HTTP/2 endpoint |
| `reactor.glowroot.agent.id` | empty | 1-256 bytes | Required stable pod/rollup identity |
| `reactor.glowroot.application.name` | application name | 1-128 bytes | Name displayed by Glowroot |
| `reactor.glowroot.hostname` | `HOSTNAME` | up to 255 bytes | Pod or host identity |
| `reactor.glowroot.export.interval-ms` | `60000` | 60000-3600000, multiple of 60000 | Aggregate and gauge interval |
| `reactor.glowroot.connect-timeout-ms` | `1000` | 100-30000 | TCP/HTTP2 connect limit |
| `reactor.glowroot.request-timeout-ms` | `2000` | 100-30000 | Whole unary gRPC lifecycle limit |
| `reactor.glowroot.trace.slow-threshold-ms` | `500` | 1-3600000 | HTTP slow-trace threshold when the startup queue exists |
| `reactor.glowroot.http.sample-rate` | `256` | power of two, 1-1024 | Samples successful HTTP aggregates; `5xx` stays exact |
| `reactor.glowroot.trace.capacity` | `0` | 0-32 | Startup-owned HTTP trace queue; `0` allocates none |
| `reactor.glowroot.sql.capacity` | `16` | 0-32 | SQL slots allocated only while `sql`, `full`, or `diagnostic` is active |
| `reactor.glowroot.error.trace.capacity` | `8` | 0-16 | Error details retained only by error-enabled profiles |
| `reactor.glowroot.error.max-frames` | `24` | 0-32 | Maximum copied stack frames per error |
| `reactor.glowroot.error.max-bytes` | `4096` | 256-8192 | Maximum UTF-8 bytes per error detail |
| `reactor.glowroot.max-routes` | `64` | 1-64 | Maximum HTTP route slots |
| `reactor.glowroot.max-export-bytes` | `65536` | 16384-65536 | Hard encoded request limit |

If `reactor.native.capabilities` is explicit, add `glowroot`:

```properties
reactor.native.capabilities=http,dubbo,redis,glowroot
reactor.glowroot.enabled=true
reactor.glowroot.profile=micro
```

Use the control API from an authenticated internal operation, never from a public endpoint or the
request hot path:

```java
GlowrootTelemetry.switchTo(TelemetryProfile.FULL, Duration.ofSeconds(5));
// Keep the incident window bounded.
GlowrootTelemetry.restoreConfiguredProfile();
```

Do not automate per-request profile oscillation. SQL slots use a separate positive 32-bit token with
a 25-bit generation, preventing stale-slot aliasing during normal process life. The runtime fails
instead of wrapping only after more than `33 million` state-shape transitions.

The call is serialized and synchronous. `restoreConfiguredProfile()` returns to the startup value
from `reactor.glowroot.profile`; it does not hard-code `micro`. When that baseline is `micro`, SQL
slots, error/diagnostic queues, profile-derived export data, and Rust-owned JNI MXBean global
references have been dropped. Linux also requests `malloc_trim(0)` from the isolated agent thread
after the final reference is gone. Windows releases ownership but does not force a process-wide
working-set eviction.

The isolated thread does not share Hyper workers or the server Tokio runtime. The glibc trim call is
still process-wide, so profile switching is a rare control-plane operation, never a request-path
feature. Attribute RSS with fresh telemetry-off/on processes rather than one post-switch reading.

Create one reusable SQL descriptor per normalized statement. Do not include bind values and do not
construct descriptors per request:

```java
private static final GlowrootTelemetry.SqlStatement FIND_CUSTOMER =
        GlowrootTelemetry.sql("customer.find", "select id, name from customer where id = ?");
```

`http.sample-rate` and `trace.capacity` are startup settings. They are not resized by a profile
switch. Keep `trace.capacity=0` for the strictest `micro` reclamation. Every key has the normal
uppercase environment form; for example, `reactor.glowroot.profile.release-timeout-ms` maps to
`REACTOR_GLOWROOT_PROFILE_RELEASE_TIMEOUT_MS`.

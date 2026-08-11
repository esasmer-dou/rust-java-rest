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

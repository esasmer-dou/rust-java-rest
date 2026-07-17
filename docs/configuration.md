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

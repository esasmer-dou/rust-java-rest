# Operations Runbook

[English](operations.md) | [Türkçe](operations.tr.md)

Before rollout, record container RSS, p50/p95/p99, `503` ratio, JNI queue wait, in-flight response
bytes, active file streams, and dependency pool wait. Compare the same endpoint mix at c64 and c256.

Use `/app/health` for liveness. Use `/app/readiness` for required dependencies. Do not make liveness
call Redis, Dubbo, or a database. A failed readiness check should remove the pod from traffic without
restarting a healthy process.

Treat `503` as bounded overload, not automatically as a bug. Raise a route budget only when the
provider, database pool, CPU, and RSS all have headroom. Increasing a global queue can improve the
success count while making p99 and retained memory worse.

Enable idle native trim only for low-traffic, memory-first pods. Use conservative delays from
`docs/production-runtime.md`; do not trim in request handlers.

At release time run `mvn clean verify`. The build fails if codegen classes leak into the runtime JAR.
Build Windows DLL and Linux SO from the same source revision and verify the packaged ABI manifest.

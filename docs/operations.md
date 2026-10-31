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

## Unreleased: Generated Admission And Owned Responses

Before enabling `@KeyAdmission`, package the generated startup descriptor/invoker and declare its
named `LongKeyAdmission` bean in an active configuration. Startup resolves the bean once; missing
registration fails closed even if `reactor.codegen.route-invoker.required=false`. Use direct
`long`/`String` path/query keys, bounded stripe capacity and dependency deadlines. A stripe collision
shares capacity; this is not a distributed lock, idempotency store or waiting queue.

APT adapts native JSON `NativeResponseHandle` returns, including async and `ResponseEntity` forms,
without reflection in the request path. `DubboResponses.replyAsync(source, DubboResponses::reply)`
keeps observing the source after HTTP cancellation so late owners can be released. Keep the RPC
deadline bounded; do not replace this ownership adapter with a cancellable `thenApply` chain.
Retries rewrite the current response only; no response bytes are cached automatically.

These changes are unpublished. K-001 keeps JNI and existing ABI signatures; additive cleanup exports
need a coordinated future native rebuild and matching provenance, not just new Java classes.

At release time run `mvn clean verify`. The build fails if codegen classes leak into the runtime JAR.
Build Windows DLL and Linux SO from the same source revision and verify the packaged ABI manifest.

## Incident Checklist

1. Record the deployed Java version, native platform, binary SHA-256 and startup ABI result.
   Check the running pod, not just the image tag. Do not copy credentials into incident reports.
2. Check liveness and the application's dependency readiness separately. A ready JVM does not
   prove that its required Redis snapshot, Dubbo service or database is available.
3. For `503`, identify the rejecting route and compare active work, queue wait and downstream
   pool wait. Change one bounded limit at a time; retrying non-idempotent writes can duplicate work.
4. For native response errors, check the Java/native pair and generated invoker first.
   After cancellation, check that in-flight work drains within its deadline. Do not force-close
   a body still being written or remove its ownership adapter to suppress an error.
5. For native HTTPS, check the endpoint hostname, certificate chain, expiry and CA PEM path.
   Never bypass validation. See [native HTTP(S) configuration](../platform/starter-http-client/NATIVE-INTEGRATION.md).
   A byte-budget rejection is capacity protection, not a reason to disable bounds.
6. Compare RSS at baseline, after load and after idle using the same workload and limits.
   A reserved memory budget is not measured RSS. Keep ARM64 runtime verification false until
   real hardware has passed its qualification workflow; cross-compilation alone is not enough.

# Troubleshooting

[English](troubleshooting.md) | [Türkçe](troubleshooting.tr.md)

| Symptom | Check first | Correct action |
| --- | --- | --- |
| Startup says scan fallback | Is `components.idx` packaged? | Add `ReactorStartupProcessor`; do not hand-edit the index. |
| Duplicate route build error | Same HTTP method and normalized path | Remove the duplicate or exclude the inactive profile surface. |
| Generated writer build error | Nested/list field in an annotated record | Use `JsonBodyProducer` or an explicit business writer. |
| Increasing `503` | Route admission and dependency pool wait | Tune that route only; do not enlarge the global queue first. |
| RSS stays high after burst | In-flight bytes, pools, allocator retention | Run idle/soak evidence; enable conservative idle trim only if p99 passes. |
| Old DLL/SO mismatch | Startup ABI and provenance error | Replace the native artifacts with binaries from the same release. |
| Turkish characters are wrong | Request and response content type | Send UTF-8 and keep `charset=utf-8`; do not use platform-default encoding. |
| Unreleased `@KeyAdmission` route fails startup | Generated invoker and named `LongKeyAdmission` bean | Run APT and package generated startup registration; fix the bean name/type. Disabling the global required-invoker option does not bypass this guard. |
| Unreleased admission codegen rejects a key | Exactly one direct path/query `long` or `String` parameter | Use its Java or binding name; DTO/header/body keys and borrowed-output-buffer routes are unsupported. |
| Unreleased native adapter reports a missing cleanup symbol | Java/native provenance and `discardNativeResponse` export | Use a coordinated future native build; an unchanged ABI number does not prove additive-symbol availability. |

Generated code lives under `target/generated-sources/annotations`. Inspect that directory before
assuming a runtime fallback. Route metadata lives in `target/classes/META-INF/reactor`.

## Incident Checklist

1. Record the JAR version, native SHA-256, ABI and active profile. A matching ABI alone does not prove that a new optional JNI export exists. Do not include credentials in reports.
2. Separate application health from dependency readiness. A healthy HTTP listener does not prove that Redis, Dubbo or the database is reachable.
3. Compare successful request rate, p99, rejection count and dependency wait for the affected route. A fast `503` is not a successful request. Change one bounded limit at a time.
4. For native HTTPS startup failures, check the coordinated JAR/SO release first. For handshake failures, check hostname, certificate expiry and trusted CA. Never disable certificate verification.
5. For memory that remains high after traffic stops, compare idle RSS with native in-flight bytes and live response handles. A configured byte limit is not an RSS measurement. Do not release a borrowed buffer before its callback ends.

Use the [operations checklist](operations.md#incident-checklist) for the next diagnostic steps and
[native compatibility guide](native-availability.md) before replacing a DLL or SO.

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

Generated code lives under `target/generated-sources/annotations`. Inspect that directory before
assuming a runtime fallback. Route metadata lives in `target/classes/META-INF/reactor`.

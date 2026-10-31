# Native Package Compatibility

[English](native-availability.md) | [Turkish](native-availability.tr.md)

## Choose The Matching Native File

REST 4.8.0 and cache 0.9.0 contain clean, same-source native builds.
Source: `3418671b5010afb5dc049897f2a7287dc6a58daf`; build: `40834723168`.

| Platform | Packaged file | Evidence boundary |
| --- | --- | --- |
| Linux x64 | SO; GLIBC 2.17 or later | Runtime checks run on EmaTech |
| Windows x64 | DLL; cross-compiled without Visual Studio | No new Windows runtime certification |
| Linux ARM64 | SO; GLIBC 2.17 or later | Clean compilation; no ARM runtime certification |
| macOS ARM64 | dylib; macOS 11 or later | Clean compilation; no ARM runtime certification |

Published 4.8.0 contains these ARM64 bytes but stale platform sidecars, so the
provenance check rejects them. The 4.8.1 patch corrects
metadata only; it does not rebuild the binaries or add ARM runtime evidence
(`runtime.verified=false`). See [4.8.1 notes](release-notes/v4.8.1.md).

Standalone agent packages contain Linux x64 and Windows x64 only. With REST/cache, the coordinated runtime owns the exporter; a second engine is not loaded.

The ordinary runtime enables WebSocket, Dubbo, Redis, Glowroot, edge cache, JWT, outbound HTTP and shared slab at build time.
Availability does not activate an optional feature or move business rules out of Java.
Contracts remain REST 29, Dubbo 7, Redis 6 and Glowroot 6; separate Spring Dubbo remains ABI 3.

OpenJ9 uses JNI by default. FFM is a separate opt-in HotSpot Java 21 module.
Never replace the default packaged binary with the FFM binary implicitly.

The loader first reads the selected binary's platform sidecar in its owning JAR.
Only an absent sidecar permits the root manifest in that same JAR. Invalid metadata, hash or ABI fails startup.
REST also verifies its source revision. Checks run at startup, not on each request.
Do not mix old DLL/SO files with new JARs. Compilation evidence is not a cross-platform performance guarantee.

## Qualify ARM64 On EmaTech

Use real ARM64 hardware. A cross-build or QEMU smoke test does not qualify a production runtime.
The `rust-spring` workflow `arm64-runtime-qualification.yml` requires an EmaTech
self-hosted runner with the `ARM64` and `native-hardware` labels.

An operator first stages a reviewed `plan.json` under `ARM64_QUALIFICATION_PLAN_ROOT`.
The plan identifies the actual binaries, hashes, existing verifier commands and paired performance measurements.
Run it from the `rust-spring` checkout on that runner:

```sh
python3 scripts/qualify-arm64-runtime.py --platform linux-aarch64 \
  --plan-root /opt/ematech/arm64 --plan plan.json --output /tmp/arm64-proof
```

Use `--platform osx-aarch64` on a real macOS ARM64 runner. The receipt records its JVM separately;
it is not OpenJ9 production proof. The runner checks packaged provenance, all five ABI handshakes,
edge features, HTTPS, slab safety and resource regression. Missing evidence fails qualification.
The script never edits sidecars or marks `runtime.verified=true`. That change requires review of a complete receipt.

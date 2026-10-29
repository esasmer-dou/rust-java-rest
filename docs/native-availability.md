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

# Build The Native HTTP Transport

[English](NATIVE-INTEGRATION.md) | [Turkish](NATIVE-INTEGRATION.tr.md)

The native outbound transport is an opt-in 4.8.0 feature. Resolve the released
Java starter and use a matching library with `outbound-http` capability; an ABI
number alone does not prove the capability. If absent, build the selected feature
from matching sources. The default
transport is still `jdk`; native outbound resources are not created unless selected.

## Build The Native Library

The `outbound-http` Cargo feature and conditional module are already integrated.
No Cargo or `lib.rs` patch is required. The feature is not enabled by default.
From the `rust-spring` directory, with a working host Rust toolchain:

```sh
cargo build --release --locked --features outbound-http
```

The host artifact is `target/release/librust_hyper.so` on Linux or
`target/release/librust_hyper.dylib` on macOS. Build for the OS and architecture of
the application's JVM. This feature uses the existing Hyper/Tokio dependencies;
it does not require hyper-util's legacy client or an additional TLS dependency.

Windows builds can use a prepared cargo-xwin/LLVM SDK cache without Visual Studio.
For a cache containing `lld-link.exe` at the location below:

```powershell
$env:XWIN_CACHE_DIR=Join-Path $env:LOCALAPPDATA 'cargo-xwin'
$env:CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER=Join-Path $env:XWIN_CACHE_DIR 'lld-link.exe'
cargo xwin build --release --locked --target x86_64-pc-windows-msvc --features outbound-http
```

The Windows artifact is
`target/x86_64-pc-windows-msvc/release/rust_hyper.dll`. A cross-compiled artifact
must still be tested on its target OS. The build instructions are not evidence
that release artifacts for every platform have been validated.

## Configure The Application

Use the existing `NativeLibraryLoader` with an explicit absolute library path:

```text
-Drust.lib.path=/absolute/path/librust_hyper.so
```

On Windows, use an absolute DLL path such as
`-Drust.lib.path=C:/native/rust_hyper.dll`. Configure the transport before runtime
creation and bind generated clients once during application startup:

```properties
reactor.http-client.transport=rust-native
app.stock.base-url=http://stock-service:8080
```

REST ABI 29 is preserved. The additive outbound JNI API has its own version 1
check, and its symbols are probed at transport startup. A missing feature, missing
symbol or incompatible API fails startup with a diagnostic. There is no implicit
JDK fallback. Selecting `jdk` explicitly at startup retains the existing transport.

This implementation is **HTTP/1-only**. HTTPS is rejected when the client is bound,
before any request is sent. It has no insecure TLS mode and does not downgrade
HTTPS to HTTP. Verified TLS, HTTP/2, proxies, redirects, upgrades and transparent
decompression are unsupported. This is **not full production HTTPS coverage**.
Choose JDK at startup when those capabilities are required. Native I/O never
reconnects/replays a possibly delivered request; only the existing explicit,
idempotency-aware application retry policy can start another attempt.

See [resource limits and admission](README.md#native-resource-limits) for property
names, defaults and reservation rules. Explicit existing `reactor.http-client.*`
configuration takes precedence over native defaults. Admission is fail-fast, not
an unbounded queue. Native metadata uses `try_read`/`try_lock`, not a lock-free
design; Java completion and application code run outside Tokio I/O threads.

## Test The Actual Artifact

From the `rust-java-rest` source checkout, after its normal dependencies are built:

```sh
mvn -f platform/starter-http-client/pom.xml -Dreactor.http-client.native-test-library=/absolute/path/librust_hyper.so test
```

On Windows, supply the absolute DLL path instead. The explicit
`reactor.http-client.native-test-library` property enables real JNI/loopback native
tests. Without it, native socket tests are skipped, not counted as native proof.
The bytecode comparison uses a checked-in instruction snapshot from pre-change
4.8.0 source. It does not trust the mutable local Maven cache. You can additionally
check a genuine pre-change source-build JAR with
`-Dreactor.http-client.baseline-jar=/absolute/path/baseline.jar`. A candidate JAR is
rejected rather than compared with itself.

The integrated tests cover HTTP requests, raw UTF-8 and bytes, bounded headers and
bodies, explicit retries, deadlines, cancellation, close, connection reuse and
resource admission. Fresh child JVMs record actual VM class-load logs. The native
child verifies REST ABI 29, outbound feature presence, exactly zero JDK HTTP client
threads and no `HttpClientImpl`, `HttpClientFacade` or `SelectorManager` load. A JDK
positive control must start the real JDK client; a feature-less library must fail
without fallback. These checks do not use fake native transport stubs.

Actual native correctness proof currently covers an integrated Windows x86-64
debug DLL. It does not establish Linux/macOS or release-profile acceptance. The
1 MiB reservation default and 3 MiB configuration ceiling are transport-buffer
limits, not process RSS guarantees. The **3 MiB incremental under-load memory gate,
128 MiB container gate and throughput/release gates remain open**. Transient shared
DTO encoder and application decoder allocations are outside those reservations.

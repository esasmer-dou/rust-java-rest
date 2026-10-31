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
it does not require hyper-util's legacy client. The outbound feature includes
`tokio-rustls`, `rustls` with the ring provider, bundled WebPKI roots and the PEM
reader. It does not link OpenSSL or change the default REST feature selection.

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

The next coordinated Java/native build supports HTTP/1.1 over HTTP or verified
HTTPS. Use matching artifacts; older published libraries lack the additive TLS
configuration export. Certificate chain, expiry and SAN/hostname validation are
mandatory. `reactor.http-client.native.tls.ca-pem-path` adds an absolute PEM trust
file; it does not replace validation. A runtime retains one TLS configuration,
with bounded origins and connections. TLS session resumption is disabled: a
session-count limit alone would not bound peer-provided certificate and ticket
bytes. HTTP keep-alive still reuses established TLS connections. DNS, TCP and TLS share
the connect deadline. TLS memory is included in byte reservations through JNI
completion copying, driver termination and cancellation cleanup.

The native byte budget includes 64 KiB per active TLS call and 128 KiB per TLS
connection, plus 16 KiB per client for TLS configuration and its trust-anchor
vector. Built-in certificate bytes are shared read-only. A custom PEM file is
limited to 256 KiB and 32 trust anchors; parsing scratch and retained certificate
bytes are reserved before allocation. There are at most eight client runtimes.
These reservations are budget accounting, not a promise of an equal RSS increase.
Disabling session resumption can increase CPU on new connections; keep-alive
and the measured endpoint workload determine that cost.

Set `reactor.http-client.native.tls.enabled=false` before startup to select JDK
for the entire client runtime, not an insecure native mode. HTTP/2, proxies,
redirects, upgrades and transparent decompression remain outside the native
transport. Choose JDK for those capabilities. Native I/O never
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

The earlier integrated Windows x86-64 debug checks do not qualify other platforms.
On October 31, 2026, the current-source Linux x86-64 candidate passed all 38 Java
TLS/transport tests and both JNI response-ownership tests on EmaTech with OpenJ9 21.
This is functional validation using an explicit native-library override, not
packaged-JAR validation, confirmation of a published release or full performance
qualification. ARM64 and macOS hardware runtime qualification remains pending; see
[native availability](../../docs/native-availability.md).

The 1 MiB reservation default and 3 MiB configuration ceiling are transport-buffer
limits, not process RSS guarantees. The **3 MiB incremental under-load memory gate,
128 MiB container gate and throughput/release gates remain open**. Transient shared
DTO encoder and application decoder allocations are outside those reservations.

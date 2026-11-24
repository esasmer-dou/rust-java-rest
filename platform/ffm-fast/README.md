# Optional Java 21 Preview FFM Bridge

[English](README.md) | [Türkçe](README.tr.md)

Experimental: built only by the explicit platform `-Pffm-fast` Maven profile and
native `ffm-fast` Cargo feature. NOT part of default reactor/BOM/core dependencies.
No existing JNI route or ABI constant is changed. Core never references this module.
Absent an explicit application opt-in, it adds no core classes, checks, buffers,
native loads or threads. Whole-process acceptance is separate from leaf allocation tests.

## Current Source Integration

The separate [shared-slab cache adapter](../ffm-cache/README.md) now connects this
bridge to existing managed HTTP response ownership. The cache-reader `shared-slab`
source profile demonstrates raw Redis GET without a Java payload array. Default
core/cache behavior does not change. This is not the versioned atomic cache API.

On 26 October, sustained load exposed a retention defect in OpenJ9 Java 21's FFM
downcall scope stack. The PHD histogram and the actual VM class bytecode confirmed
unreleased HeapArgInfo entries. This VM is now rejected BEFORE loading PanamaAccess
or the linker. Do not patch JDK internals or increase heap to hide this behavior.
Use `SharedSlabCacheReader.openNativeSlab` for the explicit OpenJ9-safe JNI transport
over the SAME Rust slab protocol. FFM on qualified HotSpot remains a separate choice.

Historical 26 October 2026 focused-test JNI slab (ordinary abort) SHA256:
`0c68e2e417e3be7949904f783e9db4948f120ab06c6851e132ee5c7c1120f8b5`.
Historical separate Linux FFM (unwind) SHA256:
`9d6f7e5a648fb7519c22d74ea6e527c35357cc4dda8526eb9ea09f54f9abff56`.
At that checkpoint no default resource had been replaced or published and Windows
distribution was not verified. These hashes/results are historical test artifacts,
not the 29 October coordinated release manifest. The Java modules are now published
in 4.8.0; that does not qualify HotSpot FFM on Windows or replace its separate unwind
binary. Use release provenance and target-platform acceptance, not old hashes.

## Startup Choice And Lifetime

Use Java 21 with explicit `--enable-preview --enable-native-access=ALL-UNNAMED`
(or the actual named module). Load the separately approved native library through
the existing application lifecycle BEFORE selection. This module never loads a
native library automatically.

```java
FfmFastBridge fast = FfmFastBridge.tryActivateForRedisClient(
        FfmFastBridge.SLAB | FfmFastBridge.GLOWROOT | FfmFastBridge.REDIS,
        existingClientId, 16);
// null: select your EXISTING JNI implementation for this class loader's lifetime.
// non-null: errors/statuses propagate; NEVER replay an operation through JNI.
```

The entry class contains no foreign API references and has class-file minor
version 0. The JVM authoritatively enforces preview loading. The preview factory
returns the ordinary `Calls` interface and checks `Module.isNativeAccessEnabled()`
BEFORE creating a linker. It never requests warning-only implicit native access.
Missing symbols, preview/native access,
or blocked OpenJ9 Java21 decline BEFORE the first operation. A selection
is one-time; successful activation cannot reconfigure or later select JNI.
There is no ManagementFactory, JMX initialization or input-argument parsing.
The module check observes current permission, not its provenance: access enabled
earlier by another restricted call is not distinguished from a launch flag.
FFM/linker startup class costs remain part of the future RSS measurement.

This is a separately packaged startup adapter, not automatic integration into
NativeTelemetry or RustCache. The explicit ffm-cache adapter is optional.
Redis selection MUST use `tryActivateForRedisClient` before choosing a business
path. The new C `reactor_redis_client_status` query checks the existing registered
client's read access and `max_response_bytes` in 1..65536 before any slab allocation
or GET. The default 1 MiB response limit is intentionally incompatible and declines
to JNI at startup, not after a failed GET. Generic `tryActivate` rejects REDIS.
Selection is bound to that client ID; another ID is UNSUPPORTED without I/O/replay.
The caller retains the existing client's lifecycle; closing it can invalidate
later GETs. Native feature capability bits alone do NOT certify a client.

## Memory And Ownership

- Token layout retains 64 slot indices, each with 64 KiB offsets. Only 16 slots
  (1 MiB payload) are allocated by default; explicit startup capacity is 1..32
  (at most 2 MiB). First successful configuration freezes capacity atomically,
  before allocation; a competing class-loader selection cannot change it.
- 64 cache-line-aligned control entries occupy 4096 bytes, plus fixed metadata.
  The inactive 64-slot logical layout is 4 MiB, NOT 4 MiB of allocated payload.
- Native allocation uses alloc_zeroed on the bounded heap block, never a 4 MiB
  stack array. Native slab storage has process lifetime; no unsafe unload/free API.
- Native acquisition scans at most 32 active states, returns a
  primitive epoch lease, or zero when full. Every read, write, copy and GET claims
  an epoch-matched BUSY state. Release cannot free/reuse BUSY storage; stale and
  duplicate tokens fail. Epoch exhaustion retires slots rather than wrapping.
  Acquisition resets an initialized-prefix length under BUSY, NOT 64 KiB of data.
  Checked reads/copies synthesize zero beyond this epoch's prefix. Writes clear
  only a gap between the prefix and a new write. Sparse writes can still clear
  almost 64 KiB; copying logical-zero output costs the requested length. This
  removes the unconditional acquire clear, NOT the need for measured A/B evidence.
  The raw C base-pointer export is metadata, not a safe byte view: bypassing checked
  lease operations can expose stale physical bytes and races and is unsupported.
- Java exposes no native slab segment/address. Caller direct buffers are wrapped
  ONCE for reuse; wrappers allocate but calls do not intentionally allocate. The
  bridge does not allocate another staging payload. Caller buffers still count
  toward the application's memory budget. For externally native-owned input, use
  `wrapBuffer(bytes, nativeOwner)`: calls retain/fence that owner through the access,
  even across concurrent wrapper close. Close detaches both references but does
  not free caller memory or revoke an admitted call. The caller must NOT explicitly
  close/free the owner or mutate/reuse input until ALL accesses/copies finish.
- Redis still uses its existing bounded response Vec. FFM rejects clients whose
  configured response limit exceeds 64 KiB BEFORE executing GET. At default
  capacity, up to another 1 MiB of simultaneous response payloads is possible;
  at 32 slots, another 2 MiB. Existing connection/topology buffers also remain.
  Fixed payload bounds are NOT proof of the <=+3 MiB whole-process RSS gate.

## Actual Operation Semantics

`reactor_glowroot_record_op_fast` calls the existing `glowroot::record_operation`,
including slow/error trace offers. It corresponds to nativeRecordOperation, not
the Java API's additional Throwable detail capture, which stays on NativeTelemetry.
Metric slot IDs must come from the existing registration API, not slab leases.

`reactor_redis_get_into_slot(lease, clientId, keyOffset, keyLength)` uses the EXISTING
registered native client, full nonempty UTF-8 key, handle_result and command_get.
Namespace construction remains with the caller. Single/sentinel/cluster routing,
access modes, connection pools, admission, timeouts and native metrics are reused.
There is no extra retry or side-effect replay. On hit, slot offset zero contains
the bytes. Return high 32 bits=status, low 32 bits=length; MISS differs from empty
hit. An admitted GET clears the logical result on every failure, including panic;
BUSY/STALE rejection never touches another operation's slot. Consume/copy while
the lease is held, then release in finally.
This is a synchronous raw GET, not the versioned-cache atomic read or async API.
Use existing bounded blocking-call lanes, never an HTTP/event-loop executor.

Only API-version/capability/size metadata getters use JDK21 Linker.Option.isTrivial().
If the VM rejects the hint, these handles are linked ordinarily during preflight.
Allocation, slot operations, full Glowroot recording and blocking Redis use ordinary
downcalls. No newer critical() API, upcalls, reflection or runtime code generation.

## Applied Opt-In Wiring

The following build wiring is explicitly opt-in. Cargo feature, NOT default:

```toml
ffm-fast = ["redis", "glowroot"]
```

Declaration INSIDE `rust-spring/src/redis_cache.rs` (not lib.rs):

```rust
#[cfg(any(feature = "shared-slab", all(feature = "ffm-fast", panic = "unwind")))]
#[path = "ffm_fast.rs"]
mod ffm_fast;
```

The private-child scope permits use of existing private Redis helpers without
exposing client internals or adding a second registry. Glowroot remains accessed
through its existing crate API. Moving this file to a root module unchanged is
NOT valid. Existing REST29/Dubbo7/Redis6/Glowroot6 ABI constants stay unchanged.

Current release panic=abort CANNOT be made recoverable by catch_unwind. Use the
separate opt-in profile below before FFM activation. The module
is gated out under abort unless the explicit JNI `shared-slab` feature is selected;
abort builds advertise zero FFM capabilities so foreign activation declines first.

The explicit `shared-slab = ["redis"]` feature is different: it permits JNI slab
ownership in the ordinary abort engine. The private module condition is now
`any(feature="shared-slab", all(feature="ffm-fast", panic="unwind"))`.
FFM capabilities remain zero under abort, so a foreign caller must decline
before configuring a slab or doing I/O. JNI keeps the existing engine's abort
panic policy. Only the separate unwind artifact advertises active FFM.
Choose one native variant per process; never load both to change transport later.

```toml
[profile.release-ffm]
inherits = "release"
panic = "unwind"
```

Ordinary release still uses panic="abort". The optional platform Maven profile
adds `ffm-fast` and `ffm-cache`; no preview dependencies or flags are introduced into core.
No default native artifact is copied/replaced by experimental verification.

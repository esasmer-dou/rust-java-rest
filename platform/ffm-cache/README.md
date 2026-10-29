# Optional Shared Slab Redis Reader

[English](README.md) | [Türkçe](README.tr.md)

Read raw Redis values without creating a Java payload `byte[]` for every hit.
Java still owns the handler and business decisions. Rust performs the Redis GET
and holds its result in bounded native storage until HTTP serialization finishes.
This is a source-only opt-in module, not a replacement for the default cache API.
Published 4.8.0 artifacts do not yet include these additions.

## Choose The Right Read

| Need | Use |
| --- | --- |
| Published, versioned projection or atomic snapshot | Existing `RustCache` projection API |
| Raw JSON key, value at most 64 KiB, direct HTTP output | This optional reader |
| Larger value, ordinary Java processing, no preview flags | Existing JNI API |

Do not replace a versioned read with raw GET. That would lose the snapshot's
publication consistency. The FFM path does not add a cache or hide Redis requests.
It reuses the native client's connection pools, timeouts and topology routing.
The focused integration gate uses standalone Redis, not live Cluster/Sentinel failover.

`maxResponseBytes` is the existing RESP **wire-byte** budget, not just value length.
With a65536-byte budget, a five-digit bulk length plus framing uses10 bytes; the
largest such value is65526 bytes. A65527-byte value is rejected. The64KiB slab
capacity does not remove protocol overhead. Larger wire budgets use the ordinary
JNI reader; the slab preflight deliberately does not relax your client's limit.

## Build And Start

Build the platform with its explicit `ffm-fast` profile. Both `rust-java-ffm-fast`
and `rust-java-ffm-cache` are separate artifacts. Neither belongs to the default
core/BOM dependency graph. For OpenJ9 JNI slab build the matching native source
with `--release --features shared-slab`; the ordinary abort engine is retained.
For HotSpot FFM use `--profile release-ffm --features ffm-fast`. Abort builds
advertise zero FFM capabilities and must not activate foreign calls. Existing
REST29/Dubbo7/SpringDubbo3/Redis6/Glowroot6 contracts are unchanged.

```xml
<dependency>
    <groupId>com.reactor</groupId>
    <artifactId>rust-java-ffm-cache</artifactId>
    <version>4.8.0</version>
</dependency>
```

This dependency snippet requires a local source build until a new release is
published. Do not combine it with an older published native file.

```text
java --enable-preview --enable-native-access=ALL-UNNAMED \
  -Drust.lib.path=/app/native/librust_hyper.so -jar application.jar
```

For OpenJ9 21, open the shared slab through JNI. Its FFM linker retains per-call
scope objects under sustained load, so this adapter rejects FFM before loading
the preview implementation. There is no internal-JDK reflection or ThreadLocal
cleanup hack. No request is replayed. Open one reader at startup:

```java
RustCacheConfig config = RustCacheConfig.builder()
        .host("redis").port(6379).accessMode("read-only")
        .readConnections(1).writeConnections(1)
        .maxReadInflight(1).maxWriteInflight(1)
        .maxResponseBytes(65_536).build();
SharedSlabCacheReader reader = SharedSlabCacheReader.openNativeSlab(config, true, 4, 256);
// Required native slab, 4 slots, 256 UTF-8 key bytes. No preview flags needed.
```

For an explicitly qualified HotSpot Java 21 deployment, `open(config, true, true,
4, 256)` selects FFM with the preview/native-access flags above. `open(config,
false, false, 4, 256)` selects the ordinary payload-array JNI read. On OpenJ9,
optional FFM selects that ordinary JNI read at startup; required FFM refuses startup.
`openNativeSlab` is the explicit OpenJ9-safe slab alternative, not a hidden fallback.

In a framework handler, return the existing managed response:

```java
return CacheResponses.ownedProblem(reader.read("catalog:stock"));
```

Imports: `com.reactor.rust.cache.config.RustCacheConfig`,
`com.reactor.rust.ffm.cache.SharedSlabCacheReader`,
`com.reactor.rust.cache.http.CacheResponses`.
The managed writer owns the returned snapshot. It releases it on completion or
failure and can retry a larger output buffer without issuing another Redis GET.
The output buffer must be direct. No DTO or JSON parsing is required on this path.

For use outside managed HTTP output, close every snapshot explicitly:

```java
try (CacheReadSnapshot snapshot = reader.read("catalog:stock")) {
    // Inspect hit/miss and write through snapshot's API into caller-owned direct storage.
}
```

`CacheReadSnapshot` is in `com.reactor.rust.cache.api`. Do not discard an unclosed
snapshot. There is no per-snapshot Cleaner: an abandoned lease can exhaust the
bounded slab until the process restarts. Transfer ownership once or use try-with-resources.

## Selection And Limits

| Argument | Meaning |
| --- | --- |
| `requested=false` | JNI; no linker activation or slab allocation |
| `requested=true, required=false` | Attempt FFM at startup; otherwise select JNI before the first GET |
| `requested=true, required=true` | Fail startup if flags, library or client limits are incompatible |
| `slots=1..32` | Fixed process capacity; 64 KiB per slot |
| `maxKeyBytes=1..4096` | Bound UTF-8 key storage; malformed UTF-16 is rejected |
| `maxResponseBytes=1..65536` | Required for FFM; higher configured limits select JNI or fail strict startup |

`openNativeSlab(config, required, slots, maxKeyBytes)` uses the same limits and
native owner but calls additive JNI entries instead of FFM. An older
native file declines at startup. With `required=true`, missing support is fatal.
`slabActive()` and `ffmActive()` distinguish actual storage from the transport.

Selection is once per class loader and binds the native client ID. Use one reader
per process lifecycle. An active reader never silently changes back to JNI.
After a GET starts, BUSY, timeout, oversized value or native error is reported as
`FfmReadException`, never retried through JNI. Map it to an explicit bounded
failure response, for example 503. A miss and an empty hit remain distinct.
The existing native client may perform its configured transport/topology recovery;
this adapter adds no retry policy. Blocking GET must run in a bounded worker lane,
never on the Hyper/Tokio event loop.

## Memory And Shutdown

Four slots reserve 256 KiB payload plus a 4096-byte control table. JNI needs only
`slots * 8` direct bytes for lease results. It passes the existing String to JNI;
Rust validates UTF-16 and encodes bounded UTF-8 in stack storage before I/O.
There are no Java key buffers, key wrappers or per-byte Java encoding loops on
this path. FFM still uses `slots * (maxKeyBytes + 8)` direct input bytes.
Batched standalone GET reads small and large RESP bodies directly into the
owned slot, without a temporary payload Vec or a Vec-to-slab copy. Small replies
retain their existing body/CRLF transport read pattern; the size class is not a
new response limit.
Sentinel/Cluster keep
their existing routing/recovery and temporary response Vec; no GET is replayed
to switch paths. The older in-slot-key C entry also retains its safe copy path.
Native buffers for other operations, FFM/JVM classes, HTTP buffers and reader
objects also consume memory. Snapshot
and segment objects still allocate: this is not an end-to-end zero-allocation or
zero-copy claim. It removes Java payload arrays, not every native copy.

The runtime also reuses its canonical UTF-8 JSON header and avoids temporary
header lists in the owned-frame path (bodies of at least 16 KiB). This does not
cache your response. Each request
still runs your handler and GET, and custom headers keep their normal behavior.
No JVM, heap or profile change is required for this header preparation.

Close the reader during application shutdown. New reads then fail; admitted reads
finish before the native client is closed. Already returned snapshots remain
valid until explicitly released. Input wrappers are detached. Slab storage is
process-lifetime and is not freed by `close()`. No forced GC or unsafe unload is used.

The runnable isolated example is the cache reader's `shared-slab` profile.
See its [guide](../../../rest-sample-cache-reader/SHARED_SLAB.md) for environment
variables and exact startup flags. Default reader builds remain JNI-based.

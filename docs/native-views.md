# Borrowed Native Views

[English](native-views.md) | [Turkish](native-views.tr.md)

This API is published in `4.8.0`. Container RSS, RPS and p99 qualification remains
required for your target workload; publication does not close that acceptance gate.

Allocation tests first check repeated small, retained allocations. A JVM that
reports only delayed allocation chunks skips that measurement; it is not counted
as an allocation pass. A calibrated Java 21 HotSpot run is the allocation oracle.
This does not change the supported OpenJ9 production runtime or certify container RSS.

For an executable Rust request-buffer example, see the sample reader's separate
[`native-view` profile](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/NATIVE_VIEW.md).
It shows generated binary production, synchronous borrow lifetime and capacity retry.
The normal Redis sample stays separate; select the isolated profile explicitly.

## Choose The Right Model

| Need | Use |
| --- | --- |
| Ordinary request validation and business data | Record DTOs |
| Already encoded JSON that Java need not inspect | `RawResponse` or the native response path |
| Read selected fields from an existing binary buffer without a DTO graph | `@NativeView` |
| Produce JSON directly from business values | A producer or generated direct JSON writer |

A view does not decode arbitrary JSON or Hessian. It reads a specific generated binary
layout. It does not automatically change HTTP routes, Dubbo contracts or cache results.

`META-INF/reactor/contracts.tsv` records the view schema version and every field's
index and offset, including default annotation values. Changing any of these changes
the contract manifest even when the Java getter signatures stay the same. Reordering
method declarations does not change it. Enum fields also record constant order,
including enums nested in another class: changing ordinals changes the view contract.
Compare manifests between producer and reader
builds before deploying a binary-layout change. This check is structural, not proof
that both applications assign the same business meaning to a field.

## Define A View

```java
package example;

import com.reactor.rust.view.NativeView;
import com.reactor.rust.view.Utf8Slice;
import com.reactor.rust.view.ViewField;

@NativeView
public interface AccountView {
    @ViewField(index = 0) long id();
    @ViewField(index = 1) Utf8Slice code();
    @ViewField(index = 2) long balanceCents();
    @ViewField(index = 3) boolean active();
}
```

Keep the normal framework annotation processors enabled. Compilation generates
`AccountView_NativeView` and `AccountView_NativeWriter` in the same package.

## Write, Borrow And Close

```java
import com.reactor.rust.view.Utf8Slice;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

ByteBuffer storage = ByteBuffer.allocateDirect(256);
ByteBuffer json = ByteBuffer.allocateDirect(256);

// Allocate these buffers once for the chosen owner, not once per HTTP request.
try (var code = new Utf8Slice().reset(
        ByteBuffer.wrap("ACC-42".getBytes(StandardCharsets.UTF_8)), 0, 6)) {
    int size = AccountView_NativeWriter.write(storage, 0, 42L, code, 1299L, true);
    try (var account = AccountView_NativeView.threadLocalView(storage, 0, size)) {
        long id = account.id();
        boolean matches = account.code().contentEqualsAscii("ACC-42");
        json.clear();
        int bytesWritten = AccountView_NativeWriter.writeJson(account, json);
        // Consume json[0..bytesWritten) before reusing the output buffer.
    }
}
```

The binary writer preserves position, limit and byte order. The JSON writer advances
the output position. A failed JSON write can leave partial output; discard it.

## Storage Lifetime Is Part Of The Contract

The view borrows storage. It does not own or extend a native response lease. Keep the
native owner alive and the bytes immutable until the view closes. Then release the
native owner. Never retain a view, nested view or slice in a field or an async callback.

Each generated thread-local view permits one outstanding borrow. A reentrant borrow
fails instead of overwriting another invocation's view. Closing detaches all retained
buffer references. Input and output must not share storage, even through buffer slices
or duplicates. This aliasing cannot always be detected by the framework.

All multibyte fields use big endian. Relative descriptors cannot point into the header
or beyond the supplied record length. Prefer the three-argument borrow when adjacent
records share one buffer. Schema version and record length are transport metadata;
the binary writer does not insert them into the record.

## Allocation Boundaries

Integer, boolean, enum and UTF-8 getter/JSON paths can reuse their wrappers. The
allocation test covers this path. `materializeString()`, `toString()` and subsequences
allocate. Float/double formatting can also allocate. These are explicit conveniences,
not zero-allocation guarantees for every schema.

Views are thread-confined. Reference fields are non-null. Generic schemas, cycles,
inaccessible nested schema metadata and enums with more than 256 values fail compilation.

## Optional Prebaked JSON Writes

This SWAR option is available in the source checkout only; it has not been released
in a new Maven package. The focused real-HTTP comparison passed on EmaTech on
26 October 2026: four fresh process pairs, concurrency 16/64/256, control and
literal routes, 1 CPU/128 MiB per application, and zero response errors.

For generated record writers, `-Areactor.json.swar=true` enables prebaked literal
writes. Without the option, generation retains the existing byte-table path. Both
paths preserve JSON bytes and buffer-capacity retry accounting. There is no runtime
property check or reflective lookup on the write path. Explicit `false` is identical
to omitting the option; other values fail compilation.

Applications inheriting `rust-java-platform-parent` can select the A/B arm with
`-Dreactor.json.swar=false` (default) or `-Dreactor.json.swar=true`. Run a **clean build
of the artifact containing the annotated DTO**, not just its downstream application.
A JVM `-D` at application startup cannot change already generated writers.
Standalone builds must wire the Maven property to the annotation processor:

```xml
<properties><reactor.json.swar>false</reactor.json.swar></properties>
<!-- Inside maven-compiler-plugin/configuration; retain other compiler arguments. -->
<compilerArgs><arg>-Areactor.json.swar=${reactor.json.swar}</arg></compilerArgs>
```

Only records annotated with `@GenerateDirectJsonWriter` use this processor. The
consumer sample's optional `direct-json-consumer` profile includes an annotated
`SwarPayload` returned by `/api/v1/catalog/literal`. Inspect its generated
`SwarPayloadDirectJsonWriter` for `writePrebakedLiteral` calls when SWAR is enabled.
The property does not convert unannotated DTOs or already encoded cache JSON to SWAR.

Enabled generation chooses by the UTF-8 byte length of the complete `"field":` prefix,
not the number of characters in the field name:

- **8..16 bytes:** one `writePrebakedLiteral(first, last, len)` call, with no retained
  tail bytes for that prefix. The constants pack the first and last eight bytes,
  low byte first. Their overlapping bytes must agree. With enough space, the method
  uses absolute `ByteBuffer.putLong` stores at the prefix start and at `start + len - 8`;
  these stores can overlap. At length 8 it stores only once and ignores `last`.
- **More than 16 bytes:** the existing `writePrebakedLong` word path and 0..7-byte
  tail-table path are unchanged.
- **Fewer than 8 bytes:** the existing byte-table path is unchanged.

UTF-8 sequences may cross word boundaries without changing their bytes. Full-word
stores use the packed word unchanged for little-endian buffers and
`Long.reverseBytes(word)` for big-endian buffers. There is no VarHandle ByteBuffer
view or per-request word-array allocation. The caller's byte order, position and
limit are preserved. Heap/direct buffers and unaligned offsets are supported.

The fused method rejects lengths outside 8..16. It checks both cursor and
required-byte integer additions before any write or counter update; overflow throws
`ArithmeticException`. A short buffer uses a bytewise fallback only up to its limit,
while accounting for the full prefix. The completed writer returns the negative
exact required length, including value bytes, when space is insufficient. Respect
the existing bounded response-retry contract; do not send truncated JSON or repeat
business side effects to retry serialization.

This remains a default-off build-time option, not a universal speedup. Literal expansion
also adds bytecode/JIT work; it does not remove DTO construction, value escaping or
floating-point formatting. Do not claim zero allocations or a universal speedup.
The cache library's separate `Utf8JsonBuffer.writePrebakedLong(long)` byte-array
VarHandle API is unchanged and has no automatic generator integration; see its
bilingual functional-operations guide for composition.

### Focused Verification

From the workspace root, with the matching dependencies installed:

```sh
mvn -B -ntp -f rust-java-rest/pom.xml -Dtest=SwarJsonWriteTest,SwarJsonCodegenTest,JsonBufferWriterTest,ReactorCodegenProcessorsTest test
mvn -B -ntp -f java-rust-cache/pom.xml -Dtest=SwarJsonWriteTest,Utf8JsonWriterTest,Utf8JsonBufferResetTest test
```

These tests cover generated writers, byte equality, both build arms, invalid options,
all fused lengths and truncation points, alignment, byte order, read-only buffers,
integer overflow, Unicode, nested/null values, bounded growth and capacity retry.
These unit tests are separate from load acceptance. Our EmaTech real-HTTP matrix
passed all six control/literal cells: useful RPS stayed within -2%, p99 within
+10%, and steady RSS/container delta within +3 MiB. Literal-route RPS ranged
from -0.48% to +1.59%; its steady RSS increased by 0.54–1.38 MiB. This is bounded
overhead, not a demonstrated memory reduction. Measure your own DTOs before
enabling the option. Combined product/platform release validation is separate.

# Experimental Distribution Evidence

[English](README.md) | [Türkçe](README.tr.md)

Verified on 2026-10-21. This is scoped toolchain and runtime evidence, not release
approval. No binaries were copied into default resources. No Rust source, Cargo
configuration, core HTTP source, or default Maven dependency was changed by these
checks. Exact hashes are in [verification.json](verification.json).

## Current All-Feature Attempt

**26 October update:** Linux all-feature `release-ffm` was rebuilt from current
source. JNI slab/abort SHA256 `0c68e2e417e3be7949904f783e9db4948f120ab06c6851e132ee5c7c1120f8b5`;
separate FFM/unwind SHA256 `9d6f7e5a648fb7519c22d74ea6e527c35357cc4dda8526eb9ea09f54f9abff56`.
GLIBC maximum2.14,17 C exports+4 JNI slab exports and ABI29/7/6/6 passed. EmaTech
executed184 Rust,8 OpenJ9 activation/cache and18 HotSpot tests. OpenJ9 Java21 FFM
scope retention was reproduced by load and confirmed with PHD/DTFJ and actual VM
bytecode. FFM is now blocked on this VM before linker creation. The explicit JNI
shared slab and HotSpot FFM each passed sustained150000-read checks. OpenJ9 HTTP
acceptance compares JNI vs JNI slab, not the rejected foreign linker.
Windows and combined default resource publication were not done. The paragraph
below is the retained historical 21 October attempt, not today's Linux status.

The later current-source attempt is **INCOMPLETE**, not a new PASS. Its 51-file
source digest is `ff98fa6c354684e40f06ea79c9ef337fc23ac2579df189970a1e936a73cfdcbd`.
It includes default plus `ffm-fast,edge-cache,outbound-http`, profile `release-ffm`.
Windows reached the native link and failed (Cargo 101): the configured cached SDK
library search path lacked bcrypt, advapi32, kernel32, ntdll, userenv, ws2_32 and
dbghelp import libraries. An unpacked DbgHelp.Lib exists in the cache, but no
toolchain repair or source change was attempted. Linux compilation was explicitly
stopped at the ten-minute task budget (137, not a reported OOM/product assertion).
No current binary SHA, ABI/exports/GLIBC gate, Java/strict tests, Redis probe or
combined Rust unit-test PASS is claimed. Cache copies of old binaries are stale.
The manifest's primary inputs describe this attempt; `priorSnapshot*` fields and
the sections below describe ONLY the earlier successful snapshot.

Run: `.tmp/ffm-current-boole-20261021-v2`. External evidence is in the distribution
ledger's `current-all-features` subdirectory. Native sources and default packaged
artifacts were not edited. No load ran; owned build jobs were stopped.

## Frozen Inputs

Both binaries use the same 49-file dirty source snapshot: Git
`ccf11143cea8b7513706c439c67939b7803469d0-dirty`. The separate source-tree SHA256 is
`54984470ca47f1ee3e18b575b0f33eb7f71098af211281d0a4e6244cc5be84d2`.
This snapshot PRECEDES the edge-cache additions and JNI worker daemon-attach fix.
It is not a build or verification of the later source tree.

Cargo retained default features and added `ffm-fast`: websocket, dubbo, redis,
glowroot. The actual private Redis/Glowroot implementation was compiled, not a
standalone stub. Both commands ran offline/locked, with `CARGO_BUILD_JOBS=2` and
`REACTOR_NATIVE_SOURCE_REVISION` set to the dirty Git revision above.

```text
# Linux, cached manylinux2014 builder, Rust 1.91.0
cargo build --offline --locked --profile release-ffm --features ffm-fast
# Windows, Rust 1.91.0, cargo-xwin 0.23.0, cached LLD 21.1.2
cargo-xwin xwin build --offline --locked --profile release-ffm --features ffm-fast --target x86_64-pc-windows-msvc --target-dir <private-target>
```

Linux used `java-rust-dubbo-glibc217-builder:local`, read-only snapshot input and a
private output target. Container limits: 2 CPUs, 2 GiB, 128 PIDs, no network.
Windows used `C:/Users/generic/AppData/Local/cargo-xwin`, without Visual Studio or
dependency installation. Its build job had a 2 GiB aggregate limit and a two-CPU
quota. The builds completed in 8m21s and 12m11s respectively, not benchmark times.

## Results

- Linux ELF x86-64: maximum required GLIBC **2.14**, dependencies resolved in the
  GLIBC 2.17 builder. `verify-linux.py` rejects missing exports/dependencies,
  unsupported architecture, nonnumeric GLIBC tags, and requirements above 2.17.
  Negative control: the old GLIBC 2.34 SO was rejected.
- Both actual binaries: REST/Dubbo/Redis/Glowroot ABI **29/7/6/6**, all **16** FFM C
  exports, **14/14** live Java tests, **zero** skips/failures/aborts.
- Both allocation gates: eight fine controls each measured **4800 B**; retained
  large positive control **144000 B**; **100000** warmed slab/copy cycles **384 B**
  total. This is not a zero-allocation claim or a Redis allocation measurement.
- Both real Redis 7.4 standalone probes: existing registered client, UTF-8 hit,
  empty hit, miss, explicit lease release and client close, natural JVM exit.
  Redis and its disposable network were removed afterwards.
- Linux used cached Temurin HotSpot 21.0.12+8 JRE; Windows and javac used GraalVM
  HotSpot 21.0.6+8.1 JDK. JVMs: `-Xms32m -Xmx384m -XX:ActiveProcessorCount=2`,
  `--enable-preview --enable-native-access=ALL-UNNAMED`. No `System.gc`.

Additional strict leaf gate on the SAME binaries: `FfmStrictZeroLeafTest` passed
on both platforms, one focused test each with no skip/abort/failure. Each of native
metadata (three getters per iteration), held-lease slab read, and native disabled
recording used exactly 200000 warmup iterations, then three consecutive 100000
iteration batches. Every leaf reported `[0, 0, 0]` B. Eight fine controls before
and eight after each run all reported 4800 B. No run or window selection/retry.
The recording path enabled the Java GLOWROOT capability and used valid slot zero,
but never configured native telemetry in its fresh process. It therefore tested
the native disabled-engine path, not a Java capability/negative-slot shortcut.
Only metadata getters used `isTrivial`; read and recording used ordinary downcalls.
This is literal zero Java-thread heap allocation for these leaves, not native-heap
or RSS evidence. Full lease/copy allocation remains 384 B, not zero.

Strict logs/scripts are in the ledger's `strict-zero` subdirectory and workspace
`.tmp/ffm-strict-zero-boole-20261021`. No native build or production source change
was needed. The original 14-test runs and AppCDS jar/archive remain untouched.

## Linux AppCDS

The cached HotSpot JRE supports actual JVM archive creation; a Linux javac/jar
installation was unnecessary. The probe jar was compiled with the existing full
Windows JDK using `--enable-preview --release 21 -proc:none`.

```text
java <bounded-preview-flags> -XX:ArchiveClassesAtExit=/proof/appcds/app.jsa -cp /proof/ffm-probes.jar com.reactor.rust.ffm.FfmAppCdsProbe
java <bounded-preview-flags> -Xshare:on -XX:SharedArchiveFile=/proof/appcds/app.jsa -Xlog:cds=info,class+load=info -cp /proof/ffm-probes.jar com.reactor.rust.ffm.FfmAppCdsProbe
```

Separate Linux containers used identical jar/native mounts and JVM options.
Training produced a 2699264-byte archive with 439 classes, including six app
classes. The fresh load log identifies `FfmAppCdsProbe` and `FfmFastBridge` as
`source: shared objects file (top)`. Both runs performed real slab copy/read/release
and printed `APP_CDS_FFM_PROBE=OK`. Missing-archive `-Xshare:on` failed as expected.
This proves JVM mechanics, not Maven plugin integration, OpenJ9 SCC, startup/RSS
improvement, or portability of the archive to another JDK/classpath/architecture.

## Evidence And Limits

Workspace run: `.tmp/ffm-dist-boole-20261021-v1`. External ledger:
`C:/Users/generic/AppData/Local/Temp/reactor-zero-materialization-testagent-20261021/phase4-ffm-distribution`.
The ledger preserves exact scripts, input manifests, hashes, successful logs and
infrastructure failures. Primary logs: `linux-build-native-cache.log`,
`windows-build.log`, `linux-compat.log`, `linux-live-14.log`, `windows-live-14.log`,
`linux-abis.log`, `windows-abis.log`, `linux-redis.log`, `windows-redis.log`,
`appcds-create.log`, `appcds-load.log`, `appcds-missing-negative.log`.

Early Linux attempts hit offline registry availability and Windows bind-mount
extraction overhead; a private Linux registry volume resolved them. The first
Redis harness could not publish a host port on its internal network; switching
only that disposable network to a normal bridge allowed the Windows probe.
These are recorded infrastructure failures, not product assertion failures.

Outstanding: current-source rebuild/qualification, whole-process RSS <=+3 MiB,
paired JNI/FFM A/B, live OpenJ9 FFM, Cluster/Sentinel failover and release packaging.
The noisy slab timing samples are not acceptance evidence or an RPS claim.

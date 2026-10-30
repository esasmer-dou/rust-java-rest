# Optional HotSpot AppCDS Goal

[English](README-appcds.md) | [Turkish](README-appcds.tr.md)

Published in `rust-java-maven-plugin:4.8.0`; disabled unless explicitly enabled.
Real target-image archive generation/loading remains a separate acceptance step.

`reactor:generate-appcds` is an opt-in `package`-phase goal with runtime dependency
resolution. `reactor.appcds.enabled` defaults to `false`. The disabled execute method
returns before inspecting JVM metadata, MavenProject, files or processes. Maven itself
can still inject configuration and resolve dependencies before invoking the goal.

## JVM Boundary

This implementation supports the **HotSpot/OpenJDK Server VM on Java 21**, using the
same JDK that runs Maven. OpenJ9/Semeru is explicitly rejected when enabled: its SCC
is not a HotSpot `.jsa` archive. Use the existing `runtime-image` goal with
`reactor.runtime.openj9.scc.enabled=true` and the
[startup tuning](../../docs/startup-tuning.md#openj9-shared-classes-cache--aot) /
[production SCC](../../docs/production-runtime.md#rom-only-openj9-shared-cache) recipes.
No OpenJ9 options or recipes are changed by this goal.

## Runnable Minimal Example

Use a full **HotSpot JDK 21** running both Maven and `java`,
Maven 3.9+, authenticated access to the released 4.8.0 artifacts, and port 8080 free.
From `rust-java-rest`, in Bash:

```sh
mvn -B -ntp -f examples/pom.xml -pl minimal-rest -Pappcds clean package \
  -DskipTests -Dreactor.appcds.enabled=true
java examples/tools/AppCdsRun.java \
  examples/minimal-rest/target/reactor-appcds/compatible-jvm.properties \
  com.reactor.examples.minimal.MinimalApplication
```

From a second terminal: `curl --fail --max-time 5 http://localhost:8080/app/health`.
Stop with Ctrl+C before another run. The opt-in profile invokes this existing
plugin's `generate-appcds` goal after JAR packaging. Its checked-in
[offline training main](../../examples/minimal-rest/src/main/java/com/reactor/examples/minimal/OfflineArchiveTraining.java)
accepts only `--offline` and loads three real classes without initialization.
It starts no HTTP/native engine, DB, Redis, workers or business calls. It is a
small runnable baseline, **not** representative coverage of your application's
generated routes/writers. Extend your own finite harness for those classes.

The JDK-only [launcher](../../examples/tools/AppCdsRun.java) reads the plugin's
UTF-8 properties, checks the training JDK/platform and archive/JAR SHA-256 values,
replays recorded VM arguments and the exact **ordered JAR classpath**, then starts
the selected main with `-Xshare:on` and CDS logs. It does not use `target/classes`,
`lib/*`, Maven `exec:java`, a relocated JAR, or an application-manifest guess.
Only use trusted metadata. Keep the JDK and Maven-repository JARs at their training
paths; even an identical copied/touched JAR can invalidate HotSpot's path/mtime
checks. This is a same-layout development launcher, not a portable deployment image.
Source-file compilation, hashing and its parent JVM add overhead; do not use this
launcher as a production or startup-performance benchmark entrypoint.

Inspect `training.log`, `validation.log`, `app-cds.jsa`, `app-cds.classlist` and
`compatible-jvm.properties` under `examples/minimal-rest/target/reactor-appcds`.
Metadata still says `deploymentVerified=false`: archive generation/validation is
not target-image or RSS/RPS/p99 acceptance. Without the profile and enabled
property the ordinary example build/startup remains unchanged.

| Failure | Action |
| --- | --- |
| Maven uses OpenJ9 / no default `classes.jsa` | Select full HotSpot 21 with its default CDS archive; do not substitute an SCC file. |
| Missing plugin/JAR | Check Maven repository credentials; install changed matching source artifacts if needed. |
| Training timeout/nonzero exit | Read `training.log`; fix finite offline training, not a retry loop or service startup. |
| Hash/JDK/classpath mismatch or `-Xshare:on` failure | Retrain in the final unchanged layout/JDK; never silently change to `-Xshare:auto` and call it PASS. |
| Native startup/port error | Check matching 4.8.0 native artifact and free port; archive validity is not HTTP health. |

Launcher-only checks (JDK 21; no server/archive creation):

```sh
mkdir -p examples/minimal-rest/target/helper-checks
javac -d examples/minimal-rest/target/helper-checks \
  examples/tools/AppCdsRun.java examples/tools/AppCdsRunChecks.java
java -cp examples/minimal-rest/target/helper-checks AppCdsRunChecks
```

On HotSpot 21 these check valid commands, ordered classpaths and hash/mismatch
rejection. On OpenJ9 or another unsupported VM they assert the exact VM rejection
and print **rejection-only** success: full command/hash checks are not run.
Neither result validates a real archive or image. To compile the profile without
training, run `mvn -B -ntp -f examples/pom.xml -pl minimal-rest -Pappcds clean package -DskipTests`;
leave `reactor.appcds.enabled` at its default `false` (also valid on OpenJ9).

### OpenJ9 SCC Alternative (Not AppCDS)

Use a full Semeru/OpenJ9 JDK 21 instead. From `rust-java-rest`:

```sh
mvn -B -ntp -f sample/pom.xml clean package -DskipTests
SCC_DIR=$(mktemp -d)
SAMPLE_JAR="$PWD/sample/target/rust-java-rest-4.8.0-sample.jar"
java "-Xshareclasses:name=rest_demo,cacheDir=$SCC_DIR,noaot" -Xscmx8m \
  -cp "$SAMPLE_JAR" com.reactor.rust.startup.OpenJ9SharedClassCachePreloader \
  "$SAMPLE_JAR" com.reactor.rust
java "-Xshareclasses:name=rest_demo,cacheDir=$SCC_DIR,readonly,fatal" -Xscmx8m \
  -jar "$SAMPLE_JAR"
```

The finite preloader loads class metadata, reports loaded/skipped counts, and
does not initialize the application. It is not a zero-allocation/business warmup.
Check `/app/health` in a second terminal; stop the server before removing the
disposable SCC directory. A missing/incompatible/read-protected cache must fail,
not silently regenerate on a read-only production mount. Keep matching JDK, UID
permissions and immutable application JARs; qualify the resulting image separately.

For a **jlink/SCC image recipe**, the existing plugin can generate one from this
self-contained shaded compatibility JAR (the minimal example's thin JAR is not sufficient):

```sh
mvn -B -ntp -f sample/pom.xml package -DskipTests \
  com.reactor:rust-java-maven-plugin:4.8.0:runtime-image \
  -Dreactor.runtime.mainClass=com.reactor.rust.example.ReactorRustHyperApplication \
  -Dreactor.runtime.openj9.scc.enabled=true \
  -Dreactor.runtime.openj9.scc.class-prefixes=com.reactor.rust \
  -Dreactor.runtime.modules=java.se,jdk.crypto.ec,jdk.unsupported
```

This writes `sample/target/reactor-runtime/Dockerfile` and `jlink-modules.txt`;
it **does not** run jlink, build a container, or verify a deployment. The explicit
`java.se` module set is conservative for this large sample, not a minimal-service
footprint recommendation. Inspect the recipe/modules, pin approved base-image
digests, then build on the target architecture from the `sample` context using
`docker build -f sample/target/reactor-runtime/Dockerfile -t rest-scc-demo:local sample`.
The recipe creates SCC at image build time and opens it `readonly,fatal` as UID
10001. Verify health, archive use, native extraction, resource budgets and startup
errors in that target image before acceptance. OpenJ9 never consumes HotSpot `.jsa`.

## Strategy Details

The goal remains **disabled by default**. When explicitly enabled,
`reactor.appcds.strategy=dynamic` is now the default strategy: finite training uses
`ArchiveClassesAtExit` **on top of the existing JDK default CDS archive**, rather
than replacing its class coverage. The base must be the regular, nonempty
`java.home/lib/server/classes.jsa` (`bin/server/classes.jsa` on Windows).
Missing/invalid bases fail closed; there is no automatic base generation, static
fallback or training retry. Explicit `reactor.appcds.strategy=static` retains the
older two-step class-list/dump implementation for deliberate use.

## Explicit Offline Training

Configure a dedicated, finite offline harness from the packaged project JAR or its
resolved runtime JARs. The main and **1..64 individual argument elements** are required.
No manifest main, application defaults, shell parsing, `@argfiles` or automatic service
startup is used. Arguments preserve spaces literally; do not add shell quote characters.

```xml
<plugin>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-maven-plugin</artifactId>
  <version>4.8.1</version>
  <executions>
    <execution>
      <id>optional-appcds</id>
      <goals><goal>generate-appcds</goal></goals>
    </execution>
  </executions>
  <configuration>
    <strategy>dynamic</strategy>
    <trainingMainClass>example.OfflineArchiveTraining</trainingMainClass>
    <trainingArgs>
      <arg>--offline</arg>
    </trainingArgs>
    <timeoutSeconds>60</timeoutSeconds>
    <maxHeapMb>256</maxHeapMb>
  </configuration>
</plugin>
```

Enable only in an explicitly selected HotSpot archive build with the quoted property
`"-Dreactor.appcds.enabled=true"`. The goal requires an already packaged project JAR,
not `target/classes`. The project JAR is first; runtime JARs follow in Maven's resolved
order with duplicates removed. Classpath directories, missing JARs and wildcards are
rejected. Nested executable-JAR loaders and module-path training are not supported.

The harness must implement its own offline mode, avoid all business side effects,
start no workers or child processes, and return normally. `--offline` above is an
application contract, **not a sandbox or a network firewall**. Do not provide live
credentials. Relative fixture paths resolve from the generated output directory.

## Commands And Outputs

### Train The Classes Your Routes Actually Use

A tiny training main can produce a valid archive that barely covers your service.
Archive creation alone does not establish a performance improvement. Include the
generated dispatch classes, JSON writers and DTOs used by your selected routes.
Do not start HTTP servers, JMX polling, database pools or Redis clients just to load
their classes. Those initialization paths add unrelated training state.

Put one binary class name per line in `src/main/resources/appcds-hot-classes.txt`:

```text
com.reactor.generated.ReactorApplicationDescriptor
com.reactor.generated.ReactorApplicationDescriptor__Dispatch
com.example.customer.CustomerResponse
com.example.customer.CustomerResponseJsonWriter
```

The names must exist in your packaged JAR classpath. Replace the example names
with your real classes. Do not include hidden lambda implementation names from logs.
This offline main loads metadata without running class initializers:

```java
package example;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class OfflineArchiveTraining {
    public static void main(String[] args) throws Exception {
        if (!Arrays.equals(args, new String[]{"--offline"}))
            throw new IllegalArgumentException("Use --offline");
        try (var input = OfflineArchiveTraining.class
                .getResourceAsStream("/appcds-hot-classes.txt")) {
            if (input == null) throw new IllegalArgumentException("Missing training class list");
            byte[] bytes = input.readNBytes(262_145);
            if (bytes.length > 262_144) throw new IllegalArgumentException("Class list exceeds 256 KiB");
            String[] names = new String(bytes, StandardCharsets.UTF_8).split("\\R");
            if (names.length > 4096) throw new IllegalArgumentException("Too many training classes");
            for (String name : names) {
                if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
                    throw new IllegalArgumentException("Invalid training class: " + name);
                Class.forName(name, false, OfflineArchiveTraining.class.getClassLoader());
            }
        }
    }
}
```

Use `<trainingMainClass>example.OfflineArchiveTraining</trainingMainClass>` and
`<trainingArgs><arg>--offline</arg></trainingArgs>` with this main.
Loading happens only in the explicitly selected build-time training process.
There is no runtime scanning or request-time reflection. Class-only training does
not exercise serialization or replace correctness tests. Check actual shared-class
loading, RPS, p99 and RSS with your payloads before deployment. OpenJ9 still uses SCC.

Two tokenized `ProcessBuilder` commands run sequentially, never through a shell.
Default **dynamic** strategy (the class list remains diagnostic):

```text
<java.home>/bin/java <bounded-vm-options> -Xshare:on -XX:SharedArchiveFile=<jdk-base> -XX:ArchiveClassesAtExit=<top> -XX:DumpLoadedClassList=<classlist> -cp <ordered-jars> <explicit-main> <explicit-args>
<java.home>/bin/java <bounded-vm-options> -Xshare:on -XX:SharedArchiveFile=<jdk-base>:<top> -XX:+VerifySharedSpaces -XX:+PrintSharedArchiveAndExit -cp <ordered-jars>
```

Use `;` instead of `:` between archives on Windows. The second JVM validates the
archive dictionaries and exits **without replaying the training main**. Success
requires the expected dynamic archive and training-main app-loader entry, not
just a zero exit with base-only CDS. Base SHA-256 is checked before/after, alongside
ordered JAR hashes. Compatibility metadata records `strategy`, `base.archive.path`,
`base.archive.sha256`, `base.archive.size`, `archive.validated` and
`runtime.sharedArchiveFile` (the exact base/top pair). `deploymentVerified=false`
still means target deployment has not been accepted by the build goal.

Explicit static mode instead runs `-Xshare:off -XX:DumpLoadedClassList=...` training
and then `-Xshare:dump -XX:SharedClassListFile=...`; that custom static archive
replaces the JDK base and can lose its broader precomputed class coverage.

Each child has `-Xms32m`, `-Xmx<maxHeapMb>m`, `ActiveProcessorCount=2`,
`ParallelGCThreads=1` and `ConcGCThreads=1`. Heap configuration is limited to
32..1024 MiB and the per-child timeout to 1..300 seconds (default 60); termination
adds at most two two-second waits. No retry is performed for side-effecting training.
Ambient Java option/agent variables and `CLASSPATH` are removed from child environments.
Stdin is closed. Stdout/stderr go directly to separate training/validation (or static dump) logs;
no process output is buffered in plugin heap. Provision log disk space: log files are
not size-capped during the bounded child runtime. Logs and the class list remain on
failure, but partial/stale archives and success metadata are removed.
Interruption and stdin failures also wait for owned-child termination before
releasing the lock. If forced termination cannot be confirmed, metadata is not
published and the generation lock is retained. A still-running writer's partial
archive may remain: inspect the owned child and outputs before removing the lock.

Outputs live under `${project.build.directory}/reactor-appcds/`:

- `app-cds.classlist` and `app-cds.jsa` are the two JVM outputs.
- `training.log` and `validation.log` are used for dynamic mode; `dump.log` is for
  static mode. Stale opposite-strategy logs are removed under the generation lock.
- `compatible-jvm.properties` records JVM build/vendor/home, OS/architecture, VM
  options, ordered JAR paths/SHA-256 hashes and the archive SHA-256, without training args.

A generation lock rejects concurrent writers to the same output directory. If the
build JVM is forcibly stopped, inspect the directory before removing a stale lock.
Repeating the goal replaces its own generated archive, including the read-only
attribute set by HotSpot on Windows. Input JAR permissions are not changed. An
archive still mapped by another JVM may remain locked; that JVM is never stopped
or forcibly unmapped by the plugin.
Timeout/interruption destroys **only the directly owned child**; it never enumerates
or kills unrelated processes. A misbehaving harness that forks descendants is unsupported.

## Deployment Gate

The archive is **not portable**. Reuse requires a compatible JVM build and OS/CPU
architecture, compatible VM settings, and the same unchanged ordered JAR classpath
(or an allowed runtime suffix). Metadata is evidence, not a deployment compatibility
checker. JAR hashes are checked before and after training/dump. Regenerate after JDK,
dependency or packaging changes; train within the intended image/layout. Use
`-Xshare:on -XX:SharedArchiveFile=<jdk-base>:<top>` for dynamic mode (one archive for
static mode) and class-load diagnostics in that target
image to verify loading. This goal does not alter deployment startup, attach an
archive to a release, or claim an RSS/startup/throughput improvement.

HotSpot 21 also checks archived JAR sizes and filesystem timestamps. Copying an
identical JAR with a new modification time can invalidate the top despite a
matching SHA-256. This includes any last-position offline fixture JAR. Preserve
the original timestamps or regenerate in the final layout; never bypass archive
validation to make a copied archive load.

Record allocator environment settings when comparing target-image memory. Match
them between default-CDS and layered-CDS arms, and separately verify that a changed
allocator recipe does not trade throughput/latency for memory. This goal sets no
allocator environment variables and does not change any runtime image defaults.

## EmaTech-Only Acceptance Harness

This section describes the project's EmaTech acceptance harness, not a restriction
on product use or your own tests. `mvn -f platform/maven-plugin/pom.xml -DskipTests test-compile`
compiles its dedicated test sources without executing them.
Dedicated test classes are never added to a plugin/application runtime artifact.
`AppCdsArchiveIntegrationTest` requires `REACTOR_TEST_CLUSTER=EmaTech` and the
matching `-Dreactor.appcds.integration.vm=hotspot|openj9`; skipped tests are not passes.

`AppCdsEmaTechHarness` calls the real goal with a real JAR-only `MavenProject`;
Maven need not be installed. Stage plugin classes/descriptor, dedicated test
classes, Maven `lib/*.jar`, and JUnit. Inside the parent's verified EmaTech pod:

```sh
export REACTOR_TEST_CLUSTER=EmaTech
CP='/work/test-lib/junit-platform-console-standalone-1.11.4.jar:/payload/main:/payload/tests:/payload/maven-lib/*'
# Optional framework proof; ALL entries must be explicit ordered absolute JARs.
# Application JAR first, then unchanged runtime dependencies, no wildcard/dirs.
export APPCDS_FRAMEWORK_JARS='/payload/reader-switch/lib/application.jar:/payload/reader-switch/lib/rust-java-rest.jar:...'
export APPCDS_FRAMEWORK_DESCRIPTOR=com.reactor.generated.ReactorApplicationDescriptor
java -Xms32m -Xmx128m -cp "$CP" com.reactor.rust.maven.AppCdsEmaTechHarness hotspot /evidence/hotspot-functional
java -Xms32m -Xmx128m -cp "$CP" com.reactor.rust.maven.AppCdsEmaTechHarness compare /evidence/hotspot-functional /evidence/http-comparison 3
# Separate actual OpenJ9 21 image (not a spoofed java.vm.name):
java -Xms32m -Xmx128m -cp "$CP" com.reactor.rust.maven.AppCdsEmaTechHarness openj9 /evidence/openj9-functional
```

Replace the example framework paths and `...` with the exact current runtime
JARs, or unset both framework variables for mechanics-only proof. Framework mode
exercises the real `JsonBufferWriter`, constructs the supplied generated
`ApplicationDescriptor` and checks only its package metadata. It never registers
components/handlers/extensions, starts the framework service or contacts Redis.
The dedicated fixture JAR is appended last; retain that exact ordered classpath
for archive use, including any subsequent parent-owned real-framework HTTP check.
The generated metadata fingerprints every JAR. Test-only reflection is not added
to a framework hot path. Validate the supplied descriptor's offline behavior first.

Output directories must be new. Final archive:
`/evidence/hotspot-functional/target/reactor-appcds/app-cds.jsa`.
`archive-load.log` must show the fixture application class with
`source: shared objects file (top)` under strict `-Xshare:on` in dynamic mode,
plus JDK classes from `shared objects file` in the base. Framework mode additionally
requires that evidence for `JsonBufferWriter` and the actual generated descriptor;
JDK-only sharing is insufficient. Corrupt archives/changed classpaths must fail
without silent fallback. Nonterminating training must time out and leave no
success metadata/archive/lock. Real OpenJ9 must reject before project/filesystem/
process access; the disabled goal must do no work.

Dynamic mode compares three alternating fresh-process pairs of default JDK CDS
and layered CDS. Static-mode historical experiments retain the additional `off`
control. Default CDS gains are not attributed to AppCDS. Both dynamic controls use
the same actual framework writer for their 512 checked HTTP JSON responses, JARs,
VM settings and sampling point. CSV plus raw child
`smaps_rollup`/`status` preserve process-start-to-ready latency, RSS, PSS, private
memory and heap usage. Class-load logging is only in functional verification, not
timed HTTP runs. OS page caches are not flushed. This is **fresh-JVM, warm-file-cache
fixture evidence**, even with framework classes exercised: it does not start the
Rust/native HTTP server and is not framework real-HTTP, cold-container, RPS/p99 or
production memory acceptance. The cgroup sampler also preserves before/steady
`memory.current` and raw `memory.stat`; those include the harness and charged file
cache, not just application RSS. No forced GC or application-budget increase is
used. For the original optional Phase7 acceptance, steady RSS/cgroup regression
allowance is +3 MiB; net physical-memory reduction is not mandatory. PSS/private
memory are diagnostics, not newly invented hard gates. Startup is reported
separately and must not be mislabeled as the request-p99 latency gate.
Throughput/p99 gates belong to parent-owned all-on
validation, not this startup/memory fixture.

The environment switch is a guard/parent attestation, not cluster identity proof.
The parent captures EmaTech context, pod UID, image ID, node, resource limits and
source/artifact hashes, and launches/collects evidence itself. Apply a pod deadline,
disk quota, bounded CPU/memory and network isolation; heap limits do not cap RSS.
No new training, benchmark, verifier, archive flag or retry runs by default.

Primary references: [JDK 21 AppCDS commands and classpath restrictions](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html#application-class-data-sharing),
[OpenJ9 shared classes cache](https://eclipse.dev/openj9/docs/shrc/).

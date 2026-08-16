# Startup Tuning Guide

This guide covers JVM startup, framework bootstrap, native loading, and first-request readiness for
Rust-Java REST applications running on Java 21/OpenJ9.

## Goal

Do not optimize only for "port opened quickly". Optimize for:

- process start time
- readiness time
- first request p99
- RSS after warm startup
- repeatability across cold and warm container starts

## Measure First

The framework exposes startup phases at:

```text
GET /diagnostics/startup
GET /metrics
```

Important phases:

| Phase | Meaning |
|-------|---------|
| `properties.load` | Loads `rust-spring.properties`. |
| `runtime.profile` | Applies profile overrides. |
| `di.scan` | Finds component classes. Use a component index to reduce this. |
| `di.start` | Creates beans, injects properties/dependencies, runs post-construct hooks. |
| `handlers.register` | Registers Java handlers in the handler registry. |
| `routes.scan_register` | Builds route metadata and registers routes with Rust. |
| `native.load` | Loads/extracts the Rust DLL/SO. |
| `native.configure` | Applies runtime limits to Rust. |
| `startup.prewarm` | Optional readiness prewarm. |
| `http.start` | Starts the Rust HTTP server. |

Local benchmark:

```powershell
benchmark/startup_benchmark.ps1 -Build -Profile fast-start -JvmPreset none
benchmark/startup_benchmark.ps1 -Profile fast-start -JvmPreset openj9-scc-aot
benchmark/startup_benchmark.ps1 -Profile ready-low-latency -JvmPreset openj9-scc-aot
benchmark/startup_benchmark.ps1 -Profile micro-rest -JvmPreset openj9-micro-rss
benchmark/startup_benchmark.ps1 -Profile micro-dubbo -JvmPreset openj9-idle-rss
benchmark/startup_benchmark.ps1 -Profile fast-start -JvmPreset openj9-scc-aot `
  -JavaOptsAppend '-Dreactor.startup.route-index.validate=true'
```

Local sample result, measured on this workspace:

| Mode | Ready Time | Meaning |
|------|-----------:|---------|
| `fast-start`, no JVM preset | 677 ms | Baseline JVM startup without OpenJ9 shared classes/AOT tuning. |
| `fast-start`, OpenJ9 SCC/AOT, first run | 1,880 ms | First run populates the shared cache, so it is intentionally slower. |
| `fast-start`, OpenJ9 SCC/AOT, warm cache | 366 ms | Warm shared cache path; this is the value to compare for repeated pod starts on a reused volume. |
| `fast-start`, OpenJ9 SCC/AOT, component index required, route index validated | 393 ms | Strict startup metadata gate enabled and fallback scan disabled. |

Treat these as environment-local numbers. Re-run the benchmark inside your own container image and
with the same CPU/memory limits you use in Kubernetes.

## OpenJ9 Shared Classes Cache + AOT

Use OpenJ9/Semeru with a writable shared cache directory:

```bash
-Xshareclasses:name=rust-java-rest-${APP_NAME}-${APP_VERSION},cacheDir=/opt/openj9-scc,nonfatal
-Xscmx64m
-Xscmaxaot32m
-Xtune:virtualized
```

Optional quick-start profile:

```bash
-Xquickstart
```

Use `-Xquickstart` only after measuring steady-state throughput. It can improve startup but may reduce
JIT quality for longer-running/high-throughput services.

The writable `64m` cache above is a startup experiment. It is not the low-anon production recipe.
For low anonymous memory, use the Maven `runtime-image` goal with an `8m` ROM-only cache. The build
uses `noaot`, and runtime uses `readonly,fatal`. A `4m` cache was measured at 100% full and rejected;
an AOT-bearing cache was also rejected after a direct-writer p99 regression. See
[Production Runtime](production-runtime.md#rom-only-openj9-shared-cache) for the copy-paste Maven
configuration and the c64/c256 gate results.

## OpenJ9 Micro-RSS Presets

For small Kubernetes pods, do not let the JVM size internal workers from a large host CPU count. Use
an explicit CPU view and small stacks:

```bash
-Xms8m
-Xmx48m
-Xss256k
-Xquickstart
-Xtune:virtualized
-Xshareclasses:none
-XX:ActiveProcessorCount=1
```

This is represented by `startup/openj9-micro-rss.options` and by the benchmark preset
`openj9-micro-rss`.

If Linux `anon` memory is still the limiting factor, test the JIT-cap variant:

```bash
-Xcodecachetotal8m
```

This is represented by `startup/openj9-micro-rss-jitcap.options`. It keeps JIT enabled, unlike
`-Xnojit`, but asks OpenJ9 to commit less JIT code-cache memory. Treat it as an A/B option, not the
default. It is suitable only when your repeat benchmark shows stable p99/RPS for the actual endpoint
mix.

Latest full local gate, `micro-rest`, c64/c256/c512, sample repeat `3`, minimal smaps repeat `3`,
showed the exact trade-off:

- Minimal production cgroup RSS improved from `57.358 MiB` to `51.406 MiB`.
- JIT code committed dropped from `22 MiB` to `10 MiB`.
- Total cgroup anon barely moved: `45.301 MiB` to `45.024 MiB`.
- The gate still failed because p99 regressed on several rows, including the legacy
  `dynamic-dto-json` object graph path at c256/c512.

Use this only when your service is not dominated by hot dynamic DTO graph creation, or after moving
those hot routes to `JsonProducerResponse` / direct writer and rerunning the gate.

If Linux `anon` is dominated by JVM/JIT/native thread surface rather than Java heap, test the
JIT-thread variant:

```bash
-XcompilationThreads1
```

This is represented by `startup/openj9-micro-rss-jitthreads1.options`. It keeps JIT enabled and
reduces OpenJ9 background compilation worker count. In the current minimal probe it reduced Linux
threads from the mid-20s to 17 and lowered the thread-stack budget from about `6 MiB` to `4.25 MiB`.
Treat it as an A/B option. It should pass your endpoint matrix before production because fewer
compiler workers can change warmup, p99, and long-running Java-heavy behavior.

For very low traffic services, where the service may receive only a small number of requests per day
and memory is more important than CPU throughput, there is a stricter option:

```bash
-Xnojit
```

This is represented by `startup/openj9-idle-rss.options` and by `openj9-idle-rss`. Do not use it as a
general production default. It lowers retained JVM/JIT footprint, but Java code runs without JIT
optimization, so p99 and CPU cost must be measured against your real endpoint mix.

## Framework Startup Profiles

| Profile | Use when | Behavior |
|---------|----------|----------|
| `fast-start` | You want the shortest bootstrap path. | Uses low-RSS limits, native extraction cache, no prewarm, no static file inlining. |
| `ready-low-latency` | You want the first request to be stable. | Uses low-RSS limits plus serializer/direct-writer prewarm. |
| `low-rss` | Memory is the main constraint. | Tight worker, pool, and native cache limits. |
| `balanced-dubbo` | External RPC/database paths need more concurrency. | More JNI/RPC headroom with higher RSS. |
| `throughput` | Dedicated high-throughput service. | More retained buffers and worker capacity. |

Example:

```properties
reactor.runtime.profile=fast-start
```

## Build-Time Component Index

To avoid classpath scanning, ship:

```text
META-INF/reactor/components.idx
```

Each non-comment line is a component class:

```text
com.example.app.handler.HealthHandler
com.example.app.service.OrderService
com.example.app.config.AppConfiguration
```

Properties:

```properties
reactor.startup.component-index.enabled=true
reactor.startup.component-index.required=false
reactor.startup.scan.fallback-enabled=true
```

Production gate option:

```properties
reactor.startup.component-index.required=true
reactor.startup.scan.fallback-enabled=false
```

Use the strict option only after your build reliably generates the index. Otherwise startup should
fall back to classpath scanning so production does not fail from a missing metadata file.

Generate indexes while Java sources compile. This is the preferred path:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.13.0</version>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>com.reactor</groupId>
        <artifactId>rust-java-rest</artifactId>
        <version>4.5.4</version>
        <classifier>codegen</classifier>
      </path>
    </annotationProcessorPaths>
    <compilerArgs>
      <arg>-Areactor.codegen.excludePackages=com.example.app.benchmark,com.example.app.alternative</arg>
      <arg>-Areactor.codegen.excludeClasses=com.example.app.handler.LegacyHandler</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

The `codegen` classifier registers its processors through build-only service metadata. Do not repeat
processor class names in every application POM. The production JAR excludes both processor classes
and their service metadata.

The processor writes deterministic component, route, and property indexes. It also creates an
application descriptor with direct constructors for public no-argument components. Other component
shapes use the existing DI registration path. Duplicate HTTP method/path pairs fail compilation.

Use `reactor.codegen.handlers` for handlers supplied by another source set. Use package and class
exclusions when one repository builds multiple runtime shapes. The annotation processor is the only
supported index generator, which keeps duplicate-route validation and generated factories in one
deterministic build step.

## Route Index Gate

Optional route index:

```text
META-INF/reactor/routes.idx
```

Format:

```text
GET /app/health com.example.app.handler.HealthHandler#health
POST /orders com.example.app.handler.OrderHandler#create
```

Properties:

```properties
reactor.startup.route-index.validate=true
reactor.startup.route-index.required=false
```

When validation is enabled, startup reports both:

- routes that are present in the index but missing from runtime registration
- routes that runtime registered but are missing from the index

The generated files are build artifacts. Do not maintain them by hand. Run
`ReactorStartupProcessor` during Java compilation, package the resulting files, and fail the build
or startup gate when the selected surface and runtime registration diverge.

With `reactor.startup.route-index.required=true`, either mismatch fails startup. This is a
production gate and visibility feature. It does not replace handler method registration yet.

## Native Extraction Cache

By default, native DLL/SO extraction uses a deterministic cache:

```properties
reactor.native.extract.cache.enabled=true
reactor.native.extract.cache-dir=
```

Default location:

```text
${user.home}/.reactor/native/abi-19/{platform}/{sha256-prefix}/
```

This avoids extracting the same native binary to a new temporary file on every startup.

## Prewarm

Enable readiness prewarm when first-request latency matters more than minimum startup work:

```properties
reactor.startup.prewarm.enabled=true
reactor.startup.prewarm.json=true
```

Prewarm initializes handler descriptors, DSL-JSON writer state, direct JSON writer providers, and
response-type writer lookup. It does not create fake request DTOs or call user handlers.

## OpenJ9 CRIU / Semeru InstantOn Container Path

The framework includes an opt-in checkpoint hook and a Docker image flow for Semeru/OpenJ9 CRIU.
This path is Linux/container-only and is disabled by default.

Official boundary:

- OpenJ9 CRIU support must be enabled with `-XX:+EnableCRIUSupport`.
- The OpenJ9 CRIU API is exposed through the `openj9.criu` module.
- Current OpenJ9 documentation positions CRIU support for Semeru container images on UBI 8/9.
- Checkpoint images must not contain cryptographic secrets or already-open external connections.

Do not checkpoint after sockets, DB pools, ZooKeeper clients, or RPC connections are open.

Safe checkpoint candidate:

```text
properties loaded
DI/component index ready
handlers and route metadata registered
native library loaded
no HTTP listener yet
no DB/RPC/ZK connection yet
```

Framework properties:

```properties
reactor.instanton.checkpoint.enabled=false
reactor.instanton.checkpoint.dir=/checkpoint
reactor.instanton.checkpoint.fail-on-unavailable=true
reactor.instanton.checkpoint.leave-running=false
reactor.instanton.checkpoint.shell-job=true
reactor.instanton.checkpoint.file-locks=true
reactor.instanton.checkpoint.auto-dedup=false
reactor.instanton.checkpoint.tcp-close=true
reactor.instanton.checkpoint.log-file=checkpoint.log
reactor.instanton.checkpoint.log-level=4
```

`reactor.instanton.checkpoint.log-file` must be a file name, not a path. The OpenJ9 CRIU API rejects
path values for this option.

In your application main, call the hook after route/native metadata is ready and before starting
the HTTP server:

```java
StartupPrewarmer.prewarmIfEnabled();
InstantOnCheckpoint.checkpointIfEnabled();
NativeBridge.startHttpServer(port);
```

The sample app already does this. If the property is disabled, the call is a no-op.

Build the sample InstantOn image on Docker Desktop:

```powershell
docker/instant-on/build-checkpoint-image.ps1
```

Linux/WSL equivalent:

```bash
bash docker/instant-on/build-checkpoint-image.sh
```

On this workspace, Docker Desktop could build and run the base image, but it could not create a CRIU
checkpoint because Docker Desktop's own `dockerd` process runs under seccomp. The working local path
was an Ubuntu WSL Docker Engine started inside the Ubuntu distribution:

```powershell
wsl -d Ubuntu -u root -- systemctl start docker
wsl -d Ubuntu -u root -- bash -lc `
  "cd /mnt/e/ReactorRepository/rust-spring-performance/rust-java-rest && bash docker/instant-on/build-checkpoint-image.sh"
```

Run the restored image:

```bash
docker run --rm --privileged --security-opt seccomp=unconfined -p 8080:8080 rust-java-rest-instanton:local
```

Troubleshooting:

- The build script runs `criu check --all` before attempting checkpoint creation.
- On some Docker Desktop + WSL2 hosts, CRIU can fail with `couldn't suspend seccomp:
  Operation not permitted` even with `--privileged` and `seccomp=unconfined`.
- The script treats that seccomp failure as a hard stop because checkpoint/restore will not work.
- Other `criu check --all` warnings, such as WSL device/inode mismatches, are logged but the script
  continues to the real checkpoint attempt.
- If Java is PID 1, CRIU can fail with `The criu itself is within dumped tree`; the provided
  scripts run the checkpoint container with `--init` to avoid that process-tree shape.
- OpenJ9/JVM startup may hold file locks; the framework enables `setFileLocks(true)` by default
  for checkpoint runs.
- If that preflight fails, the Java/framework code path is ready but the local Docker/WSL kernel
  cannot produce a checkpoint image. Validate on a CRIU-capable Linux/UBI host or CI runner.
- `-SkipCriuCheck` / `SKIP_CRIU_CHECK=true` forces the checkpoint attempt, but it should not be
  used to hide a failed production gate.

Production gate: do not ship this as the default image until restore behavior is measured under the
same Kubernetes CPU/memory limits, env vars, locale, secrets model, and mounted files that production
uses. Privileged restore is acceptable for local validation; production should move toward the
minimum required Linux capabilities after the restore flow is proven.

Local WSL Docker measurement from this workspace:

| Mode | Host Elapsed Avg | Framework Ready Avg | Restore-to-HTTP Avg | RSS |
|------|-----------------:|--------------------:|--------------------:|----:|
| Normal Semeru/OpenJ9 image | 1,655 ms | 604 ms | n/a | ~35 MiB |
| CRIU restored image | 1,039 ms | 951 ms carried from checkpoint timeline | 1 ms | ~36 MiB |

Interpretation: `ready_ms` remains the original checkpoint timeline for continuity. For restored
containers, use `ready_since_restore_ms` and external `docker run -> first successful HTTP probe`
as the operational startup metrics.

# Generated Dispatch

[English](generated-dispatch.md) | [Türkçe](generated-dispatch.tr.md)

First published in REST 4.8.0. Generated dispatch changes how eligible annotated
handlers are called, not their Java API, request contract or native ABI.

## Choose At Build Time

| Processor option | Behavior |
| --- | --- |
| Omitted or `-Areactor.codegen.dispatch=auto` | Default. Use switch dispatch where supported; otherwise warn and keep the existing generated direct-call adapter. |
| `-Areactor.codegen.dispatch=switch` | Strict. Unsupported methods or table overflow fail compilation. |
| `-Areactor.codegen.dispatch=legacy` | Keep the previous generated route adapters and exception-handler lambdas. |

Other values fail compilation. Selection happens during annotation processing,
not on each request. Changing a JVM property at startup does not change already
compiled classes. A clean rebuild is required when changing modes.

## Compatibility And Bounds

Switch dispatch requires a registered component or `@Bean` owner: a public,
concrete, non-generic class with public, non-static, non-generic methods and
publicly accessible parameter types, including enclosing types and generic
arguments. Ordinary parameters need exactly one supported request binding.

In `auto`, package-private owners, methods, parameter types and exception handlers
keep their existing package-local generated factories. This includes
package-private `@Bean` return types. Registration, conditions and lifecycle stay
where they are; eligible public methods can still use switch in the same app.
Unregistered owners and previously invalid signatures do not gain new support.
If warnings are treated as errors, choose `legacy` explicitly where needed.

Routes support arities 0..8, with at most 256 switch entries per arity and a
separate limit of 256 exception-handler methods. IDs and ordering are deterministic.
`auto` keeps overflow entries on generated legacy calls; strict `switch` rejects
them. `legacy` does not remove the existing eight-parameter route limit. These
are generated-table limits, not connection or concurrency limits.

Each generated `RouteN` adapter contains its switch and direct handler call in
`invokeN` itself, not in a forwarding dispatcher. Specialized primitive
`invokeInt`, `invokeLong`, `invokeBoolean`, `invokeDouble` and `invokeShort` methods
also contain their own switches. Existing binding and direct-buffer/scalar paths
are unchanged, including `@DirectQueryInt` / `@DirectPathInt` runtime-direct
MethodHandle invocation. Generating an adapter does not reroute those calls.
Async handlers borrowing framework buffers remain unsupported.

## Maven Usage

The platform parent defaults `reactor.codegen.dispatch` to `auto` and forwards it
to the annotation processor. From your application's directory, choose a mode:

```sh
mvn -B -ntp -DskipTests -Dreactor.codegen.dispatch=auto clean package
mvn -B -ntp -DskipTests -Dreactor.codegen.dispatch=switch clean package
mvn -B -ntp -DskipTests -Dreactor.codegen.dispatch=legacy clean package
```

If the application or a profile adds compiler arguments, append instead of
replacing the parent's options. For example, inside `maven-compiler-plugin`:

```xml
<configuration>
  <compilerArgs combine.children="append">
    <arg>-parameters</arg>
  </compilerArgs>
</configuration>
```

Apply the same merge rule to each profile that defines `compilerArgs`. Without
the platform parent, pass `-Areactor.codegen.dispatch=...` through your existing
compiler configuration; a Maven property alone is not an annotation-processor
option. The compiler's `Generated dispatch mode=...` note reports switch/legacy
route and exception-handler counts. Generated sources show the actual calls.

## Runtime And Validation Scope

OpenJ9 runs the same precompiled Java adapters. The generated request path adds
no reflective invocation, dynamic proxies or runtime recompilation. Existing
startup inspection and runtime-direct MethodHandle paths are not removed.
Guards, async completion, status/headers, response ownership and lifecycle keep
their existing behavior. Java APIs and REST29 / Dubbo7 / SpringDubbo3 / Redis6 /
Glowroot6 remain unchanged.

### Measured Reader Scope

On 2026-10-27, source `phase79-dc6e245883e69cefe9e6` passed the declared
EmaTech/OpenJ9 reader matrix: five fresh process pairs, six small/raw/heavy cells
at c64/c256, 19,816,245 exact-body validated warm/measured responses and 100
separate HTTP contract checks, with zero errors. The real cache-reader detail,
segment and generated health routes were measured; customer-number binding and
the expected missing-customer 404 were also checked. HTTP used pod loopback,
not public ingress. All five pairs, including the original failing cells, were
retained after adding the missing relative-order coverage.

Both arms used 1 CPU / 128 MiB, affinity `0,3`, L1 disabled, seeded Redis without
a database, and 15-second warm / 8-second measurement windows per arm/cell.
**Benchmark-only recipe:** admission queue timeout was 125 ms for both
`cache-read` and `cache-read-point`, with unchanged concurrency caps 64/128.
Production defaults were not changed; this is not acceptance of the default
25 ms recipe. Acceptance used per-cell medians across all five paired results:
RPS >= 0.98, p99 <= 1.10, RSS/container delta <= +3 MiB and zero errors.

| Cell | RPS ratio | p99 ratio | RSS delta MiB | Container delta MiB |
| --- | ---: | ---: | ---: | ---: |
| small c64 | 1.026040 | 1.027248 | -0.795 | -0.369 |
| small c256 | 1.036327 | 1.029094 | -0.801 | -0.428 |
| raw c64 | 0.998793 | 0.992910 | -0.598 | -0.223 |
| raw c256 | 0.991837 | 1.003699 | -0.703 | -0.277 |
| heavy c64 | 1.025231 | 0.991344 | -2.230 | -2.086 |
| heavy c256 | 0.989412 | 1.021405 | -0.488 | -0.910 |

A separate same-JVM HotSpot primitive-invoker allocation check measured 0 bytes
for each mode over 100,000 calls after a positive 532,480-byte counter calibration.
The OpenJ9 allocation-method abort is not counted as a pass; this does not prove
that complete HTTP requests allocate nothing. Earlier FAIL receipts remain
preserved. These bounded results do not establish universal speedup, consumer
performance, public-network behavior, long-soak stability, other platforms or
full production/release qualification. This measurement predates publication;
shipping the feature in 4.8.0 does not turn it into full production or user acceptance.

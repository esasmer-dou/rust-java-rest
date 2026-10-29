# Bounded Outbound HTTP And Circuit Breakers

[English](README.md) | [Turkish](README.tr.md)

Circuit breakers and the opt-in native outbound transport are published in 4.8.0.
The default transport remains `jdk`. The native artifact
is **HTTP/1-only: HTTPS is unsupported and rejected**, not silently downgraded.

## Protect A Slow Dependency

```java
package example;

import com.reactor.rust.annotations.PathVariable;
import com.reactor.rust.http.client.HttpCircuitBreaker;
import com.reactor.rust.http.client.HttpExchange;
import com.reactor.rust.http.client.ReactorHttpClient;
import java.util.concurrent.CompletionStage;

@ReactorHttpClient(baseUrlProperty = "app.stock.base-url")
public interface StockClient {
    @HttpExchange(path = "/stock/{id}", timeoutMs = 500, retries = 0, idempotent = true)
    @HttpCircuitBreaker(windowSize = 64, minimumCalls = 16,
            failureRateThreshold = 50, openDurationMs = 10_000, halfOpenMaxCalls = 2)
    CompletionStage<String> stock(@PathVariable("id") long id);
}
```

```properties
app.stock.base-url=http://stock-service:8080
```

The normal framework compiler generates the implementation. No dynamic proxy is
created. Here `String` contains the raw text response. Use your normal generated DTO
mapping when business code needs typed JSON values.

| Setting | Effect |
| --- | --- |
| `windowSize=64` | Remember the last 64 completed logical outcomes |
| `minimumCalls=16` | Do not open before 16 outcomes exist |
| `failureRateThreshold=50` | Open when at least half of that window failed |
| `openDurationMs=10000` | Reject calls without opening a socket during this period |
| `halfOpenMaxCalls=2` | Admit at most two recovery probes; both must succeed |
| `record5xxAsFailure=true` | Count final HTTP 5xx responses as dependency failures |

Transport/decode failures count as failures. An ordinary HTTP 4xx response is not a
transport failure. Returning `HttpClientResponse<T>` exposes its status, but a final
5xx still counts when configured. A method annotation replaces the entire interface
configuration, rather than inheriting individual attributes.

A decoder throwing `RuntimeException` or `Error` completes the result
exceptionally and records a failed outcome. It cannot leave the caller waiting
forever or retain a half-open recovery probe.

Native calls record their final breaker outcome inside the existing completion
boundary. They do not add a second observer future/continuation. Cancellation
and completion still compete for one outcome; a late reply cannot undo a cancel.
Unannotated calls do not allocate the protected call's breaker bookkeeping.
Native cancellation, resource completion and breaker-outcome flags share one
embedded atomic state word. They do not allocate separate flag objects.

Protected native methods returning `CompletionStage<T>` or `CompletionStage<List<T>>`
complete directly with the decoded body. They do not build an intermediate
`HttpClientResponse` or a future/continuation just to extract its body. Status,
header limits, retries and error classification still apply. Return
`CompletionStage<HttpClientResponse<T>>` when your business logic needs status or
headers. The Java method signatures and annotations do not change.

Body-only protected calls also avoid materializing unused Java header strings,
maps and lists. Rust still validates HTTP headers and their limits; Java still
checks every encoded field length and the completion frame. The full response
API keeps all response headers. Body bytes still cross JNI; this is not a claim
of end-to-end zero-copy.

## Prepare Fixed Requests Once

For parameterless generated native methods, the client prepares the URI and fixed
headers once at startup. No extra application code or setting is needed:

```java
@HttpExchange(path = "/stock/summary", timeoutMs = 500, retries = 0)
@HttpCircuitBreaker
CompletionStage<byte[]> summary();
```

This is not a response cache. Every call reaches the stock service. Requests,
futures, circuit permissions and response bodies remain call-owned. Unprotected
parameterless native methods use the same preparation. Retained metadata is
bounded by generated method count, not by arbitrary request values.

Methods with path, query, header or body parameters keep the dynamic path. When an
`OutboundHeaderProvider` exists, headers are still built per call on the caller
thread. User identity and `traceparent` are never frozen at startup. Mutating a
prepared Request also uses dynamic preparation. Invalid fixed URIs or headers
fail native client initialization. The existing JDK sending path is preserved.

## Failure And Retry Rules

OPEN rejection uses one shared stackless `HttpCircuitOpenException`. The common REST
error mapper returns `503` without exposing dependency addresses. Rejected calls do
not open sockets. No queue, scheduler or maintenance thread is added by the breaker.

The breaker records **one final outcome**, after the existing retry policy finishes.
It does not add retries or make a write idempotent. The example deliberately uses
`retries=0`. Do not enable retries for a payment/order write without a real idempotency
contract. Cancelling a JDK result retains its existing timeout/drain behavior. Native
cancellation, including generated breaker wrappers, propagates to I/O; capacity is
not reclaimed before native acknowledgment and completion work actually finish.

Methods without the annotation retain the existing generated path: no breaker field
or check. Breaker state, outcome windows and probes have fixed bounds. Sample rejection
timings and allocation tests are not a container RPS/RSS guarantee.

Allocation acceptance includes repeated small retained-object controls. Coarse or
unsupported JVM counters skip the measurement rather than report a false zero.
The separate calibrated HotSpot Java 21 oracle does not change the production JVM.

## Trace Propagation

Outbound headers are built once on the caller thread. Retries reuse the same request
and traceparent, not another thread's context. The tracing starter also provides
`W3cTraceparent.write(context, target, offset)` for a caller-owned 55-byte buffer.
That writer does not allocate for valid IDs. Parsing still materializes the two IDs.
`TraceSpan.parentSpanId()` retains the incoming remote parent. These changes do not
turn JDK HTTP headers into a native zero-copy transport.

## Select The Transport At Startup

```properties
# Default; no native outbound queues, runtime, workers or adapter are created.
reactor.http-client.transport=jdk

# Opt-in alternative, requiring the outbound-http Cargo feature in the DLL/SO.
# reactor.http-client.transport=rust-native
```

Set an absolute `-Drust.lib.path=C:/native/rust_hyper.dll` or the corresponding SO
path when using a custom artifact. The existing `NativeLibraryLoader` is used;
missing additive JNI symbols fail startup with a build-feature diagnostic. There
is no implicit JDK fallback. REST ABI 29 is unchanged; the outbound API is version 1.
See [native build instructions](NATIVE-INTEGRATION.md) for source feature and artifact requirements.

Generated clients bind their base URL during construction. Native binding rejects
HTTPS, userinfo and excess retained origins before sending. Bind clients once at
startup, not per request. Native transport does not support verified TLS, HTTP/2,
proxies, redirects, cookie/authenticator automation, upgrades, or application-set
framing/hop-by-hop headers. Use JDK at startup when those features are required.
This is not full production HTTPS coverage.

Generated client factories respect `@RequiresProperty` and profile conditions
at startup. A disabled client is not constructed. There is no per-request property
lookup. See the [complete outbound sample](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/OUTBOUND.md).

## Native Resource Limits

All values below are native defaults. Explicit existing `reactor.http-client.*`
system properties, environment variables or application configuration take
precedence over native-specific defaults for threads, in-flight calls, body sizes
and header count. Bundled framework defaults are not mistaken for explicit values.

| Property suffix after `reactor.http-client.` | Native default |
| --- | --- |
| `threads` | 1 dedicated Tokio worker, not the server executor |
| `native.completion-threads` | 1 Java worker, 256 KiB requested stack, no inherited thread locals |
| `max-inflight` | 8 fixed generation-tagged logical slots |
| `max-request-bytes` / `max-response-bytes` | 16 KiB / 64 KiB |
| `max-headers` / `native.max-header-bytes` | 16 fields / 8 KiB each request or response head |
| `native.byte-budget-bytes` | 1 MiB; configurable maximum 3 MiB per runtime |
| `native.max-connections` | 4 total connecting, active and retained connections |
| `native.max-origins` / `native.max-idle-per-origin` | 8 retained origins / 1 idle connection per origin |
| `connect-timeout-ms` / `request-timeout-ms` | 1000 / 2000 ms, per attempt |
| `native.idle-timeout-ms` / `shutdown-timeout-ms` | 30000 / 5000 ms |

There are at most **8 live native runtimes per process**. A runtime reserves
`4 * (maxRequest + maxResponse + 4 * maxHeaderBytes + 8192)` bytes per logical call,
including retry waits and Java completion work. Each native connection also reserves
`2 * maxHeaderBytes + 16384` bytes. Default call reservation is 491520 bytes, so the
1 MiB budget admits at most two such calls, not eight. Raised body limits must fit
one reservation or startup fails; connection reservations can reject admission
earlier. Limits never create an unbounded wait queue.

Origin metadata and its fixed idle queue are also charged to the byte budget at
startup. Allow headroom for completion work when combining a route bulkhead with
the client: eight concurrent route operations and one completion worker can use
nine logical slots while keeping eight socket connections. A completed future may
wake the next route before its continuation returns; freeing its slot earlier
would hide still-live application work from the memory bound.

Hostname lookup admits at most one queued/running OS DNS job per runtime and at
most eight returned addresses. The job retains its resolver permit and
`maxHeaderBytes + 8192` byte reservation until it actually ends, even when the
request times out. IP literals bypass DNS. A busy resolver rejects admission;
it does not accumulate canceled jobs in Tokio's blocking queue.

These reservations bound transport buffers, not total JVM/native RSS, kernel socket
buffers, caller-owned request objects, transient allocations in the existing shared
DTO encoder, or application decoder/continuation allocations. Raw strings remain
raw UTF-8 and raw bytes are not JSON-encoded. A 3 MiB transport budget is not a
3 MiB total-RSS guarantee. Measure your payload, decoder and callbacks with your
chosen JVM and pod limits before setting a production memory budget.

## Contention, Cancellation And Shutdown

Java admission and completion use CAS counters and a fixed `AtomicReferenceArray`;
no per-request transport-mode check, pending map or Java request lock is added to
the JDK body path. Native call slots and cancellation generations are atomic.
The fixed eight-slot runtime registry uses generation-checked `ArcSwapOption`
entries. Each startup-bound origin owns a bounded `ArrayQueue` of exclusive idle
connections. Request lookup, idle checkout/return, cancellation and completion do
not acquire a registry or pool mutex. Stale handles cannot reach a replacement
runtime in the same slot. Startup registration and destruction still use lifecycle
locks. This is not a claim that all Tokio, Hyper, JVM or allocator internals are lock-free.

Completion metadata and body cross JNI separately. The body gets one owned Java
`byte[]`; it is not packed into another body-sized event array and copied again
by Java. Callers never share a worker body buffer. Each worker reuses one metadata
holder slot and clears its reference in the same iteration. Native reservations
remain live until the JNI copies finish; a failed JNI allocation also clears the
native slot. This is not full zero-copy: the Rust body is copied into Java once.
The old `poll` symbol and frame format remain available. This source set requires
a matching binary with split completion. Missing symbols fail at startup rather
than silently selecting JDK transport. REST ABI 29 is unchanged.

Tokio never calls Java. Bounded Java completion workers pull native results and run
decoding/completion there. Cancellation uses generation-tagged bounded slots;
I/O keeps its deadline while completion acknowledgment is pending.
On a healthy circuit, successful outcomes advance its logical window with one
CAS instead of rewriting zero ring slots. The exact window and generation rules
still apply after a failure. This needs no new setting, memory pool or timer.
Completion claims "finished" and the circuit outcome together in one CAS.
A cancellation that already claimed the outcome still wins; it is not counted twice.
Workers start parked. After activity, one bounded native wait absorbs short gaps;
an arriving response wakes it immediately. If no work arrives, the worker parks
indefinitely instead of polling JNI every 50 ms. This is not a fixed request delay.
Submission/close preserve the lost-wakeup barrier. No spin loop, timer or extra
thread is added. Call and byte reservations remain held until callbacks return.
Only completely drained responses enter the idle pool. A socket permit is released
when its driver really terminates, not merely when abort is requested.

The original once-only caller-thread header providers and idempotency/retry rules
remain. GET/PUT/DELETE retain their existing idempotency classification. POST is not
retried by default; enabling write retries requires a real idempotency contract.
No native library-level reconnect/replay occurs after a possibly delivered send.

Close stops new admission and cancels active/backoff I/O. It drains completion
workers up to the configured timeout and is safe from a completion worker itself.
Arbitrary blocked application code cannot be forcibly terminated: its runtime slot
stays charged until the worker really exits. Uncancellable OS DNS work is retained
by a bounded shutdown reaper and also keeps the process runtime slot charged.

## Verify The Integrated Artifact

From the `rust-java-rest` source checkout, use the actual matching native library:

```sh
mvn -f platform/starter-http-client/pom.xml -Dreactor.http-client.native-test-library=/absolute/path/librust_hyper.so test
```

Without that explicit artifact property, socket-level native tests are skipped, not
reported as native proof. Tests use actual JNI/loopback HTTP and fresh child JVMs
with real VM class-load logs. The JDK control must load/start its real client; the
native child must have exactly zero JDK HTTP client threads and no HttpClientImpl,
HttpClientFacade or SelectorManager class load. Bytecode checks require the genuine
pre-change 4.8.0 starter in the local Maven cache, never a transport stub.

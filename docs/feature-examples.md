# 4.8.0 Feature And Runnable Consumer Map

[English](feature-examples.md) | [Türkçe](feature-examples.tr.md)

`v4.8.0` publishes REST, platform, plugin and optional Java modules together.
Publication is not workload acceptance. Preserve the [release limitations](release-notes/v4.8.0.md#qualification-scope):
no universal RSS reduction, fixed writer p99, zero consumer warmup rejection, or
all-platform functional/performance PASS is implied. Ordinary JNI is panic-abort;
the separate HotSpot FFM build is opt-in and uses its own feature/profile.

## Which Application?

The five [core examples](../examples/README.md) are small explicit-module HTTP
lessons. The core [compatibility sample](../sample/README.md) is a larger diagnostics
app. Neither is one of these four independently built sibling applications:

| Application | Actual role / prerequisites |
| --- | --- |
| [cache writer](https://github.com/esasmer-dou/rest-sample-cache-writer/blob/master/README.md) | PostgreSQL to versioned Redis projection; DB/Redis configuration and schema required. |
| [cache reader](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/README.md) | Generated projection reader; requires a writer-published Redis snapshot for the normal routes. Optional profiles below are different applications. |
| [Dubbo provider](https://github.com/esasmer-dou/rest-sample-dubbo-provider/blob/master/README.md) | Java business service; DB-backed mode needs PostgreSQL. Static catalog modes do not. |
| [Dubbo consumer](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/README.md) | HTTP to Dubbo; needs the matching provider, shared 0.5.0 contracts and aligned native/Java libraries. |

Cross-repository links point to GitHub. Checkout-based commands assume the repositories
are side by side. Use JDK 21, Maven 3.9+ and authenticated GitHub Packages access
in your own development or deployment environment; no private cluster is required.
Build changed shared libraries
before dependent apps; a checkout is not an installed Maven artifact. Do not enable
multiple isolated reader profiles together or leave two examples on port 8080.

Optional sample profiles/flags describe this checkout, not a claim that those
source additions were already shipped in sample application 0.8.0. Build the
selected checkout profile; its unchanged POM version alone is not release evidence.

## Feature Selection And Read Costs

| Feature | Concrete source / runnable setup | Boundary and cost |
| --- | --- | --- |
| Generated app/modules | [reader application](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/src/main/java/com/reactor/sample/cache/reader/app/RestSampleCacheReaderApplication.java), [platform setup](../platform/README.md) | APT generates startup/dispatch, not a runtime classpath scan. Core examples use explicit modules; they are not declarative-app coverage. |
| Generated repositories / JSON | [platform generation guide](../platform/README.md), [shared model](https://github.com/esasmer-dou/rust-sample-model/blob/master/README.md), [provider repository](https://github.com/esasmer-dou/rest-sample-dubbo-provider/blob/master/src/main/java/com/reactor/sample/dubbo/provider/db/PostgresCustomerRepository.java) | Four sample apps use generated codecs/mappers, but that repository is handwritten JDBC, not proof of a generated repository interface. No standalone generated-repository app is supplied here. |
| Opt-in L1 / owned cache JSON | [generated reader declaration](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/src/main/java/com/reactor/sample/cache/reader/service/CustomerCacheService.java), [reader setup](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/README.md) | `allowL1=true` permits configuration; it does not prove L1 is enabled. Hits retain bounded native storage; version/freshness checks and misses still cost Redis work. Owned output avoids a Java payload array, not all copies or allocations. |
| HS256 / RSA / EC / Ed25519 / bounded JWT cache | [starter](../platform/starter-security/README.md), [runnable issuer and HTTP/error checks](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/JWT_SECURITY.md), [native HS256](../NATIVE_JWT.md) | Select reader `jwt-security`; no Redis/DB/RPC. JCA signatures on misses; cache off by default, entry/worker budgets explicit. Native edge HS256 is a separate opt-in; valid traffic still enters Java authorization. |
| W3C / retry-safe headers | [tracing starter](../platform/starter-tracing/README.md), [reader trace profile](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/TRACE_PROPAGATION.md) | `trace-propagation` is isolated; caller-buffer formatting is not response caching. Retry preserves protected headers; do not retry side-effecting writes without idempotency. |
| HTTP client / circuit breaker | [starter](../platform/starter-http-client/README.md), [reader outbound profile](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/OUTBOUND.md) | `outbound` is isolated; default JDK transport supports TLS. Native transport is HTTP/1-only, rejects HTTPS. Bound deadlines, connections, admission and completion headroom. |
| FFM / shared slab / OpenJ9 JNI | [Java adapter](../platform/ffm-cache/README.md), [runnable reader](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/SHARED_SLAB.md) | `shared-slab` reads raw Redis JSON, **one GET per request**, no L1. JNI slab on OpenJ9; FFM only on qualified HotSpot with preview/native-access flags and separate unwind binary. Four 64-KiB slots reserve 256 KiB; RESP framing consumes the wire budget. |
| Borrowed binary/native views | [runnable producer + reader](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/NATIVE_VIEW.md) | `native-view` needs no Redis/DB. `/view` validates borrowed bytes and writes JSON; `/dto` deliberately creates record/String objects. Borrow ends before synchronous handler return. Neither decodes arbitrary JSON/Hessian. |
| Generated direct Dubbo JSON / owned response | [consumer handler](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/src/direct-json/java/com/reactor/sample/dubbo/consumer/directjson/DirectCatalogHandler.java), [run/config guide](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/docs/DIRECT_JSON.md) | `direct-json-consumer` needs catalog provider; **one RPC per request**, not a response cache. Avoids consumer row DTOs, but retains a bounded native Hessian result and writes JSON bytes. Constructors/normalization on consumer DTOs do not run. |
| SWAR/prebaked literals | [literal handler](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/src/direct-json/java/com/reactor/sample/dubbo/consumer/directjson/SwarHandler.java), [build switch](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/docs/DIRECT_JSON.md) | Compile-time `-Dreactor.json.swar=true`, clean rebuild; not a runtime flag. Ordinary generated writers remain default. Buffer growth is not an RPC replay. |
| AppCDS / jlink / OpenJ9 SCC | [real minimal profile, training and startup](../platform/maven-plugin/README-appcds.md#runnable-minimal-example) | Core example only, not enabled in four sample apps. HotSpot 21 `.jsa` and OpenJ9 SCC are different mechanisms. `runtime-image` generates a recipe, not an accepted image; thin JARs are not self-contained images. |
| Lifecycle / async deadlines / backpressure | [runtime guide](../README.md), [consumer route budgets](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/src/main/java/com/reactor/sample/dubbo/consumer/handler/CustomerHandler.java) | Core manages native pending requests and late-result cleanup. HTTP timeout does not cancel business work; bound DB/RPC deadlines separately. Existing sample budgets are usage, not a new dedicated cancellation/slow-client acceptance suite. |

Optional profiles have separate source/resources/output directories. Select one,
clean-package it, start its documented main with matching runtime dependencies,
then run its success **and failure** requests. Adding an optional artifact to a POM
alone does not select its application, native capabilities, keys or external services.

## Shared Contracts To Real Processes

| Contract | Producer/provider | Consumer and run instructions |
| --- | --- | --- |
| [StreamCatalogRow](https://github.com/esasmer-dou/rust-sample-model/blob/master/src/main/java/com/reactor/rust/dubbo/sample/dto/StreamCatalogRow.java) / [StreamCatalogService](https://github.com/esasmer-dou/rest-sample-utility/blob/master/src/main/java/com/reactor/rust/dubbo/sample/StreamCatalogService.java) | [Java implementation](https://github.com/esasmer-dou/rest-sample-dubbo-provider/blob/master/src/main/java/com/reactor/sample/dubbo/provider/service/StreamCatalogServiceImpl.java), [catalog main](https://github.com/esasmer-dou/rest-sample-dubbo-provider/blob/master/src/main/java/com/reactor/sample/dubbo/provider/streamcatalog/StreamCatalogProviderApplication.java); see provider's `stream-catalog-provider` profile. | [DirectCatalogClient](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/src/direct-json/java/com/reactor/sample/dubbo/consumer/directjson/DirectCatalogClient.java), [direct-json run steps](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/docs/DIRECT_JSON.md); point its static address to the started provider. |
| [CustomerSegmentQuery](https://github.com/esasmer-dou/rust-sample-model/blob/master/src/main/java/com/reactor/rust/dubbo/sample/dto/CustomerSegmentQuery.java) / [CustomerQueryService](https://github.com/esasmer-dou/rest-sample-utility/blob/master/src/main/java/com/reactor/rust/dubbo/sample/CustomerQueryService.java) | [CustomerQueryServiceImpl](https://github.com/esasmer-dou/rest-sample-dubbo-provider/blob/master/src/main/java/com/reactor/sample/dubbo/provider/service/CustomerQueryServiceImpl.java); use provider DB setup, not static catalog-only mode. | [CustomerHandler](https://github.com/esasmer-dou/rest-sample-dubbo-consumer/blob/master/src/main/java/com/reactor/sample/dubbo/consumer/handler/CustomerHandler.java); follow normal provider/consumer README DB instructions. Typed results materialize; native JSON is a separate choice. |
| [AccountSnapshot](https://github.com/esasmer-dou/rust-sample-model/blob/master/src/main/java/com/reactor/sample/model/account/AccountSnapshot.java) | Generated binary producer in reader's [native-view recipe](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/NATIVE_VIEW.md). | Same profile's `/dto` comparison route; plain shared record is not a registered serializer or independent service. |

Model/utility are contract libraries, not runnable applications. Keep both at
0.5.0 on the two sides. Runtime Dubbo 0.9.0 is distinct from the independently
published Spring annotation artifact 0.8.0; see [utility dependency rationale](https://github.com/esasmer-dou/rest-sample-utility/blob/master/README.md).

# Public GET Edge Policies

For a protected HS256 route, use the separate [native JWT filter](NATIVE_JWT.md).
It rejects invalid tokens before JNI but keeps valid-request authorization in Java.
Do not use public response caching for that purpose.

This is an explicit acceleration path, not a replacement for Java business logic.
First published in REST 4.8.0, this path is off until policies are explicitly
installed before route registration. The packaged native binaries include
`edge-cache`; custom builds need `--features edge-cache`. Availability alone does
not allocate a route slab. See [platform verification limits](docs/native-availability.md),
including the published 4.8.0 ARM64 sidecar issue.

Use it for public catalog snapshots. Do not use it for authorization, tenant
selection, per-user output or a handler that must execute business side effects.
An edge hit bypasses **all** Java handlers and guards. `@Authenticated` cache
routes are rejected at compilation unless the method explicitly has `@PermitAll`.
Your own custom guards are your responsibility; explicit public consent is required.

```java
import com.reactor.rust.annotations.*;
import com.reactor.rust.cache.core.RustCache;
import com.reactor.rust.cache.http.CacheResponses;
import com.reactor.rust.cache.versioned.CacheFreshness;
import com.reactor.rust.cache.versioned.VersionedJsonCacheReader;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;

@RestController("/catalog")
public final class CatalogController {
    private final VersionedJsonCacheReader reader;
    public CatalogController(RustCache cache) {
        reader = VersionedJsonCacheReader.create(cache, "catalog", 1_000,
            new CacheFreshness(60_000, false));
    }
    @GetMapping("/{id}")
    @RustEdgeCache(versionKey = "catalog:current",
        dataKeyPrefix = "catalog:v:{version}:id:", pathParam = "id",
        maxAgeMs = 60_000, serveStale = false)
    @RustEdgeRateLimit(permitsPerSecond = 25_000, burst = 1_000)
    public ResponseEntity<RawResponse> byId(@PathVariable("id") long id) {
        return CacheResponses.problem(reader.getById(id));
    }
}
```

At startup, before registering HTTP routes, explicitly install the generated
companion. Keep the cache open until the HTTP server has stopped and drained:

```java
RestApplication.run(context -> {
    var cache = context.manage(RustCaches.create(context.properties()));
    cache.installPublicEdgePolicies(id -> CatalogController_EdgePolicies.install(id, true));
    context.handlers(new CatalogController(cache));
});
```

The startup snippet imports `com.reactor.rust.app.RestApplication` and
`com.reactor.rust.cache.core.RustCaches`. The managed cache closes after server
shutdown. Use these properties for the explicitly small read client:

```properties
reactor.cache.redis.host=127.0.0.1
reactor.cache.redis.port=6379
reactor.cache.redis.access-mode=read-only
reactor.cache.redis.read-connections=2
reactor.cache.redis.max-read-inflight=8
reactor.cache.redis.max-response-bytes=16384
```

Match Java fallback freshness to the annotation. Otherwise a miss could execute
a Java handler with a different age policy. The example deliberately uses the
same 60-second age limit and forbids stale data on both paths.

The Redis client must allow reads and have `max-response-bytes <= 16384`.
This prevents a large Redis reply from being allocated before the edge cache can
reject it. A normal larger client is deliberately rejected; create a small read
client for these routes. No automatic unsafe downgrade or JDK fallback occurs.

| Setting / rule | Behavior |
| --- | --- |
| Version format | Existing writer `fence\|timestamp-id` or plain `timestamp-id` |
| Key template | Exactly one `{version}`, then the URI-encoded path parameter |
| Version polling | At most 1 second of pointer visibility delay; no polling thread |
| L1 entry freshness | At most 1 second; Redis expiry/deletion becomes visible on the next fetch |
| Per-route slab | 32 slots, at most 8192 payload bytes each |
| Process cache cap | 2 MiB including conservative per-slot key/control accounting |
| Redis fetch admission | At most 8 edge fetches in the whole process |
| Slow HTTP consumer | Pins its slot; no overwrite while the response is in use |
| Query / credentials / body | Bypass the cache; execute Java normally |
| Missing data / invalid version metadata / stale snapshot / oversized value | Execute Java normally |
| Allowed stale hit | HTTP 200 plus `X-Cache-Stale: true` |
| Rate limit | Shared per route and per replica, not per user or cluster-wide |
| Rate-only policy | Supports GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS; does not cache commands |
| Rejection | HTTP 429 `application/problem+json`, no JNI handler callback |

The policy manifest is generated at `META-INF/reactor/edge-policies.tsv`.
Policies are immutable after route registration. Existing route constructors and
REST ABI 29 are unchanged. These correctness rules are not yet an RSS/RPS claim;
the release performance matrix must still validate each enabled recipe.

The published body must already be valid UTF-8 JSON. This path does not deserialize
or validate the JSON body; the Java publisher owns that contract. Do not point a
public policy at arbitrary or private Redis keys.

## Declarative Cache Startup

With published REST 4.8.0 and cache 0.9.0, manual cache wiring is optional:

```java
@EnableRustCache(publicEdgePolicies = PublicCatalogHandler.class,
    publicEdgeEnabledProperty = "sample.public.edge.enabled")
@ReactorApplication(scanBasePackages = "com.example.catalog")
public final class CatalogApplication {
    public static void main(String[] args) {
        RestApplication.run(CatalogApplication.class, args);
    }
}
```

This imports `com.reactor.rust.cache.integration.EnableRustCache`,
`com.reactor.rust.annotations.ReactorApplication` and
`com.reactor.rust.app.RestApplication`. The cache processor generates the direct
policy calls and closes the client on installation failure. The switch is read
once at startup and defaults to false when absent. The ordinary `@EnableRustCache`
output stays unchanged when no policy classes are listed.

Only explicitly public, uniform data may bypass Java. These options do not
implement native JWT validation. Private routes keep their existing security
guards. The optional `public-edge` build of `rest-sample-cache-reader` demonstrates
separate public caching and rate-limit endpoints without publishing customer data.
The sample 0.8.0 POM includes this profile, but its optional application is not in
the default sample JAR. Rebuild the selected source profile; its enable property
remains off by default.

For cache-backed string path parameters, native hits do not build Java path
strings. The original parameter is materialized only when falling back to Java;
primitive binding validation is unchanged. Cached payloads are shared without
copying and cannot be overwritten while a response still references them.
The response-byte budget stays reserved until the last byte reference is released.

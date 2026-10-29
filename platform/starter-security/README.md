# Explicit JWT Verification

[English](README.md) | [Turkish](README.tr.md)

The asymmetric verifier and token cache below are published in 4.8.0 and remain opt-in.
Focused EmaTech security, HTTP and paired-container
RSS/RPS/p99 checks passed for the bounded configuration shown in the sample. This
does not qualify every workload or all released framework features.

## Choose A Path

| Situation | Choice |
| --- | --- |
| Existing HS256 service | Keep the current properties; no cache is added automatically |
| Frequently repeated HS256 tokens | Explicit `VerifiedTokenCache.hmacFromProperties(...)` |
| Locally configured RSA, P-256 or Ed25519 keys | `AsymmetricJwtVerifier` |
| Repeated asymmetric tokens | Wrap that verifier with `withCache(...)` |

No network key lookup or refresh thread is started. Every key ID is pinned to one
algorithm. Unknown keys, `none`, algorithm confusion and unsupported JOSE extensions
are rejected. A token cannot select a file or URL to load.

## Enable HMAC Caching Explicitly

Keep the existing secret in your deployment secret store. These are application properties:

```properties
reactor.security.enabled=true
reactor.security.jwt.issuer=https://issuer.internal.example
reactor.security.jwt.audience=orders-api
reactor.security.jwt.require-expiration=true
reactor.security.jwt.clock-skew-seconds=30
reactor.security.jwt.max-token-chars=8192
```

Provide `REACTOR_SECURITY_JWT_HMAC_SECRET` through a Kubernetes Secret or an equivalent
secret source. The secret must have at least 32 UTF-8 bytes. Do not commit it.

```java
package example;

import com.reactor.rust.security.JwtVerifier;
import com.reactor.rust.security.JwtVerifierProvider;
import com.reactor.rust.security.VerifiedTokenCache;

public final class ApiJwtProvider implements JwtVerifierProvider {
    @Override public JwtVerifier create() {
        return VerifiedTokenCache.hmacFromProperties(
                new VerifiedTokenCache.Options(256, 4, 0, 256 * 1024));
    }
}
```

Register this one startup provider in
`src/main/resources/META-INF/services/com.reactor.rust.security.JwtVerifierProvider`:

```text
example.ApiJwtProvider
```

Your existing `@Authenticated` and `@PermitAll` routes do not change. Select at most
one provider. Without a provider, the existing uncached HS256 path remains in use.

## Configure Public Keys At Startup

The following replaces the body of `create()` above. `trustedPublicKey` is a public
key loaded from your trusted deployment configuration, not from a token header.

```java
var keys = new com.reactor.rust.security.JwtKeySet(
        new com.reactor.rust.security.JwtKeySet.Key("orders-2026",
                com.reactor.rust.security.JwtAlgorithm.RS256, trustedPublicKey));
var policy = com.reactor.rust.security.JwtVerificationPolicy.forIssuerAndAudience(
        "https://issuer.internal.example", "orders-api");
var verifier = new com.reactor.rust.security.AsymmetricJwtVerifier(
        keys, policy, java.time.Clock.systemUTC(), 4);
return verifier.withCache(new VerifiedTokenCache.Options(256, 4, 0, 256 * 1024));
```

RS256 accepts bounded 2048–8192-bit RSA keys. ES256 requires P-256 and a 64-byte JOSE
signature. EdDSA means Ed25519, not Ed448. The local key set has at most 64 keys.
Rotate by replacing the verifier and its cache together. For rolling deployments,
allow the old and new public keys during the agreed overlap, then remove the old key.
`clear()` does not add a distributed revocation service.

## Optional Native HS256 Guard

For fixed-key HS256 routes, the optional
[`@RustEdgeJwt` guide](../../NATIVE_JWT.md) moves signature checking to Rust.
Use its generated installer and matching native artifact. The built-in Java guard
then reuses the native request proof instead of repeating HMAC. It still checks
the current time, identity and roles. A token that expires in the JNI queue fails.
Missing security SPI or mismatched policies fail startup. Custom verifiers keep
their full checks. This is separate from the Java claims cache described below;
it requires the matching native `edge-jwt` capability, not merely REST ABI 29.

## Cache And Resource Limits

| Option in the example | Meaning |
| --- | --- |
| `256` slots | Fixed two-way set-associative capacity; must be a power of two |
| `4` workers | Bounded concurrent hashing work; the verifier has its own explicit crypto worker limit |
| `0` missing-expiration TTL | Do not cache a token without `exp`, even when the verification policy allows it |
| `256 * 1024` maxBytes | Conservative positive-entry/claims retention budget, not total process RSS |

A hit hashes the **complete** token and checks the current time again. It skips
signature/MAC verification and JSON parsing. Only successful verification is cached.
The full SHA-256 digest is retained; token text is not. Collisions remain a cryptographic
assumption. Concurrent cold misses may verify more than once.

If the byte budget is full, a valid token can still be verified without being cached.
Worker exhaustion fails fast with the framework's `503` dependency error. No retry is
added. Invalid or expired credentials remain `401`.

The byte budget excludes fixed slot/hash state, crypto workers, keys, JCA internals,
application-held principals and JVM memory. Logical eviction does not promise an
immediate RSS drop. Use hit/miss, retained-entry/byte and crypto-verification counters
alongside container RSS, useful RPS and p99. Never log bearer tokens.

### Small Fixed Working State

Asymmetric signature input and cache hashing each use at most 256 bytes of reusable
ASCII scratch per worker. Larger permitted tokens are processed in chunks; the token
limit is still enforced. Scratch is erased after successful and failed calls. All
32 SHA-256 digest bytes remain in the cache key; this is not a truncated shortcut.

`maxBytes=0` is a true storage bypass: capacity is zero, no hash workers or positive
slot storage are created, and each call still goes through full verification. Use
the verifier directly to avoid the wrapper as well. Fixed-state estimates are not
JCA measurements and must not be presented as process RSS.

An initialized signature engine is reused for the same trusted key. A changed key
or interrupted/provider-failed input forces reinitialization. This follows the
[Java 21 Signature verification contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/Signature.html#verify(byte%5B%5D));
it does not skip signature verification on uncached requests.

Expired entries in an idle cache are bounded, not proactively swept. A matching
lookup, replacement or explicit `clear()` releases them. There is no maintenance
thread. `clear()` prevents old in-flight misses repopulating the live generation;
it is not a distributed revocation barrier for requests already executing.

Start with a worker count matching your actual concurrent verifier callers, not
incoming TCP connections. Public-key verification has real CPU/JCA cost. A cache
is useful when tokens repeat; it does not make unique-token signature work free.
For mostly unique tokens, compare cache off/on before spending a larger byte budget.

The isolated [JWT sample](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/JWT_SECURITY.md)
shows `@Authenticated`, trusted key files, optional cache properties and Kubernetes
environment settings, plus JDK-only token issuance and error checks. Its checkout
`jwt-security` profile does not alter the Redis reader.

Allocation profiling also checks small retained-object controls. If the JVM reports
only delayed allocation chunks, the profiling test skips instead of claiming zero.
Calibrated HotSpot Java 21 measurements are separate from OpenJ9 container acceptance.

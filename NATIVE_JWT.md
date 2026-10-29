# Reject Invalid HS256 Tokens Before JNI

[English](NATIVE_JWT.md) | [Turkish](NATIVE_JWT.tr.md)

First published in REST 4.8.0, this filter remains off until the generated
installer is explicitly called before route registration. Packaged binaries include
`edge-jwt`; custom builds need `--features edge-jwt`. REST ABI 29 alone is not a
capability check. See [platform verification limits](docs/native-availability.md):
Linux x64 runtime evidence is not ARM runtime certification, and published 4.8.0
ARM64 sidecars require the prepared 4.8.1 correction.

## Choose This Path

Use this filter when invalid bearer traffic reaches an HS256-protected API.
Rust rejects an invalid token with HTTP 401 before the JNI queue. A valid token
still enters Java. The built-in HS256 guard reuses Rust's signature proof instead
of calculating the same HMAC again. Java still checks identity, time, roles and
business authorization. Custom verifier implementations retain their full path.
This is not an authorization bypass or a response cache.

Keep the Java-only path for RSA, P-256, Ed25519, custom subject claims, dynamic key
providers, or legacy error JSON. This native filter supports HS256 with `sub` and
the standard problem-details error format. It never downloads keys or JWKS.

## Declare A Protected Route

```java
package example;

import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.RestController;
import com.reactor.rust.annotations.RustEdgeJwt;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.security.Authenticated;
import java.nio.charset.StandardCharsets;

@RestController("/accounts")
public final class AccountApi {
    private static final RawResponse OK = RawResponse.json(
            "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));

    @Authenticated(roles = "reader")
    @RustEdgeJwt
    @GetMapping("/me")
    public RawResponse me() { return OK; }
}
```

Keep the security starter on the classpath.
Keep its `META-INF/services` registrations when shading or repackaging. Check both
the reader's 200 response and the admin route's 403 response in the deployed image;
token rejection alone does not prove that Java role authorization is installed.

The compiler generates
`AccountApi_EdgeJwtPolicies`. In your application's startup, after loading
configuration and **before** registering HTTP routes, call:

```java
PropertiesLoader.load();
AccountApi_EdgeJwtPolicies.install();
RestApplication.run(Application.class, args);
```

Here the imports are `com.reactor.rust.config.PropertiesLoader` and
`com.reactor.rust.app.RestApplication`; `Application` is your existing
`@ReactorApplication` entry point. An annotation alone does not activate native
code. The [runnable sample](https://github.com/esasmer-dou/rest-sample-cache-reader/blob/master/OUTBOUND.md)
uses `sample.edge.jwt.enabled` (default `false`) to decide whether to call the
generated installer. This is a sample startup switch, not a core runtime property.

Do not combine this route with public edge caching or `@PermitAll`. Generated
configuration rejects those combinations. Native static/file routes cannot replace
the Java guard either. Call the installer once; policies freeze when routes start.

## Use The Same Policy In Both Languages

```properties
reactor.security.enabled=true
reactor.security.jwt.issuer=sample-issuer
reactor.security.jwt.audience=stock-api
reactor.security.jwt.subject-claim=sub
reactor.security.jwt.require-expiration=true
reactor.security.jwt.clock-skew-seconds=0
reactor.security.jwt.max-token-chars=8192
reactor.rust.errors.format=problem-details
```

Supply `REACTOR_SECURITY_JWT_HMAC_SECRET` from a deployment secret. Do not commit it.
The shared namespace is `reactor.security.jwt`; a different annotation prefix is
rejected rather than silently using a different Java/native policy.

| Property | Effect / bound |
| --- | --- |
| `reactor.security.enabled` | Must remain `true`; Java authorization is mandatory |
| `reactor.security.jwt.hmac-secret` | 32–1024 UTF-8 bytes; use a high-entropy secret |
| `reactor.security.jwt.issuer` | Exact issuer when set; at most 256 UTF-8 bytes |
| `reactor.security.jwt.audience` | Exact audience when set; string or up to 32 audience values |
| `reactor.security.jwt.require-expiration` | Default `true`; reject a missing `exp` |
| `reactor.security.jwt.clock-skew-seconds` | 0–300 seconds; applies to `exp` and `nbf` |
| `reactor.security.jwt.max-token-chars` | 64–8192; the JWT must be canonical base64url |

Algorithm confusion, unsupported JOSE headers, duplicate registered claims,
duplicate Authorization headers, invalid signatures, expired tokens and future
`nbf` values are rejected. The decoded header is limited to 1 KiB. Escaped JSON
strings remain supported within the token limit.

## Memory And Lifecycle

Each policy retains only a fixed 32-slot verified-token digest/time cache.
The lookup uses a full 256-bit BLAKE3 fingerprint of the complete token. This is
an internal lookup key, not a change to HS256: signatures remain HMAC-SHA256.
Rust SIMD is used without adding a C/assembler toolchain. A configured rate-only
edge policy can reject overload before signature work; public caching cannot be
combined with this protected route.
It does not retain raw tokens or user payloads. Every cache hit checks `exp` and
`nbf` again. Java checks the current time again at the guard boundary. A token that
expires while waiting in the JNI queue is rejected even if Rust accepted it earlier.

The generated installer binds each annotated Java method to its native policy.
Startup checks that the key, issuer, audience, size, clock skew and expiration
settings match. Missing security SPI registration or different settings stop
startup. No public HTTP header can substitute for a verified request.

The proof contains a policy ID and the full token fingerprint. It travels in the
native request job and is visible only during that job's JNI call. Another token,
route, thread or later callback cannot reuse it. Proof checking uses bounded stack
buffers; it does not create another token byte array. A direct call to a protected
guard without this proof fails closed. Java authorization remains mandatory.

Custom verifiers are not replaced by this optimization. Their revocation and
additional checks still run. The lower-level `registerFromProperties` API alone
does not bind a Java method and therefore does not remove its Java HMAC check.

There is no refresh thread, network key lookup or per-user growing map. Up to 64
routes can register policies. Rotate keys or change issuer/audience by restarting
the application. Do not use this fixed-key filter with a different dynamic Java
verifier. A missing feature/symbol fails startup; no silent fallback is installed.

Routes retain a small policy ID. Frozen process-owned storage keeps the actual
policy alive, so request matching does not clone or release a policy `Arc`.
Invalid IDs or an uninitialized frozen table are rejected, not treated as a valid token.

Without the Cargo feature, the native JWT path is not compiled. In a feature-enabled
artifact, only explicitly installed routes get a JWT policy. This does not make
the whole HTTP/JNI request allocation-free or guarantee lower latency for every
valid-token workload; measure that workload before enabling it.

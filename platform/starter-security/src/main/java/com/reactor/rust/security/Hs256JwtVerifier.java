package com.reactor.rust.security;

import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.exception.UnauthorizedException;
import com.reactor.rust.bridge.NativeEdgeJwt;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/** Minimal HS256 verifier with issuer, audience, expiry, not-before, subject, and role checks. */
final class Hs256JwtVerifier implements JwtVerifier {
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final String issuer;
    private final String audience;
    private final String rolesClaim;
    private final String subjectClaim;
    private final long clockSkewSeconds;
    private final int maxTokenChars;
    private final boolean requireExpiration;
    private final ThreadLocal<Mac> mac;

    private Hs256JwtVerifier(
            byte[] secret,
            String issuer,
            String audience,
            String rolesClaim,
            String subjectClaim,
            long clockSkewSeconds,
            int maxTokenChars,
            boolean requireExpiration) {
        this.key = new SecretKeySpec(secret, "HmacSHA256");
        this.issuer = issuer;
        this.audience = audience;
        this.rolesClaim = rolesClaim;
        this.subjectClaim = subjectClaim;
        this.clockSkewSeconds = clockSkewSeconds;
        this.maxTokenChars = maxTokenChars;
        this.requireExpiration = requireExpiration;
        this.mac = ThreadLocal.withInitial(this::newMac);
    }

    static Hs256JwtVerifier fromProperties() {
        String secret = PropertiesLoader.require("reactor.security.jwt.hmac-secret");
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("reactor.security.jwt.hmac-secret must contain at least 32 UTF-8 bytes");
        }
        String rolesClaim = claimName(
                PropertiesLoader.get("reactor.security.jwt.roles-claim", "roles"),
                "roles",
                "reactor.security.jwt.roles-claim");
        String subjectClaim = claimName(
                PropertiesLoader.get("reactor.security.jwt.subject-claim", "sub"),
                "sub",
                "reactor.security.jwt.subject-claim");
        if (rolesClaim.equals(subjectClaim)) {
            throw new IllegalStateException("JWT subject and roles claims must use different names");
        }
        return new Hs256JwtVerifier(
                secretBytes,
                trim(PropertiesLoader.get("reactor.security.jwt.issuer", "")),
                trim(PropertiesLoader.get("reactor.security.jwt.audience", "")),
                rolesClaim,
                subjectClaim,
                Math.max(0L, PropertiesLoader.getLong("reactor.security.jwt.clock-skew-seconds", 30L)),
                positive(PropertiesLoader.getInt("reactor.security.jwt.max-token-chars", 8_192),
                        "reactor.security.jwt.max-token-chars"),
                PropertiesLoader.getBoolean("reactor.security.jwt.require-expiration", true)
        );
    }

    @Override
    public SecurityPrincipal verify(String token) {
        try {
            if (token == null || token.length() > maxTokenChars) throw invalid();
            int firstDot = token.indexOf('.');
            int secondDot = firstDot < 0 ? -1 : token.indexOf('.', firstDot + 1);
            if (firstDot <= 0 || secondDot <= firstDot + 1 || secondDot == token.length() - 1
                    || token.indexOf('.', secondDot + 1) >= 0) {
                throw invalid();
            }

            byte[] headerBytes = decodeCanonical(token.substring(0, firstDot));
            String algorithm = JwtJson.objectString(headerBytes, "alg");
            if (!"HS256".equals(algorithm)) throw invalid();

            byte[] expected = sign(token.substring(0, secondDot));
            byte[] actual = decodeCanonical(token.substring(secondDot + 1));
            if (!MessageDigest.isEqual(expected, actual)) throw invalid();

            byte[] payload = decodeCanonical(token.substring(firstDot + 1, secondDot));
            JwtJson.Claims claims = JwtJson.claims(payload, subjectClaim, rolesClaim, issuer, audience);
            long now = Instant.now().getEpochSecond();
            if (requireExpiration && !claims.expirySeen) throw invalid();
            if (claims.expirySeen && now - clockSkewSeconds >= claims.expiry) throw invalid();
            if (claims.notBeforeSeen && now + clockSkewSeconds < claims.notBefore) throw invalid();
            if (!issuer.isEmpty() && !issuer.equals(claims.issuer)) throw invalid();
            if (!audience.isEmpty() && !claims.audienceMatched) throw invalid();
            if (claims.subject == null || claims.subject.isBlank()) throw invalid();
            return new SecurityPrincipal(claims.subject, claims.roles);
        } catch (UnauthorizedException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    private byte[] sign(String input) {
        Mac local = mac.get();
        local.reset();
        return local.doFinal(input.getBytes(StandardCharsets.US_ASCII));
    }

    private Mac newMac() {
        try {
            Mac value = Mac.getInstance("HmacSHA256");
            value.init(key);
            return value;
        } catch (Exception error) {
            throw new IllegalStateException("HmacSHA256 is unavailable", error);
        }
    }

    int maxTokenCharsForCache() { return maxTokenChars; }
    Mac newMacForCache() { return newMac(); }

    JwtVerifier nativeGuardFor(Class<?> owner, java.lang.reflect.Method method) {
        if (!NativeEdgeJwt.hasGuardBinding(owner, method)) return this;
        if (!"sub".equals(subjectClaim)) throw new IllegalStateException("Native JWT guard requires subject-claim=sub");
        int policy = NativeEdgeJwt.guardPolicy(owner, method, key.getEncoded(), issuer, audience,
                maxTokenChars, clockSkewSeconds, requireExpiration);
        return token -> verifyNative(token, policy);
    }

    /** The signature belongs to Rust; identity, current time and authorization remain in Java. */
    SecurityPrincipal verifyNative(String token, int policy) {
        try {
            if (token == null || token.length() > maxTokenChars || !NativeEdgeJwt.isCurrentTokenVerified(policy, token)) {
                throw invalid();
            }
            int first = token.indexOf('.');
            int second = first < 0 ? -1 : token.indexOf('.', first + 1);
            if (first <= 0 || second <= first + 1 || token.indexOf('.', second + 1) >= 0) throw invalid();
            // No header/signature arrays or signing-input String. The proof binds all token bytes.
            byte[] payload = decodeCanonical(token.substring(first + 1, second));
            JwtJson.Claims claims = JwtJson.claims(payload, subjectClaim, rolesClaim, issuer, audience);
            long now = Instant.now().getEpochSecond();
            if (requireExpiration && !claims.expirySeen) throw invalid();
            if (claims.expirySeen && now - clockSkewSeconds >= claims.expiry) throw invalid();
            if (claims.notBeforeSeen && now + clockSkewSeconds < claims.notBefore) throw invalid();
            if (!issuer.isEmpty() && !issuer.equals(claims.issuer)) throw invalid();
            if (!audience.isEmpty() && !claims.audienceMatched) throw invalid();
            if (claims.subject == null || claims.subject.isBlank()) throw invalid();
            return new SecurityPrincipal(claims.subject, claims.roles);
        } catch (UnauthorizedException error) { throw error; }
        catch (RuntimeException error) { throw invalid(); }
    }

    /** Optional pooled miss path ONLY. Default verify() never calls this or allocates JwtClaims. */
    JwtClaims verifyClaimsForCache(String token, Mac local, byte[] input,
                                  java.util.concurrent.atomic.AtomicLong macCalls) {
        try {
            if (token == null || token.length() > maxTokenChars) throw invalid();
            int firstDot = token.indexOf('.');
            int secondDot = firstDot < 0 ? -1 : token.indexOf('.', firstDot + 1);
            if (firstDot <= 0 || secondDot <= firstDot + 1 || secondDot == token.length() - 1
                    || token.indexOf('.', secondDot + 1) >= 0) throw invalid();
            byte[] header = decodeCanonical(token.substring(0, firstDot));
            if (!"HS256".equals(JwtJson.objectString(header, "alg"))) throw invalid();
            local.reset();
            local.update(input, 0, secondDot);
            macCalls.incrementAndGet();
            byte[] expected = local.doFinal();
            byte[] actual = decodeCanonical(token.substring(secondDot + 1));
            if (!MessageDigest.isEqual(expected, actual)) throw invalid();
            JwtJson.Claims claims = JwtJson.claims(decodeCanonical(token.substring(firstDot + 1, secondDot)),
                    subjectClaim, rolesClaim, issuer, audience);
            if (!issuer.isEmpty() && !issuer.equals(claims.issuer)) throw invalid();
            if (!audience.isEmpty() && !claims.audienceMatched) throw invalid();
            if (claims.subject == null || claims.subject.isBlank()) throw invalid();
            return new JwtClaims(new SecurityPrincipal(claims.subject, claims.roles), claims.issuer,
                    claims.expirySeen, claims.expiry, claims.notBeforeSeen, claims.notBefore);
        } catch (UnauthorizedException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    /** Deliberately preserves legacy arithmetic and policy instead of applying new asymmetric bounds. */
    void validateCachedTime(JwtClaims claims, long now) {
        if (requireExpiration && !claims.hasExpiration()) throw invalid();
        if (claims.hasExpiration() && now - clockSkewSeconds >= claims.expirationEpochSecond()) throw invalid();
        if (claims.hasNotBefore() && now + clockSkewSeconds < claims.notBeforeEpochSecond()) throw invalid();
    }

    static byte[] decodeCanonical(String value) {
        if (value.isEmpty() || value.indexOf('=') >= 0 || (value.length() & 3) == 1) throw invalid();
        int remainder = value.length() & 3;
        if (remainder != 0) {
            char last = value.charAt(value.length() - 1);
            int digit = last >= 'A' && last <= 'Z' ? last - 'A'
                    : last >= 'a' && last <= 'z' ? last - 'a' + 26
                    : last >= '0' && last <= '9' ? last - '0' + 52
                    : last == '-' ? 62 : last == '_' ? 63 : -1;
            int unusedBits = remainder == 2 ? 15 : 3;
            if (digit < 0 || (digit & unusedBits) != 0) throw invalid();
        }
        return URL_DECODER.decode(value);
    }

    private static int positive(int value, String key) {
        if (value <= 0) throw new IllegalArgumentException(key + " must be > 0");
        return value;
    }

    private static UnauthorizedException invalid() {
        return new UnauthorizedException("Bearer token is invalid or expired");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nonBlank(String value, String fallback) {
        String normalized = trim(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String claimName(String value, String fallback, String key) {
        String normalized = nonBlank(value, fallback);
        if ("iss".equals(normalized) || "aud".equals(normalized)
                || "exp".equals(normalized) || "nbf".equals(normalized)) {
            throw new IllegalStateException(key + " must not shadow a registered JWT claim");
        }
        return normalized;
    }

}

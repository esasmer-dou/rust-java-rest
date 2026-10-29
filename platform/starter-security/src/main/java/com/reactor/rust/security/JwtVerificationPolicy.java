package com.reactor.rust.security;

import com.reactor.rust.exception.UnauthorizedException;
import java.util.Objects;

/** Immutable application-specific acceptance rules. Configure once, before publishing a verifier. */
public record JwtVerificationPolicy(String issuer, String audience, String subjectClaim, String rolesClaim,
                                    long clockSkewSeconds, boolean requireExpiration, int maxTokenChars,
                                    int maxClaimChars, int maxRoles, int maxClaims) {
    public JwtVerificationPolicy {
        issuer = Objects.requireNonNull(issuer, "issuer");
        audience = Objects.requireNonNull(audience, "audience");
        checkClaimName(subjectClaim);
        checkClaimName(rolesClaim);
        if (subjectClaim.equals(rolesClaim)) throw new IllegalArgumentException("Subject and roles claims must differ");
        if (clockSkewSeconds < 0 || clockSkewSeconds > 300 || maxTokenChars < 32 || maxTokenChars > 65_536
                || maxClaimChars < 1 || maxClaimChars > 4096 || maxClaimChars > maxTokenChars
                || maxRoles < 1 || maxRoles > 256 || maxClaims < 1 || maxClaims > 256
                || issuer.length() > maxClaimChars || audience.length() > maxClaimChars) {
            throw new IllegalArgumentException("JWT verification policy exceeds supported bounds");
        }
    }

    public static JwtVerificationPolicy defaults() { return forIssuerAndAudience("", ""); }

    public static JwtVerificationPolicy forIssuerAndAudience(String issuer, String audience) {
        return new JwtVerificationPolicy(issuer, audience, "sub", "roles", 30, true, 8192, 1024, 64, 64);
    }

    private static void checkClaimName(String name) {
        Objects.requireNonNull(name, "claim name");
        if (name.isBlank() || name.length() > 64 || name.equals("iss") || name.equals("aud")
                || name.equals("exp") || name.equals("nbf")) throw new IllegalArgumentException("Invalid JWT claim name");
    }

    JwtClaims accept(JwtJson.Claims parsed, long now) {
        if (parsed.subject == null || parsed.subject.isBlank() || (!issuer.isEmpty() && !issuer.equals(parsed.issuer))
                || (!audience.isEmpty() && !parsed.audienceMatched)) throw invalid();
        JwtClaims claims = new JwtClaims(new SecurityPrincipal(parsed.subject, parsed.roles), parsed.issuer,
                parsed.expirySeen, parsed.expiry, parsed.notBeforeSeen, parsed.notBefore);
        validateTime(claims, now);
        return claims;
    }

    void validateTime(JwtClaims claims, long now) {
        if ((requireExpiration && !claims.hasExpiration())
                || (claims.hasExpiration() && now >= addSaturated(claims.expirationEpochSecond(), clockSkewSeconds))
                || (claims.hasNotBefore() && now < addSaturated(claims.notBeforeEpochSecond(), -clockSkewSeconds))) {
            throw invalid();
        }
    }

    static long addSaturated(long value, long delta) {
        if (delta > 0 && value > Long.MAX_VALUE - delta) return Long.MAX_VALUE;
        if (delta < 0 && value < Long.MIN_VALUE - delta) return Long.MIN_VALUE;
        return value + delta;
    }

    static UnauthorizedException invalid() { return new UnauthorizedException("Bearer token is invalid or expired"); }
}

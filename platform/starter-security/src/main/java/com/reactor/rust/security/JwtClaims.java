package com.reactor.rust.security;

/** Immutable verified data. Presence flags distinguish absent dates from all signed long values. */
public final class JwtClaims {
    private final SecurityPrincipal principal;
    private final String issuer;
    private final boolean hasExpiration;
    private final long expiration;
    private final boolean hasNotBefore;
    private final long notBefore;

    JwtClaims(SecurityPrincipal principal, String issuer, boolean hasExpiration, long expiration,
              boolean hasNotBefore, long notBefore) {
        this.principal = principal;
        this.issuer = issuer;
        this.hasExpiration = hasExpiration;
        this.expiration = expiration;
        this.hasNotBefore = hasNotBefore;
        this.notBefore = notBefore;
    }

    public SecurityPrincipal principal() { return principal; }
    public String issuer() { return issuer; }
    public boolean hasExpiration() { return hasExpiration; }
    public long expirationEpochSecond() { return expiration; }
    public boolean hasNotBefore() { return hasNotBefore; }
    public long notBeforeEpochSecond() { return notBefore; }

    long retainedBytesEstimate() {
        return 128 + principal.retainedBytesEstimate() + SecurityPrincipal.stringBytes(issuer);
    }
}

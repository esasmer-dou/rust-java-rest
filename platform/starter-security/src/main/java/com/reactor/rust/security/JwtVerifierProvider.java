package com.reactor.rust.security;

/** Optional startup-only SPI. Local asymmetric keys are explicit; no token-directed key discovery. */
@FunctionalInterface
public interface JwtVerifierProvider {
    JwtVerifier create();

    /** Explicit opt-in; null cacheOptions means no cache. Every create() gets an independent verifier/cache. */
    static JwtVerifierProvider asymmetric(JwtKeySet keys, JwtVerificationPolicy policy, java.time.Clock clock,
                                           int maxWorkers, VerifiedTokenCache.Options cacheOptions) {
        java.util.Objects.requireNonNull(keys, "keys");
        java.util.Objects.requireNonNull(policy, "policy");
        java.util.Objects.requireNonNull(clock, "clock");
        JwtWorkPool.checkSize(maxWorkers);
        return () -> {
            AsymmetricJwtVerifier verifier = new AsymmetricJwtVerifier(keys, policy, clock, maxWorkers);
            return cacheOptions == null ? verifier : verifier.withCache(cacheOptions);
        };
    }
}

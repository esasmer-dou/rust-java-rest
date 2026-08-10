package com.reactor.rust.security;

/** Optional SPI for RS256, JWKS, or an organization-specific token verifier. */
@FunctionalInterface
public interface JwtVerifierProvider {
    JwtVerifier create();
}

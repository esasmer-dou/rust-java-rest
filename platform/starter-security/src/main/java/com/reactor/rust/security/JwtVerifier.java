package com.reactor.rust.security;

/** Verifies a compact JWT and returns the minimal identity needed by Java business logic. */
@FunctionalInterface
public interface JwtVerifier {
    SecurityPrincipal verify(String token);
}

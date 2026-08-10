package com.reactor.rust.security;

import com.reactor.rust.exception.UnauthorizedException;

import java.util.Optional;

/** Request-scoped security context; no context exists for unguarded routes. */
public final class SecurityContext {
    private static final ThreadLocal<SecurityPrincipal> CURRENT = new ThreadLocal<>();

    private SecurityContext() {}

    public static Optional<SecurityPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static SecurityPrincipal requirePrincipal() {
        SecurityPrincipal principal = CURRENT.get();
        if (principal == null) throw new UnauthorizedException("Authentication is required");
        return principal;
    }

    static void set(SecurityPrincipal principal) {
        CURRENT.set(principal);
    }

    static void clear() {
        CURRENT.remove();
    }
}

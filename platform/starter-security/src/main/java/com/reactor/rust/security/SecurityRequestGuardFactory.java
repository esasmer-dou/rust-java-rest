package com.reactor.rust.security;

import com.reactor.rust.bridge.RequestGuard;
import com.reactor.rust.bridge.RequestGuardFactory;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.exception.ForbiddenException;
import com.reactor.rust.exception.UnauthorizedException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.ServiceLoader;

/** Creates immutable route guards once while startup builds the route table. */
public final class SecurityRequestGuardFactory implements RequestGuardFactory {
    private final boolean enabled;
    private final JwtVerifier verifier;

    public SecurityRequestGuardFactory() {
        this.enabled = PropertiesLoader.getBoolean("reactor.security.enabled", false);
        this.verifier = enabled ? loadVerifier() : null;
    }

    @Override
    public int order() {
        return 10;
    }

    private static JwtVerifier loadVerifier() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = SecurityRequestGuardFactory.class.getClassLoader();
        List<JwtVerifierProvider> providers = ServiceLoader.load(JwtVerifierProvider.class, loader).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        if (providers.size() > 1) {
            throw new IllegalStateException("Multiple JwtVerifierProvider implementations found: " + providers.size());
        }
        return providers.isEmpty() ? Hs256JwtVerifier.fromProperties() : providers.get(0).create();
    }

    @Override
    public RequestGuard create(Class<?> owner, Method method) {
        if (method.isAnnotationPresent(PermitAll.class)) return null;
        Authenticated policy = method.getAnnotation(Authenticated.class);
        if (policy == null) policy = owner.getAnnotation(Authenticated.class);
        if (policy == null) return null;
        if (!enabled) {
            throw new IllegalStateException(
                    "@Authenticated route detected while reactor.security.enabled=false: "
                            + owner.getName() + '#' + method.getName());
        }
        String[] requiredRoles = policy.roles().clone();
        return new JwtGuard(verifier, requiredRoles);
    }

    private static final class JwtGuard implements RequestGuard {
        private final JwtVerifier verifier;
        private final String[] requiredRoles;

        private JwtGuard(JwtVerifier verifier, String[] requiredRoles) {
            this.verifier = verifier;
            this.requiredRoles = requiredRoles;
        }

        @Override
        public void before(com.reactor.rust.bridge.RequestGuardContext request) {
            String authorization = request.header("authorization");
            if (authorization == null || authorization.length() < 8
                    || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new UnauthorizedException("A Bearer token is required");
            }
            String token = authorization.substring(7).trim();
            if (token.isEmpty()) throw new UnauthorizedException("A Bearer token is required");
            SecurityPrincipal principal = verifier.verify(token);
            if (requiredRoles.length > 0 && !hasRequiredRole(principal, requiredRoles)) {
                throw new ForbiddenException("The authenticated identity does not have a required role");
            }
            SecurityContext.set(principal);
        }

        @Override
        public void after() {
            SecurityContext.clear();
        }

        private static boolean hasRequiredRole(SecurityPrincipal principal, String[] roles) {
            for (String role : roles) {
                if (principal.hasRole(role)) return true;
            }
            return false;
        }
    }
}

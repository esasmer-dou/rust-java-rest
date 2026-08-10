package com.reactor.rust.security;

import java.util.Arrays;

/** Immutable authenticated identity retained only for the duration of handler invocation. */
public final class SecurityPrincipal {
    private final String subject;
    private final String[] roles;

    SecurityPrincipal(String subject, String[] roles) {
        this.subject = subject == null ? "" : subject;
        this.roles = roles == null || roles.length == 0 ? new String[0] : roles.clone();
    }

    public String subject() {
        return subject;
    }

    public String[] roles() {
        return roles.clone();
    }

    public boolean hasRole(String role) {
        if (role == null) return false;
        for (String candidate : roles) {
            if (role.equals(candidate)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "SecurityPrincipal[subject=" + subject + ", roles=" + Arrays.toString(roles) + ']';
    }
}

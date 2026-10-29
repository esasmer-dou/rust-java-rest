package com.reactor.rust.security;

import java.util.Arrays;

/** Immutable authenticated identity; an explicitly configured verified-token cache may retain it. */
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

    /** Conservative 64-bit JVM accounting, charging UTF-16 even when compact Strings are used. */
    long retainedBytesEstimate() {
        long bytes = 96 + aligned(48L + 8L * roles.length) + stringBytes(subject);
        for (String role : roles) bytes += stringBytes(role);
        return bytes;
    }

    static long stringBytes(String value) {
        return value == null ? 0 : aligned(96L + 2L * value.length());
    }

    private static long aligned(long bytes) { return (bytes + 15) & ~15L; }

    @Override
    public String toString() {
        return "SecurityPrincipal[subject=" + subject + ", roles=" + Arrays.toString(roles) + ']';
    }
}

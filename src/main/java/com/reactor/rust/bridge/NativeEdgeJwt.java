package com.reactor.rust.bridge;

import com.reactor.rust.config.PropertiesLoader;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ServiceLoader;

/** Startup-only HS256 policy registration. No token/claims cache or Java background task. */
public final class NativeEdgeJwt {
    private static Binding[] bindings;
    private static volatile int bindingCount;
    private NativeEdgeJwt() {}

    private record Binding(Class<?> owner, String method, Class<?>[] arguments, String verb, String path) {
        boolean matches(Class<?> type, Method candidate) {
            return owner == type && method.equals(candidate.getName())
                    && Arrays.equals(arguments, candidate.getParameterTypes());
        }
    }

    /** Generated startup binding. The ordinary guard remains unchanged when no binding exists. */
    public static synchronized void registerGuardFromProperties(Class<?> owner, String method, Class<?>[] arguments,
                                                                 String verb, String path, String prefix) {
        if (owner == null || method == null || arguments == null || arguments.length > 32 || bindingCount == 64) {
            throw new IllegalArgumentException("Edge JWT needs one of at most 64 bounded method bindings");
        }
        for (int i = 0; i < bindingCount; i++) {
            Binding previous = bindings[i];
            if (previous.owner == owner && previous.method.equals(method) && Arrays.equals(previous.arguments, arguments)) {
                throw new IllegalStateException("Duplicate edge JWT guard binding");
            }
        }
        registerFromProperties(verb, path, prefix);
        if ((proofCapabilities0() & 1) == 0) throw new IllegalStateException("Native artifact lacks scoped JWT request proofs");
        if (bindings == null) bindings = new Binding[64];
        bindings[bindingCount] = new Binding(owner, method, arguments.clone(), verb, path);
        bindingCount++;
    }

    public static boolean hasGuardBinding(Class<?> owner, Method method) {
        for (int i = 0, count = bindingCount; i < count; i++) if (bindings[i].matches(owner, method)) return true;
        return false;
    }

    /** Startup-only equality check, including the key, before selecting the one-signature guard. */
    public static int guardPolicy(Class<?> owner, Method method, byte[] secret, String issuer, String audience,
                                  int maximum, long skew, boolean requireExpiration) {
        try {
            for (int i = 0, count = bindingCount; i < count; i++) {
                Binding binding = bindings[i];
                if (binding.matches(owner, method)) {
                    return guardPolicy0(binding.verb, binding.path, secret, issuer, audience, maximum, skew, requireExpiration);
                }
            }
            throw new IllegalStateException("Missing generated native JWT guard binding");
        } finally { Arrays.fill(secret, (byte) 0); }
    }

    /** Only a live matching native JNI request can authorize this token; no HTTP marker is trusted. */
    public static boolean isCurrentTokenVerified(int policy, String token) {
        return token != null && token.length() <= 8192 && verifiedToken0(policy, token);
    }

    public static void registerFromProperties(String method, String path, String prefix) {
        if (!"reactor.security.jwt".equals(prefix)) {
            throw new IllegalArgumentException("Edge JWT must share reactor.security.jwt with the Java verifier");
        }
        if (!PropertiesLoader.getBoolean("reactor.security.enabled", false)) {
            throw new IllegalStateException("Edge JWT requires the Java security guard to remain enabled");
        }
        if (!"problem-details".equalsIgnoreCase(PropertiesLoader.get("reactor.rust.errors.format", "problem-details"))) {
            throw new IllegalArgumentException("Edge JWT currently requires problem-details errors; keep the Java path for legacy error JSON");
        }
        if (!"sub".equals(PropertiesLoader.get(prefix + ".subject-claim", "sub"))) {
            throw new IllegalArgumentException("HS256 edge JWT currently requires subject-claim=sub; use the Java verifier for custom claims");
        }
        String secret = PropertiesLoader.require(prefix + ".hmac-secret");
        String issuer = PropertiesLoader.get(prefix + ".issuer", "").trim();
        String audience = PropertiesLoader.get(prefix + ".audience", "").trim();
        int maximum = PropertiesLoader.getInt(prefix + ".max-token-chars", 8192);
        int skew = PropertiesLoader.getInt(prefix + ".clock-skew-seconds", 30);
        int secretBytes = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (secretBytes < 32 || secretBytes > 1024 || issuer.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256
                || audience.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256 || maximum < 64
                || maximum > 8192 || skew < 0 || skew > 300) {
            throw new IllegalArgumentException("Edge JWT needs a 32..1024-byte secret, issuer/audience <=256 bytes, max-token-chars=64..8192 and clock-skew-seconds=0..300");
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = NativeEdgeJwt.class.getClassLoader();
        boolean guardPresent = ServiceLoader.load(RequestGuardFactory.class, loader).stream().anyMatch(
                provider -> provider.type().getName().equals("com.reactor.rust.security.SecurityRequestGuardFactory"));
        if (!guardPresent) throw new IllegalStateException("Edge JWT requires SecurityRequestGuardFactory in META-INF/services; Java authorization must not be omitted");
        NativeLibraryLoader.load();
        try {
            register0(method, path, secret, issuer, audience, maximum, skew,
                    PropertiesLoader.getBoolean(prefix + ".require-expiration", true));
        } catch (UnsatisfiedLinkError missing) {
            throw new IllegalStateException("Edge JWT requires a native artifact built with edge-jwt; no silent fallback", missing);
        }
    }

    private static native void register0(String method, String path, String secret, String issuer,
                                         String audience, int maxToken, int skew, boolean requireExpiration);
    private static native int proofCapabilities0();
    private static native int guardPolicy0(String method, String path, byte[] secret, String issuer, String audience,
                                           int maximum, long skew, boolean requireExpiration);
    private static native boolean verifiedToken0(int policy, String token);
}

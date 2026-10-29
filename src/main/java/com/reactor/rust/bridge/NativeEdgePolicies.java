package com.reactor.rust.bridge;

/** Startup-only, opt-in edge registration. Requires a native artifact built with edge-cache. */
public final class NativeEdgePolicies {
    private NativeEdgePolicies() {}

    /**
     * Call generated Controller_EdgePolicies.install before registering routes. Public consent
     * means no Java authentication, tenant selection or business decision is required on a hit.
     * All policies are frozen together with the route table; late mutation fails closed.
     */
    public static void register(String method, String path, int redisClientId,
                                String versionKey, String dataKeyPrefix, String pathParam,
                                long maxAgeMs, boolean serveStale, int permitsPerSecond,
                                int burst, boolean publicEndpointConsent) {
        java.util.Objects.requireNonNull(method, "method");
        java.util.Objects.requireNonNull(path, "path");
        java.util.Objects.requireNonNull(versionKey, "versionKey");
        java.util.Objects.requireNonNull(dataKeyPrefix, "dataKeyPrefix");
        java.util.Objects.requireNonNull(pathParam, "pathParam");
        if (!publicEndpointConsent && !versionKey.isEmpty()) {
            throw new IllegalArgumentException("Native edge routes bypass Java guards; explicit public endpoint consent is required");
        }
        NativeLibraryLoader.load();
        try {
            register0(method, path, redisClientId, versionKey, dataKeyPrefix, pathParam,
                    maxAgeMs, serveStale, permitsPerSecond, burst);
        } catch (UnsatisfiedLinkError unavailable) {
            throw new IllegalStateException("Native edge policies require an edge-cache enabled native artifact; no bypass fallback was installed", unavailable);
        }
    }

    private static native void register0(String method, String path, int redisClientId,
                                         String versionKey, String dataKeyPrefix, String pathParam,
                                         long maxAgeMs, boolean serveStale, int permitsPerSecond, int burst);
}

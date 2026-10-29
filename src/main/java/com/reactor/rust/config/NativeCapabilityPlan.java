package com.reactor.rust.config;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.ServiceLoader;

/** Immutable startup plan for optional native surfaces. */
public final class NativeCapabilityPlan {

    public enum Capability {
        HTTP,
        WEBSOCKET,
        DUBBO,
        REDIS,
        GLOWROOT,
        EDGE_CACHE,
        EDGE_JWT,
        OUTBOUND_HTTP,
        SHARED_SLAB,
        FFM_FAST
    }

    private final Set<Capability> enabled;

    private NativeCapabilityPlan(Set<Capability> enabled) {
        this.enabled = Set.copyOf(enabled);
    }

    public static NativeCapabilityPlan fromProperties(boolean standardRuntime) {
        EnumSet<Capability> capabilities = EnumSet.of(Capability.HTTP);
        String explicit = PropertiesLoader.get("reactor.native.capabilities", "").trim();
        if (!explicit.isEmpty()) {
            for (String token : explicit.split(",")) {
                String normalized = token.trim();
                if (!normalized.isEmpty()) capabilities.add(parse(normalized));
            }
            if (PropertiesLoader.getBoolean("reactor.glowroot.enabled", false)
                    && !capabilities.contains(Capability.GLOWROOT)) {
                throw new IllegalStateException(
                        "reactor.glowroot.enabled=true requires 'glowroot' in "
                                + "reactor.native.capabilities"
                );
            }
        } else {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = NativeCapabilityPlan.class.getClassLoader();
            for (NativeCapabilityProvider provider : ServiceLoader.load(NativeCapabilityProvider.class, loader)) {
                if (provider.enabled()) capabilities.addAll(provider.capabilities());
            }
            if (standardRuntime && PropertiesLoader.getBoolean("reactor.websocket.enabled", true)) {
                capabilities.add(Capability.WEBSOCKET);
            }
            if (PropertiesLoader.getBoolean("reactor.dubbo.enabled", false)) {
                capabilities.add(Capability.DUBBO);
            }
            if (PropertiesLoader.getBoolean("reactor.glowroot.enabled", false)) {
                capabilities.add(Capability.GLOWROOT);
            }
        }
        return new NativeCapabilityPlan(capabilities);
    }

    public boolean enabled(Capability capability) {
        return enabled.contains(capability);
    }

    public Set<Capability> enabled() {
        return enabled;
    }

    /** Startup-only validation of the loaded binary's reported Cargo features. */
    public void validateBuildFeatures(String buildFeatures) {
        Set<String> features = java.util.Arrays.stream(buildFeatures.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT).replace('-', '_'))
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (Capability capability : enabled) {
            String feature = switch (capability) {
                case HTTP -> null;
                case WEBSOCKET -> "websocket";
                case DUBBO -> "dubbo";
                case REDIS -> "redis";
                case GLOWROOT -> "glowroot";
                case EDGE_CACHE -> "edge_cache";
                case EDGE_JWT -> "edge_jwt";
                case OUTBOUND_HTTP -> "outbound_http";
                case SHARED_SLAB -> "shared_slab";
                case FFM_FAST -> "ffm_fast";
            };
            if (feature != null && !features.contains(feature)) {
                throw new IllegalStateException("Native capability " + capability
                        + " is enabled but the loaded binary was built without " + feature
                        + ". Select a native artifact with that feature; no fallback is permitted.");
            }
        }
    }

    private static Capability parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "http", "rest" -> Capability.HTTP;
            case "websocket", "ws" -> Capability.WEBSOCKET;
            case "dubbo" -> Capability.DUBBO;
            case "redis", "cache" -> Capability.REDIS;
            case "glowroot", "telemetry" -> Capability.GLOWROOT;
            case "edge-cache", "edge_cache", "edge-rate-limit" -> Capability.EDGE_CACHE;
            case "edge-jwt", "edge_jwt" -> Capability.EDGE_JWT;
            case "outbound-http", "outbound_http" -> Capability.OUTBOUND_HTTP;
            case "shared-slab", "shared_slab" -> Capability.SHARED_SLAB;
            case "ffm-fast", "ffm_fast" -> Capability.FFM_FAST;
            default -> throw new IllegalArgumentException(
                    "Unsupported reactor.native.capabilities value: " + value);
        };
    }
}

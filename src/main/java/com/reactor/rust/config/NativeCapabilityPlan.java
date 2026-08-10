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
        REDIS
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
        }
        return new NativeCapabilityPlan(capabilities);
    }

    public boolean enabled(Capability capability) {
        return enabled.contains(capability);
    }

    public Set<Capability> enabled() {
        return enabled;
    }

    private static Capability parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "http", "rest" -> Capability.HTTP;
            case "websocket", "ws" -> Capability.WEBSOCKET;
            case "dubbo" -> Capability.DUBBO;
            case "redis", "cache" -> Capability.REDIS;
            default -> throw new IllegalArgumentException(
                    "Unsupported reactor.native.capabilities value: " + value);
        };
    }
}

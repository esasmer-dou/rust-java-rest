package com.reactor.rust.config;

import java.util.Set;

/** Build-time starter hook for native feature selection. */
public interface NativeCapabilityProvider {

    /** Allows an installed starter to stay dormant without contributing a native surface. */
    default boolean enabled() {
        return true;
    }

    Set<NativeCapabilityPlan.Capability> capabilities();
}

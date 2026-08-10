package com.reactor.rust.starter.dubbo;

import com.reactor.rust.config.NativeCapabilityPlan;
import com.reactor.rust.config.NativeCapabilityProvider;
import com.reactor.rust.config.PropertiesLoader;

import java.util.Set;

public final class DubboCapabilityProvider implements NativeCapabilityProvider {
    @Override
    public boolean enabled() {
        return PropertiesLoader.getBoolean("reactor.dubbo.enabled", false);
    }

    @Override
    public Set<NativeCapabilityPlan.Capability> capabilities() {
        return Set.of(NativeCapabilityPlan.Capability.DUBBO);
    }
}

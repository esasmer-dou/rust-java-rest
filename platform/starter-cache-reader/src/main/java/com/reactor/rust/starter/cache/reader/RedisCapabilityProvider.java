package com.reactor.rust.starter.cache.reader;

import com.reactor.rust.config.NativeCapabilityPlan;
import com.reactor.rust.config.NativeCapabilityProvider;
import com.reactor.rust.config.PropertiesLoader;

import java.util.Set;

public final class RedisCapabilityProvider implements NativeCapabilityProvider {
    @Override
    public boolean enabled() {
        return PropertiesLoader.getBoolean("reactor.cache.enabled", true);
    }

    @Override
    public Set<NativeCapabilityPlan.Capability> capabilities() {
        return Set.of(NativeCapabilityPlan.Capability.REDIS);
    }
}

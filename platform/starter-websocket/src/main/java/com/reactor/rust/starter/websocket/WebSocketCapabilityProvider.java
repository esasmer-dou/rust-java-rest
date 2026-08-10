package com.reactor.rust.starter.websocket;

import com.reactor.rust.config.NativeCapabilityPlan;
import com.reactor.rust.config.NativeCapabilityProvider;
import com.reactor.rust.config.PropertiesLoader;

import java.util.Set;

public final class WebSocketCapabilityProvider implements NativeCapabilityProvider {
    @Override
    public boolean enabled() {
        return PropertiesLoader.getBoolean("reactor.websocket.enabled", true);
    }

    @Override
    public Set<NativeCapabilityPlan.Capability> capabilities() {
        return Set.of(NativeCapabilityPlan.Capability.WEBSOCKET);
    }
}

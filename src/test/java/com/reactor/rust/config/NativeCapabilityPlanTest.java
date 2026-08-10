package com.reactor.rust.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCapabilityPlanTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("reactor.native.capabilities");
        System.clearProperty("reactor.websocket.enabled");
        System.clearProperty("reactor.dubbo.enabled");
    }

    @Test
    void explicitCapabilitiesReplaceAutomaticOptionalCapabilities() {
        System.setProperty("reactor.native.capabilities", "http");
        System.setProperty("reactor.websocket.enabled", "true");
        System.setProperty("reactor.dubbo.enabled", "true");

        NativeCapabilityPlan plan = NativeCapabilityPlan.fromProperties(true);

        assertTrue(plan.enabled(NativeCapabilityPlan.Capability.HTTP));
        assertFalse(plan.enabled(NativeCapabilityPlan.Capability.WEBSOCKET));
        assertFalse(plan.enabled(NativeCapabilityPlan.Capability.DUBBO));
        assertFalse(plan.enabled(NativeCapabilityPlan.Capability.REDIS));
    }

    @Test
    void legacyPropertiesStillEnableAutomaticCapabilities() {
        System.setProperty("reactor.native.capabilities", "");
        System.setProperty("reactor.websocket.enabled", "true");
        System.setProperty("reactor.dubbo.enabled", "true");

        NativeCapabilityPlan plan = NativeCapabilityPlan.fromProperties(true);

        assertTrue(plan.enabled(NativeCapabilityPlan.Capability.HTTP));
        assertTrue(plan.enabled(NativeCapabilityPlan.Capability.WEBSOCKET));
        assertTrue(plan.enabled(NativeCapabilityPlan.Capability.DUBBO));
    }
}

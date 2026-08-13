package com.reactor.rust.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCapabilityPlanTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("reactor.native.capabilities");
        System.clearProperty("reactor.websocket.enabled");
        System.clearProperty("reactor.dubbo.enabled");
        System.clearProperty("reactor.glowroot.enabled");
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
        assertFalse(plan.enabled(NativeCapabilityPlan.Capability.GLOWROOT));
    }

    @Test
    void legacyPropertiesStillEnableAutomaticCapabilities() {
        System.setProperty("reactor.native.capabilities", "");
        System.setProperty("reactor.websocket.enabled", "true");
        System.setProperty("reactor.dubbo.enabled", "true");
        System.setProperty("reactor.glowroot.enabled", "true");

        NativeCapabilityPlan plan = NativeCapabilityPlan.fromProperties(true);

        assertTrue(plan.enabled(NativeCapabilityPlan.Capability.HTTP));
        assertTrue(plan.enabled(NativeCapabilityPlan.Capability.WEBSOCKET));
        assertTrue(plan.enabled(NativeCapabilityPlan.Capability.DUBBO));
        assertTrue(plan.enabled(NativeCapabilityPlan.Capability.GLOWROOT));
    }

    @Test
    void explicitCapabilitiesFailFastWhenGlowrootWasOmitted() {
        System.setProperty("reactor.native.capabilities", "http");
        System.setProperty("reactor.glowroot.enabled", "true");

        assertThrows(IllegalStateException.class,
                () -> NativeCapabilityPlan.fromProperties(true));
    }
}

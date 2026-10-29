package com.reactor.rust.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCapabilityPlanTest {

    private static final String HISTORICAL_FEATURES = "default,dubbo,glowroot,redis,websocket";

    @Test
    void stockHistoricalArmFeatureSetRemainsAccepted() {
        System.setProperty("reactor.native.capabilities", "http,websocket,dubbo,redis,glowroot");
        NativeCapabilityPlan.fromProperties(true).validateBuildFeatures(HISTORICAL_FEATURES);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"edge-cache", "edge-rate-limit", "edge-jwt", "outbound-http", "shared-slab", "ffm-fast"})
    void historicalBinaryRejectsNewOptionalCapabilitiesBeforeRuntimeConfiguration(String capability) {
        System.setProperty("reactor.native.capabilities", "http," + capability);
        NativeCapabilityPlan plan = NativeCapabilityPlan.fromProperties(true);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> plan.validateBuildFeatures(HISTORICAL_FEATURES));
        assertTrue(failure.getMessage().contains("built without"));
        assertTrue(failure.getMessage().contains("no fallback"));
    }

    @Test
    void currentCargoFeatureSpellingsAreAcceptedButFfmIsNotImpliedBySharedSlab() {
        System.setProperty("reactor.native.capabilities", "http,edge-cache,edge-jwt,outbound-http,shared-slab");
        String features = HISTORICAL_FEATURES + ",edge_cache,edge_jwt,outbound_http,shared_slab";
        NativeCapabilityPlan.fromProperties(true).validateBuildFeatures(features);
        System.setProperty("reactor.native.capabilities", "http,ffm-fast");
        NativeCapabilityPlan plan = NativeCapabilityPlan.fromProperties(true);
        assertThrows(IllegalStateException.class, () -> plan.validateBuildFeatures(features));
        plan.validateBuildFeatures(features + ",ffm_fast");
    }

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

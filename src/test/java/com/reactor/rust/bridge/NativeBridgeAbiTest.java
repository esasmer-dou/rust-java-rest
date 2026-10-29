package com.reactor.rust.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeBridgeAbiTest {

    @Test
    void bundledNativeLibraryMatchesJavaAbi() {
        assertEquals(NativeBridge.EXPECTED_NATIVE_ABI_VERSION, NativeBridge.nativeAbiVersion());
    }

    @Test
    void bundledNativeBuildMatchesManifestProvenance() {
        NativeProvenance.BuildInfo buildInfo = NativeLibraryLoader.validateRuntimeProvenance(
                NativeBridge.nativeBuildInfo(),
                NativeBridge.EXPECTED_NATIVE_ABI_VERSION
        );

        String expectedRevision = System.getProperty("reactor.test.expected-native-revision");
        if (expectedRevision == null) {
            assertTrue(buildInfo.sourceRevision().matches("[a-f0-9]{40}"),
                    "Release verification requires a clean source revision");
        } else {
            // Unreleased source qualification must pin the actual binary, not waive provenance.
            String expectedHash = System.getProperty("reactor.test.expected-native-sha256", "");
            assertTrue(expectedRevision.matches("[a-f0-9]{40}(-dirty)?"));
            assertTrue(expectedHash.matches("[a-f0-9]{64}"), "Pin the qualification binary SHA-256");
            assertEquals(expectedRevision, buildInfo.sourceRevision());
            assertTrue(NativeBridge.nativeArtifactInfo().contains(expectedHash),
                    "Loaded native artifact differs from the pinned qualification binary");
        }
    }

    @Test
    void retainedNativeTrimAbiIsAvailable() {
        NativeBridge.releaseNativeMemoryRetaining(2, 0, 0, 0, false);
    }
}

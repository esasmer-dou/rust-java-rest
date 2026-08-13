package com.reactor.rust.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertEquals(40, buildInfo.sourceRevision().length());
    }

    @Test
    void retainedNativeTrimAbiIsAvailable() {
        NativeBridge.releaseNativeMemoryRetaining(2, 0, 0, 0, false);
    }
}

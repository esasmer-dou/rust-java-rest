package com.reactor.rust.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeBridgeAbiTest {

    @Test
    void bundledNativeLibraryMatchesJavaAbi() {
        assertEquals(NativeBridge.EXPECTED_NATIVE_ABI_VERSION, NativeBridge.nativeAbiVersion());
    }

    @Test
    void retainedNativeTrimAbiIsAvailable() {
        NativeBridge.releaseNativeMemoryRetaining(2, 0, 0, 0, false);
    }
}

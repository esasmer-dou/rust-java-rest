package com.reactor.rust.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeAsyncHandleCapabilityTest {
    @Test
    void optionalCapabilityKeepsOlderAbi29LibrariesUsable() throws Exception {
        var capability = Class.forName("com.reactor.rust.bridge.NativeBridge$NativeHandleCompletion")
                .getDeclaredField("SUPPORTED");
        capability.setAccessible(true);
        assertEquals(Boolean.parseBoolean(System.getProperty("reactor.test.native-handle-supported", "true")),
                capability.getBoolean(null));
    }
}

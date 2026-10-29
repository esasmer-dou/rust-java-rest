package com.reactor.rust.ffm;

import java.nio.ByteBuffer;

/** Small application workload for real Linux archive creation/restoration, not a latency benchmark. */
public final class FfmAppCdsProbe {
    public static void main(String[] args) {
        System.load(System.getProperty("reactor.ffm.test.library"));
        FfmFastBridge bridge = FfmFastBridge.tryActivate(FfmFastBridge.SLAB);
        if (bridge == null) throw new AssertionError("FFM activation failed: " + FfmFastBridge.activationFailure());
        try (var bytes = bridge.wrapBuffer(ByteBuffer.allocateDirect(8))) {
            long lease = bridge.acquire();
            if (lease == 0) throw new AssertionError("No lease");
            try {
                bytes.put(0, (byte) 42);
                require(bridge.copyIn(lease, 0, bytes, 1) == FfmFastBridge.OK);
                require(bridge.readByte(lease, 0) == 42);
                require(bridge.copyOut(lease, 0, bytes, 8) == FfmFastBridge.OK);
                require(bytes.get(0) == 42 && bytes.get(7) == 0);
            } finally { require(bridge.release(lease) == FfmFastBridge.OK); }
        }
        System.out.println("APP_CDS_FFM_PROBE=OK");
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Native FFM probe failed");
    }
}

package com.reactor.rust.ffm;

import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.internal.nativebridge.NativeRedisBridge;
import com.reactor.rust.bridge.NativeBridge;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Explicit distribution IT, compiled only against supplied cached cache/runtime jars. */
public final class FfmRedisDistributionProbe {
    public static void main(String[] args) {
        int client = NativeRedisBridge.createClient(RustCacheConfig.builder()
                .host(System.getProperty("reactor.ffm.redis.host", "127.0.0.1"))
                .port(Integer.getInteger("reactor.ffm.redis.port", 6379))
                .readConnections(1).writeConnections(1).maxReadInflight(1).maxWriteInflight(1)
                .maxResponseBytes(65_536).build());
        try {
            System.out.println("NATIVE_BUILD_INFO=" + NativeBridge.nativeBuildInfo());
            FfmFastBridge bridge = FfmFastBridge.tryActivateForRedisClient(client, 16);
            require(bridge != null, "Activation: " + FfmFastBridge.activationFailure());
            String key = "ffm-distribution-proof:" + System.nanoTime();
            byte[] expected = "{\"name\":\"\u00c7a\u011fr\u0131\",\"ready\":true}".getBytes(StandardCharsets.UTF_8);
            require(NativeRedisBridge.set(client, key, expected, 30_000), "Seed failed");
            check(bridge, client, key, expected, FfmFastBridge.OK);
            require(NativeRedisBridge.set(client, key, new byte[0], 30_000), "Empty seed failed");
            check(bridge, client, key, new byte[0], FfmFastBridge.OK);
            check(bridge, client, key + ":missing", new byte[0], FfmFastBridge.MISS);
            System.out.println("LIVE_REDIS_FFM=hit,turkish,empty,miss; existing-client=" + client);
        } finally { NativeRedisBridge.closeClient(client); }
        System.out.println("LIVE_REDIS_CLIENT_CLOSED");
    }

    private static void check(FfmFastBridge bridge, int client, String key, byte[] expected, int status) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bytes = ByteBuffer.allocateDirect(65_536);
        bytes.put(keyBytes).clear();
        try (var buffer = bridge.wrapBuffer(bytes)) {
            long lease = bridge.acquire();
            require(lease != 0, "No slot");
            try {
                require(bridge.copyIn(lease, 0, buffer, keyBytes.length) == FfmFastBridge.OK, "Key copy");
                long result = bridge.redisGet(lease, client, 0, keyBytes.length);
                require(FfmFastBridge.status(result) == status, "Status " + FfmFastBridge.status(result));
                require(FfmFastBridge.length(result) == expected.length, "Length");
                if (status == FfmFastBridge.OK) {
                    require(bridge.copyOut(lease, 0, buffer, expected.length) == FfmFastBridge.OK, "Value copy");
                    byte[] actual = new byte[expected.length];
                    bytes.get(actual);
                    require(Arrays.equals(expected, actual), "Value mismatch");
                }
            } finally { require(bridge.release(lease) == FfmFastBridge.OK, "Release"); }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package com.reactor.rust.ffm.cache;

import com.reactor.rust.cache.api.CacheReadResult;
import com.reactor.rust.cache.api.CacheReadSnapshot;
import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.internal.nativebridge.NativeRedisBridge;
import com.reactor.rust.ffm.FfmFastBridge;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** Explicit raw-GET adapter. Never replaces versioned atomic cache reads or replays a failed GET. */
public final class SharedSlabCacheReader implements AutoCloseable {
    private static final int CLOSED = 1 << 30;
    private final AtomicInteger lifecycle = new AtomicInteger();
    private final int client;
    private final int maxKeyBytes;
    private final FfmFastBridge bridge;
    private final boolean foreign;
    private final ByteBuffer[] keys;
    private final FfmFastBridge.Buffer[] inputs;
    private final ByteBuffer[] leases;
    private final FfmFastBridge.Buffer[] outputs;
    private final AtomicIntegerArray inputStates;

    private SharedSlabCacheReader(RustCacheConfig config, boolean requested, boolean required, int slots, int maxKeyBytes, boolean jniSlab) {
        if (maxKeyBytes < 1 || maxKeyBytes > 4096 || slots < 1 || slots > 32 || required && !requested)
            throw new IllegalArgumentException("Invalid slab reader limits/selection");
        this.maxKeyBytes = maxKeyBytes;
        Objects.requireNonNull(config, "config");
        if (!config.accessMode().readable()) throw new IllegalArgumentException("Cache reader requires read access");
        client = NativeRedisBridge.createClient(config);
        try {
            bridge = !requested ? null : jniSlab ? FfmFastBridge.trySharedSlabViaJni(client, slots)
                    : FfmFastBridge.tryActivateForRedisClient(
                    FfmFastBridge.SLAB | FfmFastBridge.REDIS | FfmFastBridge.BATCH_REDIS, client, slots);
            if (required && bridge == null) throw new IllegalStateException("Requested shared-slab transport unavailable: " + FfmFastBridge.activationFailure());
            foreign = bridge != null && bridge.foreignActive();
            keys = foreign ? new ByteBuffer[slots] : null;
            inputs = foreign ? new FfmFastBridge.Buffer[slots] : null;
            leases = bridge == null ? null : new ByteBuffer[slots];
            outputs = foreign ? new FfmFastBridge.Buffer[slots] : null;
            inputStates = bridge == null ? null : new AtomicIntegerArray(slots);
            if (bridge != null) for (int i = 0; i < slots; i++) {
                leases[i] = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
                if (foreign) {
                    keys[i] = ByteBuffer.allocateDirect(maxKeyBytes);
                    inputs[i] = bridge.wrapBuffer(keys[i]);
                    outputs[i] = bridge.wrapBuffer(leases[i]);
                }
            }
        } catch (RuntimeException | Error failure) {
            NativeRedisBridge.closeClient(client);
            throw failure;
        }
    }

    /** Selection is startup-only. Without preview/native access the existing JNI reader is selected. */
    public static SharedSlabCacheReader open(RustCacheConfig config, boolean requested, boolean required, int slots, int maxKeyBytes) {
        return new SharedSlabCacheReader(config, requested, required, slots, maxKeyBytes, false);
    }
    /** OpenJ9-safe shared owner with JNI transport; no FFM linker activation or preview requirement. */
    public static SharedSlabCacheReader openNativeSlab(RustCacheConfig config, boolean required, int slots, int maxKeyBytes) {
        return new SharedSlabCacheReader(config, true, required, slots, maxKeyBytes, true);
    }
    public boolean slabActive() { return bridge != null; }
    public boolean ffmActive() { return bridge != null && bridge.foreignActive(); }
    public long slabPayloadBytes() { return bridge == null ? 0 : bridge.payloadBytes(); }

    /** Own the returned snapshot until serialization completes; close it on every exit. */
    public CacheReadSnapshot read(String key) {
        enter();
        try {
            Objects.requireNonNull(key, "key");
            if (bridge == null || foreign) validateKey(key, maxKeyBytes);
            if (bridge == null) {
                byte[] value = NativeRedisBridge.get(client, key);
                return CacheReadSnapshot.takeOwnership(value == null ? CacheReadResult.miss() : CacheReadResult.hit(value));
            }
            int input = acquireInput();
            boolean transferred = false;
            boolean started = false;
            try {
                leases[input].putLong(0, 0);
                started = true;
                long result = foreign
                        ? bridge.redisAcquireGet(client, inputs[input], encode(key, keys[input]), outputs[input])
                        : bridge.redisAcquireGet(client, key, maxKeyBytes, leases[input]);
                int status = FfmFastBridge.status(result);
                if (!foreign && status == FfmFastBridge.BOUNDS)
                    throw new IllegalArgumentException("Key exceeds bounded UTF-8 input or has malformed UTF-16");
                if (status == FfmFastBridge.MISS) return CacheReadSnapshot.takeOwnership(CacheReadResult.miss());
                check(status, "Redis GET");
                long lease = leases[input].getLong(0);
                if (lease == 0) throw new IllegalStateException("Successful Redis GET did not transfer its lease");
                CacheReadSnapshot snapshot = new SlabSnapshot(bridge, lease, FfmFastBridge.length(result));
                transferred = true;
                return snapshot;
            } finally {
                try {
                    long lease = started ? leases[input].getLong(0) : 0;
                    if (!transferred && lease != 0) check(bridge.release(lease), "release after failed read");
                } finally { inputStates.set(input, 0); }
            }
        } finally { leave(); }
    }

    private int acquireInput() {
        for (int i = 0; i < inputStates.length(); i++) if (inputStates.compareAndSet(i, 0, 1)) return i;
        throw new FfmReadException(FfmFastBridge.BUSY, "Shared slab input capacity");
    }

    private static void check(int status, String operation) {
        if (status != FfmFastBridge.OK) throw new FfmReadException(status, operation);
    }
    private void enter() {
        for (;;) {
            int state = lifecycle.get();
            if ((state & CLOSED) != 0) throw new IllegalStateException("Cache reader is closed");
            if (state >= CLOSED - 1) throw new IllegalStateException("Too many concurrent reads");
            if (lifecycle.compareAndSet(state, state + 1)) return;
        }
    }
    private void leave() { if (lifecycle.decrementAndGet() == CLOSED) destroy(); }
    @Override public void close() {
        int previous = lifecycle.getAndUpdate(value -> value | CLOSED);
        if (previous == 0) destroy();
    }
    private void destroy() {
        try { NativeRedisBridge.closeClient(client); }
        finally {
            if (leases != null) for (int i = 0; i < leases.length; i++) {
                if (foreign) {
                    inputs[i].close(); outputs[i].close();
                    inputs[i] = null; outputs[i] = null; keys[i] = null;
                }
                leases[i] = null;
            }
        }
    }

    static int validateKey(String value, int maximum) {
        Objects.requireNonNull(value, "key");
        if (value.isEmpty() || value.length() > maximum) throw new IllegalArgumentException("Key exceeds bounded UTF-8 input");
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException("Malformed UTF-16 key");
                bytes += 4;
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Malformed UTF-16 key");
            else bytes += c < 128 ? 1 : c < 2048 ? 2 : 3;
            if (bytes > maximum) throw new IllegalArgumentException("Key exceeds bounded UTF-8 input");
        }
        return bytes;
    }
    private static int encode(String value, ByteBuffer out) {
        int at = 0;
        for (int i = 0; i < value.length(); i++) {
            int c = value.charAt(i);
            if (c < 128) out.put(at++, (byte) c);
            else if (c < 2048) { out.put(at++, (byte) (0xc0 | c >>> 6)); out.put(at++, (byte) (0x80 | c & 63)); }
            else if (Character.isHighSurrogate((char) c)) {
                c = Character.toCodePoint((char) c, value.charAt(++i));
                out.put(at++, (byte) (0xf0 | c >>> 18)); out.put(at++, (byte) (0x80 | c >>> 12 & 63));
                out.put(at++, (byte) (0x80 | c >>> 6 & 63)); out.put(at++, (byte) (0x80 | c & 63));
            } else {
                out.put(at++, (byte) (0xe0 | c >>> 12)); out.put(at++, (byte) (0x80 | c >>> 6 & 63)); out.put(at++, (byte) (0x80 | c & 63));
            }
        }
        return at;
    }
    private static final class SlabSnapshot extends CacheReadSnapshot {
        private final FfmFastBridge bridge;
        private final long lease;
        SlabSnapshot(FfmFastBridge bridge, long lease, int length) {
            super(CacheReadResult.Status.HIT, null, length);
            this.bridge = bridge; this.lease = lease;
        }
        @Override protected void copyTo(ByteBuffer out, int offset) {
            check(bridge.copyOut(lease, 0, out, offset, length()), "response copy");
        }
        @Override protected void release() { check(bridge.release(lease), "snapshot release"); }
    }
}

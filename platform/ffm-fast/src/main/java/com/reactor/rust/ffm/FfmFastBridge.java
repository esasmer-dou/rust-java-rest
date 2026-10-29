package com.reactor.rust.ffm;

import java.nio.ByteBuffer;

/**
 * Explicit startup selection, never referenced by core. This entry class uses no
 * preview API. The JVM enforces preview loading; PanamaAccess checks module access
 * before creating any linker. No management or command-line inspection is needed.
 * A null selection means choose the existing JNI path for this class loader's
 * lifetime. Once active, errors/statuses NEVER trigger JNI fallback or replay.
 * The native slab is shared process-wide and remains allocated until process exit.
 */
public final class FfmFastBridge {
    public static final int SLAB = 1, GLOWROOT = 2, REDIS = 4, BATCH_REDIS = 8;
    public static final int OK = 0, MISS = 1, BUSY = 2, STALE = 3, BOUNDS = 4,
            UNSUPPORTED = 5, UNKNOWN_CLIENT = 6, BACKEND_ERROR = 7, TOO_LARGE = 8, PANIC = 9, TIMEOUT = 10;
    public static final int SLOT_BYTES = 65_536;
    private static boolean decided;
    private static FfmFastBridge active;
    private static String activationFailure;
    private static FfmFastBridge jniSlab;
    private final Calls calls;
    private final int capabilities;
    private final int activeSlots;
    private final int redisClientId;

    private FfmFastBridge(Calls calls, int required, int activeSlots, int redisClientId) {
        this.calls = calls;
        this.capabilities = required;
        this.activeSlots = activeSlots;
        this.redisClientId = redisClientId;
    }

    public static FfmFastBridge tryActivate(int requiredCapabilities) {
        return tryActivate(requiredCapabilities, 16);
    }

    /** Startup only. Missing flags/features select JNI BEFORE any business operation. */
    public static FfmFastBridge tryActivate(int requiredCapabilities, int activeSlots) {
        if ((requiredCapabilities & REDIS) != 0)
            throw new IllegalArgumentException("REDIS requires tryActivateForRedisClient before business selection");
        return select(requiredCapabilities, activeSlots, 0);
    }

    /** Checks this existing client's read capability and <=64KiB response limit BEFORE slab allocation. */
    public static FfmFastBridge tryActivateForRedisClient(int clientId, int activeSlots) {
        return tryActivateForRedisClient(SLAB | REDIS, clientId, activeSlots);
    }

    public static FfmFastBridge tryActivateForRedisClient(int requiredCapabilities, int clientId, int activeSlots) {
        if ((requiredCapabilities & REDIS) == 0) throw new IllegalArgumentException("REDIS capability is required");
        return select(requiredCapabilities, activeSlots, clientId);
    }

    private static synchronized FfmFastBridge select(int requiredCapabilities, int activeSlots, int redisClientId) {
        if ((requiredCapabilities & SLAB) == 0 || (requiredCapabilities & ~15) != 0
                || (requiredCapabilities & BATCH_REDIS) != 0 && (requiredCapabilities & REDIS) == 0
                || activeSlots < 1 || activeSlots > 32) throw new IllegalArgumentException("Invalid FFM capabilities/capacity");
        if (decided) {
            if (active != null && ((active.capabilities & requiredCapabilities) != requiredCapabilities
                    || active.activeSlots != activeSlots || ((requiredCapabilities & REDIS) != 0
                    && active.redisClientId != redisClientId))) {
                throw new IllegalStateException("FFM is already active; reconfiguration/fallback is forbidden");
            }
            return active;
        }
        decided = true;
        if (Runtime.version().feature() == 21 && System.getProperty("java.vm.name", "").contains("OpenJ9")) {
            // OpenJ9 21 retains per-downcall HeapArgInfo stacks. Never enter its linker.
            activationFailure = "OpenJ9 21 FFM downcall scope retention; use JNI shared slab";
            return null;
        }
        try {
            active = new FfmFastBridge(PanamaAccess.open(requiredCapabilities, activeSlots, redisClientId),
                    requiredCapabilities, activeSlots, redisClientId);
            return active;
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable unavailable) {
            // Preflight only: no operation was executed. Covers unsupported OpenJ9
            // linkers, absent preview/classes/symbols and unavailable native access.
            activationFailure = unavailable.getClass().getName();
            return null;
        }
    }

    public static synchronized String activationFailure() { return activationFailure; }
    /** Explicit alternative transport, selected before any business read; never an operation retry. */
    public static synchronized FfmFastBridge trySharedSlabViaJni(int client, int slots) {
        if (slots < 1 || slots > 32) throw new IllegalArgumentException("Invalid slab capacity");
        if (active != null) throw new IllegalStateException("FFM already active; transport switch forbidden");
        if (jniSlab != null) {
            if (jniSlab.redisClientId != client || jniSlab.activeSlots != slots)
                throw new IllegalStateException("JNI slab already configured");
            return jniSlab;
        }
        try {
            Calls calls = JniSlabAccess.open(client, slots);
            jniSlab = new FfmFastBridge(calls, SLAB | REDIS | BATCH_REDIS, slots, client);
            decided = true;
            return jniSlab;
        } catch (LinkageError | RuntimeException unavailable) {
            activationFailure = unavailable.getClass().getName() + ": " + unavailable.getMessage();
            return null;
        }
    }
    public boolean foreignActive() { return !(calls instanceof JniSlabAccess); }
    public int activeSlots() { return activeSlots; }
    public long payloadBytes() { return (long) activeSlots * SLOT_BYTES; }
    public long acquire() { return calls.acquire(); }
    public int release(long lease) { return calls.release(lease); }
    /** Returns unsigned byte 0..255, or negative status. */
    public int readByte(long lease, int offset) { return calls.readByte(lease, offset); }
    public int writeByte(long lease, int offset, int value) { return calls.writeByte(lease, offset, value); }
    /** Wrap once and reuse. Allocates a wrapper, NOT another native payload buffer. */
    public Buffer wrapBuffer(ByteBuffer bytes) { return calls.wrapBuffer(bytes, bytes); }
    /** Retains an external native owner through each access/copy, including concurrent wrapper close. */
    public Buffer wrapBuffer(ByteBuffer bytes, Object nativeOwner) { return calls.wrapBuffer(bytes, nativeOwner); }
    public int copyIn(long lease, int offset, Buffer source, int length) { return calls.copyIn(lease, offset, source, length); }
    public int copyOut(long lease, int offset, Buffer target, int length) { return calls.copyOut(lease, offset, target, length); }
    /** Copies to an existing native response buffer; no NewDirectByteBuffer or payload array is created. */
    public int copyOut(long lease, int offset, ByteBuffer target, int targetOffset, int length) {
        return calls.copyOut(lease, offset, target, targetOffset, length);
    }

    /** Native operation counters/slow traces, NOT Java Throwable detail capture. */
    public int recordOperation(int metricSlot, long durationNanos, boolean error) {
        if ((capabilities & GLOWROOT) == 0) return UNSUPPORTED;
        if (metricSlot < 0) return OK;
        if (metricSlot > 65_535) return BOUNDS;
        return calls.recordOperation(metricSlot, Math.max(0, durationNanos), error);
    }

    /** Full key already in this lease; successful GET overwrites offset zero. NEVER retried here. */
    public long redisGet(long lease, int clientId, int keyOffset, int keyLength) {
        if ((capabilities & REDIS) == 0 || clientId != redisClientId) return (long) UNSUPPORTED << 32;
        return calls.redisGet(lease, clientId, keyOffset, keyLength);
    }
    /** Caller key/output buffers are bounded and reusable. This never replays through JNI. */
    public long redisAcquireGet(int clientId, Buffer key, int keyLength, Buffer leaseOut) {
        if ((capabilities & BATCH_REDIS) == 0 || clientId != redisClientId) return (long) UNSUPPORTED << 32;
        return calls.redisAcquireGet(clientId, key, keyLength, leaseOut);
    }
    /** JNI-only bounded String input. UTF-8 conversion stays in native stack storage. */
    public long redisAcquireGet(int clientId, String key, int maxKeyBytes, ByteBuffer leaseOut) {
        if (clientId != redisClientId || !(calls instanceof JniSlabAccess jni))
            return (long) UNSUPPORTED << 32;
        return jni.readString(clientId, key, maxKeyBytes, leaseOut);
    }

    public static int status(long result) { return (int) (result >>> 32); }
    public static int length(long result) { return (int) result; }

    /**
     * Caller-owned direct storage. No slab address/segment can escape here. For
     * externally owned memory use the owner overload, and never explicitly free
     * that owner or mutate/reuse input bytes until ALL accesses/copies finish.
     * Closing detaches this wrapper; it does not free caller storage or revoke
     * an already admitted copy. Reachability fences prevent premature GC cleanup,
     * not an owner's explicit close/free or concurrent caller writes.
     */
    public interface Buffer extends AutoCloseable {
        int capacity();
        byte get(int index);
        void put(int index, byte value);
        @Override void close();
    }

    interface Calls {
        long acquire();
        int release(long lease);
        int readByte(long lease, int offset);
        int writeByte(long lease, int offset, int value);
        Buffer wrapBuffer(ByteBuffer bytes, Object nativeOwner);
        int copyIn(long lease, int offset, Buffer source, int length);
        int copyOut(long lease, int offset, Buffer target, int length);
        int copyOut(long lease, int offset, ByteBuffer target, int targetOffset, int length);
        int recordOperation(int metricSlot, long durationNanos, boolean error);
        long redisGet(long lease, int clientId, int keyOffset, int keyLength);
        long redisAcquireGet(int clientId, Buffer key, int keyLength, Buffer leaseOut);
    }
}

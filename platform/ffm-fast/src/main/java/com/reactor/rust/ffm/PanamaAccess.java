package com.reactor.rust.ffm;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Preview implementation, loaded only by explicit successful preflight. */
final class PanamaAccess implements FfmFastBridge.Calls {
    private final SymbolLookup libraryLifetime;
    private final MemorySegment slabLifetime;
    private final MethodHandle acquire, release, read, write, copyIn, copyOut, record, redis, redisAcquire;

    static FfmFastBridge.Calls open(int required, int slots, int redisClientId) throws Throwable {
        if (!PanamaAccess.class.getModule().isNativeAccessEnabled()) {
            throw new IllegalCallerException("Native access is not enabled");
        }
        return new PanamaAccess(required, slots, redisClientId);
    }

    private PanamaAccess(int required, int slots, int redisClientId) throws Throwable {
        libraryLifetime = SymbolLookup.loaderLookup();
        Linker linker = Linker.nativeLinker();
        MethodHandle version = leaf(linker, "reactor_ffm_api_version", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        MethodHandle capabilities = leaf(linker, "reactor_ffm_capabilities", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        if ((int) version.invokeExact() != 1 || (((int) capabilities.invokeExact()) & required) != required) {
            throw new UnsupportedOperationException("Missing FFM capability or unwind support");
        }
        if ((required & FfmFastBridge.REDIS) != 0) {
            MethodHandle client = bind(linker, "reactor_redis_client_status",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            int status = (int) client.invokeExact(redisClientId);
            if (status != FfmFastBridge.OK) throw new UnsupportedOperationException("Redis client preflight status=" + status);
        }
        MethodHandle total = leaf(linker, "reactor_slab_total_bytes", FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        MethodHandle configure = bind(linker, "reactor_slab_configure", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MethodHandle base = bind(linker, "reactor_slab_base_ptr", FunctionDescriptor.of(ValueLayout.ADDRESS));
        acquire = bind(linker, "reactor_slab_acquire", FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        release = bind(linker, "reactor_slab_release", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        read = bind(linker, "reactor_slab_read_byte", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        write = bind(linker, "reactor_slab_write_byte", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        var copy = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
        copyIn = bind(linker, "reactor_slab_copy_in", copy);
        copyOut = bind(linker, "reactor_slab_copy_out", copy);
        // Full operation/trace semantics and blocking Redis are NEVER trivial calls.
        record = (required & FfmFastBridge.GLOWROOT) == 0 ? null : bind(linker,
                "reactor_glowroot_record_op_fast", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_SHORT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        redis = (required & FfmFastBridge.REDIS) == 0 ? null : bind(linker,
                "reactor_redis_get_into_slot", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        redisAcquire = (required & FfmFastBridge.BATCH_REDIS) == 0 ? null : bind(linker,
                "reactor_redis_acquire_get", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        if ((int) configure.invokeExact(slots) != FfmFastBridge.OK) throw new UnsupportedOperationException("Slab capacity already frozen");
        long bytes = (long) total.invokeExact();
        if (bytes != (long) slots * FfmFastBridge.SLOT_BYTES) throw new UnsupportedOperationException("Unexpected slab size");
        MemorySegment pointer = (MemorySegment) base.invokeExact();
        if (pointer.address() == 0) throw new UnsupportedOperationException("Slab allocation failed");
        slabLifetime = pointer.reinterpret(bytes);
    }

    private MethodHandle bind(Linker linker, String symbol, FunctionDescriptor descriptor) {
        return linker.downcallHandle(libraryLifetime.find(symbol).orElseThrow(), descriptor);
    }

    private MethodHandle leaf(Linker linker, String symbol, FunctionDescriptor descriptor) {
        // Only tiny metadata getters. JDK21 isTrivial(), NOT newer critical().
        MemorySegment address = libraryLifetime.find(symbol).orElseThrow();
        try {
            return linker.downcallHandle(address, descriptor, Linker.Option.isTrivial());
        } catch (UnsupportedOperationException unsupportedHint) {
            // Link-time selection only: no operation has run, so this is not replay.
            return linker.downcallHandle(address, descriptor);
        }
    }

    @Override public long acquire() {
        try { return (long) acquire.invokeExact(); } catch (Throwable failure) { throw failed(failure); }
    }
    @Override public int release(long lease) {
        try { return (int) release.invokeExact(lease); } catch (Throwable failure) { throw failed(failure); }
    }
    @Override public int readByte(long lease, int offset) {
        try { return (int) read.invokeExact(lease, offset); } catch (Throwable failure) { throw failed(failure); }
    }
    @Override public int writeByte(long lease, int offset, int value) {
        try { return (int) write.invokeExact(lease, offset, value); } catch (Throwable failure) { throw failed(failure); }
    }
    @Override public FfmFastBridge.Buffer wrapBuffer(ByteBuffer bytes, Object nativeOwner) {
        if (bytes == null || !bytes.isDirect() || bytes.remaining() < 1 || bytes.remaining() > FfmFastBridge.SLOT_BYTES)
            throw new IllegalArgumentException("Caller buffer must be direct with 1..65536 remaining bytes");
        return new SegmentBuffer(bytes, Objects.requireNonNull(nativeOwner, "nativeOwner"));
    }
    @Override public int copyIn(long lease, int offset, FfmFastBridge.Buffer source, int length) {
        if (!(source instanceof SegmentBuffer buffer)) return FfmFastBridge.BOUNDS;
        Object owner = buffer.owner();
        try {
            MemorySegment segment = buffer.bound();
            if (length < 0 || length > segment.byteSize()) return FfmFastBridge.BOUNDS;
            return (int) copyIn.invokeExact(lease, offset, segment, length);
        } catch (Throwable failure) { throw failed(failure); }
        finally { Reference.reachabilityFence(owner); }
    }
    @Override public int copyOut(long lease, int offset, FfmFastBridge.Buffer target, int length) {
        if (!(target instanceof SegmentBuffer buffer)) return FfmFastBridge.BOUNDS;
        Object owner = buffer.owner();
        try {
            MemorySegment segment = buffer.bound();
            if (segment.isReadOnly() || length < 0 || length > segment.byteSize()) return FfmFastBridge.BOUNDS;
            return (int) copyOut.invokeExact(lease, offset, segment, length);
        } catch (Throwable failure) { throw failed(failure); }
        finally { Reference.reachabilityFence(owner); }
    }
    @Override public int recordOperation(int slot, long nanos, boolean error) {
        try { return (int) record.invokeExact((short) slot, nanos, error ? 1 : 0); } catch (Throwable failure) { throw failed(failure); }
    }
    @Override public int copyOut(long lease, int offset, ByteBuffer target, int targetOffset, int length) {
        Objects.requireNonNull(target, "target");
        if (!target.isDirect() || target.isReadOnly() || length < 0 || targetOffset < target.position()
                || targetOffset > target.limit() || length > target.limit() - targetOffset) {
            return FfmFastBridge.BOUNDS;
        }
        // The caller owns target through this synchronous call. Nothing escapes to a callback.
        MemorySegment segment = MemorySegment.ofBuffer(target).asSlice(targetOffset - target.position(), length);
        try { return (int) copyOut.invokeExact(lease, offset, segment, length); }
        catch (Throwable failure) { throw failed(failure); }
        finally { Reference.reachabilityFence(target); }
    }
    @Override public long redisGet(long lease, int client, int offset, int length) {
        try { return (long) redis.invokeExact(lease, client, offset, length); } catch (Throwable failure) { throw failed(failure); }
    }
    @Override public long redisAcquireGet(int client, FfmFastBridge.Buffer key, int length, FfmFastBridge.Buffer result) {
        if (!(key instanceof SegmentBuffer input) || !(result instanceof SegmentBuffer output)) return (long) FfmFastBridge.BOUNDS << 32;
        Object inputOwner = input.owner(), outputOwner = output.owner();
        try {
            MemorySegment source = input.bound(), target = output.bound();
            if (length < 1 || length > 4096 || length > source.byteSize() || target.byteSize() < 8
                    || target.isReadOnly()) return (long) FfmFastBridge.BOUNDS << 32;
            long keyStart = source.address(), resultStart = target.address();
            if (keyStart < resultStart + 8 && resultStart < keyStart + length) return (long) FfmFastBridge.BOUNDS << 32;
            return (long) redisAcquire.invokeExact(client, source, length, target);
        } catch (Throwable failure) { throw failed(failure); }
        finally { Reference.reachabilityFence(inputOwner); Reference.reachabilityFence(outputOwner); }
    }

    private static RuntimeException failed(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) return error;
        // A post-activation linkage failure must propagate, never select/replay JNI.
        return new IllegalStateException("Active FFM call failed; JNI fallback forbidden", failure);
    }

    private static final class SegmentBuffer implements FfmFastBridge.Buffer {
        private volatile MemorySegment segment;
        private volatile Object owner;
        SegmentBuffer(ByteBuffer bytes, Object owner) {
            this.segment = MemorySegment.ofBuffer(bytes);
            this.owner = owner;
        }
        Object owner() {
            Object value = owner;
            if (value == null) throw new IllegalStateException("Caller buffer wrapper is closed");
            return value;
        }
        MemorySegment bound() {
            MemorySegment value = segment;
            if (value == null) throw new IllegalStateException("Caller buffer wrapper is closed");
            return value;
        }
        @Override public int capacity() { return (int) bound().byteSize(); }
        @Override public byte get(int index) {
            Object retained = owner();
            try { return bound().get(ValueLayout.JAVA_BYTE, index); }
            finally { Reference.reachabilityFence(retained); }
        }
        @Override public void put(int index, byte value) {
            Object retained = owner();
            try { bound().set(ValueLayout.JAVA_BYTE, index, value); }
            finally { Reference.reachabilityFence(retained); }
        }
        @Override public void close() {
            // Readers retain owner BEFORE reading segment; detach in reverse order.
            segment = null;
            owner = null;
        }
    }
}

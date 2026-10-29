package com.reactor.rust.ffm;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Ordinary Java 21 transport for the same native owner; no foreign API or VM internals. */
final class JniSlabAccess implements FfmFastBridge.Calls {
    static JniSlabAccess open(int client, int slots) {
        if (FfmFastBridge.status(nativeReadString(0, null, 0, null)) != FfmFastBridge.BOUNDS)
            throw new IllegalStateException("JNI string-slab preflight failed");
        int status = nativeOpen(client, slots);
        if (status != FfmFastBridge.OK) throw new IllegalStateException("JNI slab preflight status=" + status);
        return new JniSlabAccess();
    }
    private static native int nativeOpen(int client, int slots);
    private static native long nativeRead(int client, ByteBuffer key, int length, ByteBuffer lease);
    private static native long nativeReadString(int client, String key, int maximum, ByteBuffer lease);
    private static native int nativeCopy(long lease, ByteBuffer output, int offset, int length);
    private static native int nativeRelease(long lease);
    long readString(int client, String key, int maximum, ByteBuffer output) {
        if (!output.isDirect() || output.isReadOnly() || output.position() != 0 || output.remaining() < 8)
            return (long) FfmFastBridge.BOUNDS << 32;
        try { return nativeReadString(client, key, maximum, output); }
        finally { Reference.reachabilityFence(output); }
    }
    @Override public long acquire() { throw unsupported(); }
    @Override public int release(long lease) { return nativeRelease(lease); }
    @Override public int readByte(long lease, int offset) { throw unsupported(); }
    @Override public int writeByte(long lease, int offset, int value) { throw unsupported(); }
    @Override public int copyIn(long lease, int offset, FfmFastBridge.Buffer source, int length) { throw unsupported(); }
    @Override public int copyOut(long lease, int offset, FfmFastBridge.Buffer target, int length) { throw unsupported(); }
    @Override public int recordOperation(int slot, long nanos, boolean error) { throw unsupported(); }
    @Override public long redisGet(long lease, int client, int offset, int length) { throw unsupported(); }
    @Override public FfmFastBridge.Buffer wrapBuffer(ByteBuffer bytes, Object owner) {
        if (!bytes.isDirect() || bytes.remaining() < 1 || bytes.remaining() > FfmFastBridge.SLOT_BYTES)
            throw new IllegalArgumentException("Bounded direct input required");
        return new Input(bytes.slice(), Objects.requireNonNull(owner, "owner"));
    }
    @Override public long redisAcquireGet(int client, FfmFastBridge.Buffer key, int length, FfmFastBridge.Buffer result) {
        if (!(key instanceof Input in) || !(result instanceof Input out) || length < 1 || length > 4096)
            return (long) FfmFastBridge.BOUNDS << 32;
        Object keyOwner = in.owner(), outOwner = out.owner();
        try {
            ByteBuffer source = in.bytes(), target = out.bytes();
            if (source.remaining() < length || target.remaining() < 8 || target.isReadOnly())
                return (long) FfmFastBridge.BOUNDS << 32;
            return nativeRead(client, source, length, target);
        } finally { Reference.reachabilityFence(keyOwner); Reference.reachabilityFence(outOwner); }
    }
    @Override public int copyOut(long lease, int offset, ByteBuffer target, int targetOffset, int length) {
        if (offset != 0 || !target.isDirect() || target.isReadOnly() || length < 0 || targetOffset < target.position()
                || targetOffset > target.limit() || length > target.limit() - targetOffset) return FfmFastBridge.BOUNDS;
        try { return nativeCopy(lease, target, targetOffset, length); }
        finally { Reference.reachabilityFence(target); }
    }
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("JNI slab supports managed Redis reads/copy/release only");
    }
    private static final class Input implements FfmFastBridge.Buffer {
        private volatile ByteBuffer bytes;
        private volatile Object owner;
        Input(ByteBuffer bytes, Object owner) { this.bytes = bytes; this.owner = owner; }
        Object owner() { return Objects.requireNonNull(owner, "Input closed"); }
        ByteBuffer bytes() { return Objects.requireNonNull(bytes, "Input closed"); }
        @Override public int capacity() { return bytes().capacity(); }
        @Override public byte get(int index) {
            Object retained = owner();
            try { return bytes().get(index); } finally { Reference.reachabilityFence(retained); }
        }
        @Override public void put(int index, byte value) {
            Object retained = owner();
            try { bytes().put(index, value); } finally { Reference.reachabilityFence(retained); }
        }
        @Override public void close() { bytes = null; owner = null; }
    }
}

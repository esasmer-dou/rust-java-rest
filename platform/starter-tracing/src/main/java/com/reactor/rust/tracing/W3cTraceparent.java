package com.reactor.rust.tracing;

import java.util.Arrays;
import java.util.Objects;
import java.nio.charset.StandardCharsets;

/** Fixed-prefix W3C traceparent codec. No shared mutable buffers or per-write allocations. */
public final class W3cTraceparent {
    public static final int LENGTH = 55;

    private static final int[] HEX_NIBBLE = new int[256];
    private static final byte[] HEX_BYTES = new byte[256];

    static {
        Arrays.fill(HEX_NIBBLE, -1);
        for (int nibble = 0; nibble < 16; nibble++) {
            int character = nibble < 10 ? '0' + nibble : 'a' + nibble - 10;
            HEX_NIBBLE[character] = nibble;
            HEX_BYTES[character] = (byte) character;
        }
    }

    private W3cTraceparent() {}

    /**
     * Writes exactly 55 ASCII bytes at {@code offset}, returning the number of bytes written.
     * Only the sampled bit is emitted; this implementation writes version {@code 00}.
     *
     * @throws NullPointerException if context or target is null
     * @throws IndexOutOfBoundsException if the complete header does not fit
     * @throws IllegalArgumentException if either context ID is not nonzero lowercase hex
     */
    public static int write(TraceContext context, byte[] target, int offset) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(target, "target");
        if (offset < 0 || offset > target.length - LENGTH) {
            throw new IndexOutOfBoundsException("traceparent requires 55 bytes at offset");
        }
        String traceId = context.traceId();
        String spanId = context.spanId();
        if (traceId.length() != 32 || spanId.length() != 16
                || !validId(traceId, 0, 32) || !validId(spanId, 0, 16)) {
            throw new IllegalArgumentException("traceparent IDs must be nonzero lowercase hex");
        }
        target[offset] = '0';
        target[offset + 1] = '0';
        target[offset + 2] = '-';
        for (int index = 0; index < 32; index++) {
            target[offset + 3 + index] = HEX_BYTES[traceId.charAt(index)];
        }
        target[offset + 35] = '-';
        for (int index = 0; index < 16; index++) {
            target[offset + 36 + index] = HEX_BYTES[spanId.charAt(index)];
        }
        target[offset + 52] = '-';
        target[offset + 53] = '0';
        target[offset + 54] = context.sampled() ? (byte) '1' : (byte) '0';
        return LENGTH;
    }

    /**
     * Returns the remote context (its span ID is the incoming parent ID), or null for invalid input.
     * Version 00 must be exactly 55 characters. Higher versions must have the same valid prefix
     * followed by end-of-input or a dash; unknown extension fields are deliberately not interpreted
     * or retained. Parsing materializes only the two fixed-length IDs, not the extension.
     */
    public static TraceContext parse(String header) {
        if (header == null || header.length() < LENGTH
                || header.charAt(2) != '-' || header.charAt(35) != '-' || header.charAt(52) != '-') {
            return null;
        }
        int version = hexByte(header, 0);
        if (version < 0 || version == 255
                || (version == 0 && header.length() != LENGTH)
                || (header.length() > LENGTH && header.charAt(LENGTH) != '-')) {
            return null;
        }
        int flags = hexByte(header, 53);
        if (flags < 0 || !validId(header, 3, 32) || !validId(header, 36, 16)) return null;
        return new TraceContext(copyId(header, 3, 32), copyId(header, 36, 16), (flags & 1) != 0);
    }

    private static int hexByte(String value, int offset) {
        int high = nibble(value.charAt(offset));
        int low = nibble(value.charAt(offset + 1));
        return high < 0 || low < 0 ? -1 : (high << 4) | low;
    }

    private static int nibble(char character) {
        return character < HEX_NIBBLE.length ? HEX_NIBBLE[character] : -1;
    }

    private static boolean validId(String value, int offset, int length) {
        int nonzero = 0;
        for (int index = offset; index < offset + length; index++) {
            int nibble = nibble(value.charAt(index));
            if (nibble < 0) return false;
            nonzero |= nibble;
        }
        return nonzero != 0;
    }

    private static String copyId(String header, int offset, int length) {
        // The prefix was already validated as ASCII. Never retain the remote header or its extensions.
        byte[] characters = new byte[length];
        for (int index = 0; index < length; index++) {
            characters[index] = (byte) header.charAt(offset + index);
        }
        return new String(characters, StandardCharsets.US_ASCII);
    }
}

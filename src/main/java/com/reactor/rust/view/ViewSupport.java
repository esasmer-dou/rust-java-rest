package com.reactor.rust.view;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

/** Stateless helpers used by generated code; all multibyte values use big endian. */
public final class ViewSupport {
    private ViewSupport() {}

    public static void checkRange(ByteBuffer buffer, int offset, int length) {
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        if (offset < 0 || length < 0 || offset > buffer.limit() - length) {
            throw new IndexOutOfBoundsException("Buffer range exceeds limit");
        }
    }

    public static int remaining(ByteBuffer buffer, int offset) {
        checkRange(buffer, offset, 0);
        return buffer.limit() - offset;
    }

    public static void checkWritable(ByteBuffer buffer, int offset, int length) {
        checkRange(buffer, offset, length);
        if (buffer.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
    }

    public static int addSize(int left, int right) {
        if (left < 0 || right < 0 || left > Integer.MAX_VALUE - right) {
            throw new IllegalArgumentException("View size overflow");
        }
        return left + right;
    }

    public static int descriptorEnd(int relative, int length, int header, int recordLength) {
        if (relative < header || length < 0 || relative > recordLength - length) {
            throw new IllegalArgumentException("Descriptor outside record tail");
        }
        return relative + length;
    }

    public static long read(ByteBuffer buffer, int offset, int width) {
        checkRange(buffer, offset, width);
        long value = 0;
        for (int i = 0; i < width; i++) {
            value = (value << 8) | (buffer.get(offset + i) & 255L);
        }
        return value;
    }

    public static void write(ByteBuffer buffer, int offset, int width, long value) {
        checkWritable(buffer, offset, width);
        for (int i = width - 1; i >= 0; i--) {
            buffer.put(offset + i, (byte) value);
            value >>>= 8;
        }
    }

    public static void ascii(ByteBuffer out, String text) {
        for (int i = 0; i < text.length(); i++) {
            out.put((byte) text.charAt(i));
        }
    }

    /** Allocation-free, including Long.MIN_VALUE. Uses only the caller's output buffer. */
    public static void jsonLong(ByteBuffer out, long value) {
        if (value < 0) {
            out.put((byte) '-');
        } else {
            value = -value;
        }
        int start = out.position();
        do {
            long quotient = value / 10;
            out.put((byte) ('0' - (value - quotient * 10)));
            value = quotient;
        } while (value != 0);
        for (int left = start, right = out.position() - 1; left < right; left++, right--) {
            byte digit = out.get(left);
            out.put(left, out.get(right));
            out.put(right, digit);
        }
    }

    public static void jsonEscape(ByteBuffer out, char ch) {
        ascii(out, "\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            int digit = (ch >>> shift) & 15;
            out.put((byte) (digit < 10 ? '0' + digit : 'a' + digit - 10));
        }
    }

    /** Escapes UTF-16 code units, including surrogate pairs, without materializing strings. */
    public static void jsonString(ByteBuffer out, String text) {
        out.put((byte) '"');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' || ch == '\\') {
                out.put((byte) '\\').put((byte) ch);
            } else if (ch < 32 || ch >= 128) {
                jsonEscape(out, ch);
            } else {
                out.put((byte) ch);
            }
        }
        out.put((byte) '"');
    }

    /** Floating-point formatting may allocate Strings. Not part of the integer zero-allocation gate. */
    public static void jsonDouble(ByteBuffer out, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite JSON number");
        }
        ascii(out, Double.toString(value));
    }

    /** Floating-point formatting may allocate Strings. */
    public static void jsonFloat(ByteBuffer out, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite JSON number");
        }
        ascii(out, Float.toString(value));
    }
}

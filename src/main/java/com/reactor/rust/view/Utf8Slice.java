package com.reactor.rust.view;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Strict UTF-8 borrowed slice. CharSequence indices and length count UTF-16 code units,
 * not bytes or code points. Source bytes must remain immutable and alive during use.
 * reset/clear invalidate earlier borrows. Allocated subsequence windows detect rebinding;
 * the reusable Utf8Slice itself must never escape its borrow. Neither tracks external native
 * lease release, so neither is safe after that release. Not thread-safe.
 */
public final class Utf8Slice implements CharSequence, AutoCloseable {
    private ByteBuffer buffer;
    private int offset;
    private int bytes;
    private int chars;
    private long generation;

    public Utf8Slice reset(ByteBuffer source, int start, int length) {
        clear();
        ViewSupport.checkRange(source, start, length);
        buffer = source;
        offset = start;
        bytes = length;
        try {
            for (int i = 0; i < bytes;) {
                int cp = codePoint(i);
                chars += Character.charCount(cp);
                i += width(source.get(start + i) & 255);
            }
        } catch (RuntimeException failure) {
            clear();
            throw failure;
        }
        return this;
    }

    private void checkBound() {
        if (buffer == null) {
            throw new IllegalStateException("UTF-8 slice is detached");
        }
    }

    public void clear() {
        generation++;
        buffer = null;
        offset = 0;
        bytes = 0;
        chars = 0;
    }

    @Override
    public void close() {
        clear();
    }

    public int byteLength() {
        checkBound();
        return bytes;
    }

    public byte byteAt(int index) {
        checkBound();
        if (index < 0 || index >= bytes) {
            throw new IndexOutOfBoundsException(index);
        }
        return buffer.get(offset + index);
    }

    @Override
    public int length() {
        checkBound();
        return chars;
    }

    @Override
    public char charAt(int index) {
        checkBound();
        if (index < 0 || index >= chars) {
            throw new IndexOutOfBoundsException(index);
        }
        int utf16 = 0;
        for (int i = 0; i < bytes;) {
            int cp = codePoint(i);
            if (cp <= 0xffff) {
                if (utf16 == index) {
                    return (char) cp;
                }
                utf16++;
            } else {
                if (utf16 == index) {
                    return Character.highSurrogate(cp);
                }
                if (utf16 + 1 == index) {
                    return Character.lowSurrogate(cp);
                }
                utf16 += 2;
            }
            i += width(buffer.get(offset + i) & 255);
        }
        throw new IndexOutOfBoundsException(index);
    }

    /**
     * Allocates a small borrowed UTF-16 window, never a String. Supports split surrogate pairs.
     * The window rejects access after reset/clear/close; it does not extend a native lease.
     */
    @Override
    public CharSequence subSequence(int start, int end) {
        checkBound();
        if (start < 0 || end < start || end > chars) {
            throw new IndexOutOfBoundsException();
        }
        return new Utf16Window(this, start, end - start, generation);
    }

    public boolean contentEqualsAscii(String literal) {
        checkBound();
        if (literal.length() != bytes) {
            return false;
        }
        for (int i = 0; i < bytes; i++) {
            char ch = literal.charAt(i);
            if (ch > 127 || ch != (buffer.get(offset + i) & 255)) {
                return false;
            }
        }
        return true;
    }

    public long parseLong() {
        checkBound();
        if (bytes == 0) {
            throw new NumberFormatException("Empty integer");
        }
        int first = buffer.get(offset);
        boolean negative = first == '-';
        int i = negative || first == '+' ? 1 : 0;
        if (i == bytes) {
            throw new NumberFormatException("Sign without digits");
        }
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multiplyLimit = limit / 10;
        long result = 0;
        for (; i < bytes; i++) {
            int digit = buffer.get(offset + i) - '0';
            if (digit < 0 || digit > 9 || result < multiplyLimit) {
                throw new NumberFormatException("Invalid or overflowing integer");
            }
            result *= 10;
            if (result < limit + digit) {
                throw new NumberFormatException("Overflowing integer");
            }
            result -= digit;
        }
        return negative ? result : -result;
    }

    public int parseInt() {
        long value = parseLong();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new NumberFormatException("Overflowing int");
        }
        return (int) value;
    }

    public void writeTo(ByteBuffer target) {
        int position = target.position();
        if (bytes > target.remaining()) {
            throw new java.nio.BufferOverflowException();
        }
        writeTo(target, position);
        target.position(position + bytes);
    }

    /** Absolute copy; preserves both buffers' positions, limits and orders. */
    public void writeTo(ByteBuffer target, int targetOffset) {
        checkBound();
        ViewSupport.checkWritable(target, targetOffset, bytes);
        if (target == buffer && targetOffset > offset && targetOffset - offset < bytes) {
            for (int i = bytes - 1; i >= 0; i--) {
                target.put(targetOffset + i, buffer.get(offset + i));
            }
        } else {
            for (int i = 0; i < bytes; i++) {
                target.put(targetOffset + i, buffer.get(offset + i));
            }
        }
    }

    /** Rejects identical backing buffers; callers must also exclude duplicate/slice storage aliases. */
    public void requireDistinctStorage(ByteBuffer target) {
        checkBound();
        if (target == buffer) {
            throw new IllegalArgumentException("Output aliases borrowed UTF-8 storage");
        }
    }

    /** Writes escaped JSON bytes directly. Output position advances; overflow may leave partial output. */
    public void writeJson(ByteBuffer out) {
        requireDistinctStorage(out);
        out.put((byte) '"');
        for (int i = 0; i < bytes; i++) {
            int ch = buffer.get(offset + i) & 255;
            if (ch == '"' || ch == '\\') {
                out.put((byte) '\\').put((byte) ch);
            } else if (ch < 32) {
                ViewSupport.jsonEscape(out, (char) ch);
            } else {
                out.put((byte) ch);
            }
        }
        out.put((byte) '"');
    }

    /** Explicit materialization boundary; allocates a byte array and a String. */
    public String materializeString() {
        checkBound();
        byte[] copy = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            copy[i] = buffer.get(offset + i);
        }
        return new String(copy, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return materializeString();
    }

    private static int width(int first) {
        if (first < 0x80) return 1;
        if (first >= 0xc2 && first <= 0xdf) return 2;
        if (first >= 0xe0 && first <= 0xef) return 3;
        if (first >= 0xf0 && first <= 0xf4) return 4;
        throw new IllegalArgumentException("Malformed UTF-8 leading byte");
    }

    private int codePoint(int index) {
        int first = buffer.get(offset + index) & 255;
        int width = width(first);
        if (index > bytes - width) {
            throw new IllegalArgumentException("Truncated UTF-8 sequence");
        }
        if (width == 1) return first;
        int cp = first & (0x7f >> width);
        for (int i = 1; i < width; i++) {
            int next = buffer.get(offset + index + i) & 255;
            if ((next & 0xc0) != 0x80) {
                throw new IllegalArgumentException("Malformed UTF-8 continuation");
            }
            cp = (cp << 6) | (next & 63);
        }
        if ((width == 2 && cp < 0x80) || (width == 3 && cp < 0x800)
                || (width == 4 && cp < 0x10000) || cp > 0x10ffff
                || (cp >= 0xd800 && cp <= 0xdfff)) {
            throw new IllegalArgumentException("Non-scalar or overlong UTF-8 sequence");
        }
        return cp;
    }

    private void checkWindow(long expectedGeneration) {
        checkBound();
        if (generation != expectedGeneration) {
            throw new IllegalStateException("UTF-16 window belongs to an expired slice borrow");
        }
    }

    private record Utf16Window(Utf8Slice source, int start, int size, long generation) implements CharSequence {
        @Override public int length() { source.checkWindow(generation); return size; }
        @Override public char charAt(int index) {
            source.checkWindow(generation);
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            return source.charAt(start + index);
        }
        @Override public CharSequence subSequence(int from, int to) {
            source.checkWindow(generation);
            if (from < 0 || to < from || to > size) throw new IndexOutOfBoundsException();
            return source.subSequence(start + from, start + to);
        }
        @Override public String toString() {
            source.checkWindow(generation);
            StringBuilder text = new StringBuilder(size);
            for (int i = 0; i < size; i++) text.append(charAt(i));
            return text.toString();
        }
    }
}

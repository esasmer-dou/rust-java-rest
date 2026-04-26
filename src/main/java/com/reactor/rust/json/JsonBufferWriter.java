package com.reactor.rust.json;

import java.nio.ByteBuffer;

/**
 * Allocation-free JSON writer for handlers that write directly into the native response buffer.
 *
 * <p>The writer keeps counting required bytes after capacity is exceeded. Callers can return
 * {@link #result()} directly; a negative value tells Rust the exact buffer size needed for retry.</p>
 */
public final class JsonBufferWriter {

    private static final ThreadLocal<byte[]> NUMBER_BUFFER =
            ThreadLocal.withInitial(() -> new byte[32]);
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final ByteBuffer out;
    private final int start;
    private final int limit;
    private int cursor;
    private int required;

    private JsonBufferWriter(ByteBuffer out, int offset) {
        this.out = out;
        this.start = Math.max(0, offset);
        this.cursor = this.start;
        this.required = this.start;
        this.limit = out != null ? out.capacity() : 0;
    }

    public static JsonBufferWriter wrap(ByteBuffer out, int offset) {
        return new JsonBufferWriter(out, offset);
    }

    public int result() {
        int needed = required - start;
        int available = Math.max(0, limit - start);
        return needed <= available ? needed : -needed;
    }

    public JsonBufferWriter beginObject() {
        writeByte('{');
        return this;
    }

    public JsonBufferWriter endObject() {
        writeByte('}');
        return this;
    }

    public JsonBufferWriter beginArray() {
        writeByte('[');
        return this;
    }

    public JsonBufferWriter endArray() {
        writeByte(']');
        return this;
    }

    public JsonBufferWriter comma() {
        writeByte(',');
        return this;
    }

    public JsonBufferWriter fieldName(String name) {
        string(name);
        writeByte(':');
        return this;
    }

    public JsonBufferWriter fieldString(String name, String value) {
        fieldName(name);
        string(value);
        return this;
    }

    public JsonBufferWriter fieldLong(String name, long value) {
        fieldName(name);
        number(value);
        return this;
    }

    public JsonBufferWriter fieldInt(String name, int value) {
        fieldName(name);
        number(value);
        return this;
    }

    public JsonBufferWriter fieldBoolean(String name, boolean value) {
        fieldName(name);
        bool(value);
        return this;
    }

    public JsonBufferWriter fieldFixed2Cents(String name, long cents) {
        fieldName(name);
        fixed2Cents(cents);
        return this;
    }

    public JsonBufferWriter string(String value) {
        if (value == null) {
            nullValue();
            return this;
        }

        beginString();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> rawAscii("\\\"");
                case '\\' -> rawAscii("\\\\");
                case '\b' -> rawAscii("\\b");
                case '\f' -> rawAscii("\\f");
                case '\n' -> rawAscii("\\n");
                case '\r' -> rawAscii("\\r");
                case '\t' -> rawAscii("\\t");
                default -> {
                    if (ch < 0x20) {
                        unicodeEscape(ch);
                    } else if (ch < 0x80) {
                        writeByte(ch);
                    } else if (Character.isHighSurrogate(ch)
                            && i + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(i + 1))) {
                        writeCodePoint(Character.toCodePoint(ch, value.charAt(++i)));
                    } else {
                        writeCodePoint(ch);
                    }
                }
            }
        }
        endString();
        return this;
    }

    public JsonBufferWriter beginString() {
        writeByte('"');
        return this;
    }

    public JsonBufferWriter endString() {
        writeByte('"');
        return this;
    }

    public JsonBufferWriter stringAsciiFragment(String value) {
        rawAscii(value);
        return this;
    }

    public JsonBufferWriter stringIntFragment(int value) {
        writeLong(value);
        return this;
    }

    public JsonBufferWriter stringLongFragment(long value) {
        writeLong(value);
        return this;
    }

    public JsonBufferWriter number(long value) {
        writeLong(value);
        return this;
    }

    public JsonBufferWriter fixed2Cents(long cents) {
        if (cents < 0) {
            writeByte('-');
            cents = -cents;
        }
        writeLong(cents / 100);
        writeByte('.');
        long fraction = cents % 100;
        writeByte((int) ('0' + (fraction / 10)));
        writeByte((int) ('0' + (fraction % 10)));
        return this;
    }

    public JsonBufferWriter bool(boolean value) {
        rawAscii(value ? "true" : "false");
        return this;
    }

    public JsonBufferWriter nullValue() {
        rawAscii("null");
        return this;
    }

    public JsonBufferWriter rawAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            writeByte(value.charAt(i));
        }
        return this;
    }

    private void writeLong(long value) {
        if (value == Long.MIN_VALUE) {
            rawAscii("-9223372036854775808");
            return;
        }
        if (value < 0) {
            writeByte('-');
            value = -value;
        }
        byte[] digits = NUMBER_BUFFER.get();
        int pos = digits.length;
        do {
            long next = value / 10;
            digits[--pos] = (byte) ('0' + (value - next * 10));
            value = next;
        } while (value != 0);
        while (pos < digits.length) {
            writeByte(digits[pos++]);
        }
    }

    private void unicodeEscape(char ch) {
        rawAscii("\\u");
        writeByte(HEX[(ch >>> 12) & 0x0F]);
        writeByte(HEX[(ch >>> 8) & 0x0F]);
        writeByte(HEX[(ch >>> 4) & 0x0F]);
        writeByte(HEX[ch & 0x0F]);
    }

    private void writeCodePoint(int codePoint) {
        if (codePoint <= 0x7FF) {
            writeByte(0xC0 | (codePoint >>> 6));
            writeByte(0x80 | (codePoint & 0x3F));
        } else if (codePoint <= 0xFFFF) {
            writeByte(0xE0 | (codePoint >>> 12));
            writeByte(0x80 | ((codePoint >>> 6) & 0x3F));
            writeByte(0x80 | (codePoint & 0x3F));
        } else {
            writeByte(0xF0 | (codePoint >>> 18));
            writeByte(0x80 | ((codePoint >>> 12) & 0x3F));
            writeByte(0x80 | ((codePoint >>> 6) & 0x3F));
            writeByte(0x80 | (codePoint & 0x3F));
        }
    }

    private void writeByte(int value) {
        if (cursor < limit) {
            out.put(cursor, (byte) value);
        }
        cursor++;
        required++;
    }
}

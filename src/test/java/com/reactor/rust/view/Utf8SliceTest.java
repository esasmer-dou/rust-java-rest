package com.reactor.rust.view;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Utf8SliceTest {
    private Utf8Slice slice(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new Utf8Slice().reset(ByteBuffer.wrap(bytes), 0, bytes.length);
    }

    @Test
    void utf16CharSequenceSemanticsIncludingSplitSurrogates() {
        String text = "\u0130stanbul, \u0131\u015f\u0131k \u00e7\u011f\u00f6\u015f\u00fc \ud83d\ude80\ud83d\ude00";
        Utf8Slice slice = slice(text);
        assertEquals(text.length(), slice.length());
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length, slice.byteLength());
        for (int i = 0; i < text.length(); i++) assertEquals(text.charAt(i), slice.charAt(i));
        for (int start = 0; start <= text.length(); start++) {
            for (int end = start; end <= text.length(); end++) {
                CharSequence window = slice.subSequence(start, end);
                assertEquals(text.substring(start, end), window.toString());
                assertEquals(end - start, window.length());
                assertEquals(window.toString(), window.subSequence(0, window.length()).toString());
            }
        }
        assertEquals(text, slice.materializeString());
        assertThrows(IndexOutOfBoundsException.class, () -> slice.charAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> slice.charAt(text.length()));
        assertThrows(IndexOutOfBoundsException.class, () -> slice.subSequence(2, 1));
    }

    @Test
    void rejectsMalformedUtf8AndDetachesOnFailure() {
        int[][] invalid = {{0x80}, {0xc0, 0xaf}, {0xc1, 0x80}, {0xc2}, {0xe0, 0x80, 0x80},
                {0xed, 0xa0, 0x80}, {0xed, 0xbf, 0xbf}, {0xef, 0x80}, {0xf0, 0x80, 0x80, 0x80},
                {0xf4, 0x90, 0x80, 0x80}, {0xf5, 0x80, 0x80, 0x80}, {0xff}, {0xc2, 0x20},
                {0xf0, 0x9f, 0x80, 0x20}};
        Utf8Slice slice = slice("previous");
        for (int[] sequence : invalid) {
            byte[] bytes = new byte[sequence.length];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) sequence[i];
            assertThrows(IllegalArgumentException.class, () -> slice.reset(ByteBuffer.wrap(bytes), 0, bytes.length));
            assertThrows(IllegalStateException.class, slice::length);
        }
        for (String text : new String[]{"", "\u0000", "\u007f", "\u0080", "\u07ff", "\u0800", "\ud7ff", "\ue000", "\uffff", "\ud800\udc00", "\udbff\udfff"}) {
            assertEquals(text, slice(text).materializeString());
        }
    }

    @Test
    void strictDecimalBoundariesAndOverflow() {
        for (long value : new long[]{0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertEquals(value, slice(Long.toString(value)).parseLong());
        }
        assertEquals(Long.MAX_VALUE, slice("+9223372036854775807").parseLong());
        assertEquals(0, slice("-0000").parseLong());
        assertEquals(Integer.MIN_VALUE, slice("-2147483648").parseInt());
        assertEquals(Integer.MAX_VALUE, slice("2147483647").parseInt());
        for (String invalid : new String[]{"", "+", "-", "9223372036854775808", "-9223372036854775809",
                "18446744073709551616", " 1", "1 ", "1.0", "1a", "\u0661", "++1", "99999999999999999999999999999999"}) {
            assertThrows(NumberFormatException.class, () -> slice(invalid).parseLong(), invalid);
        }
        assertThrows(NumberFormatException.class, () -> slice("2147483648").parseInt());
        assertThrows(NumberFormatException.class, () -> slice("-2147483649").parseInt());
    }

    @Test
    void asciiEqualityIsNotUtf8OrPrefixEquality() {
        assertTrue(slice("ABC").contentEqualsAscii("ABC"));
        assertTrue(slice("").contentEqualsAscii(""));
        assertFalse(slice("ABC").contentEqualsAscii("AB"));
        assertFalse(slice("ABC").contentEqualsAscii("ABD"));
        assertFalse(slice("\u00c4").contentEqualsAscii("\u00c4"));
        assertFalse(slice("\u00c4").contentEqualsAscii("AA"));
    }

    @Test
    void jsonEscapesAllControlsQuotesBackslashesAndPreservesUtf8() {
        StringBuilder text = new StringBuilder("\"\\");
        StringBuilder expected = new StringBuilder("\"\\\"\\\\");
        for (int i = 0; i < 32; i++) {
            text.append((char) i);
            expected.append(String.format("\\u%04x", i));
        }
        text.append("\u0130\u015f\u0131k\ud83d\ude80");
        expected.append("\u0130\u015f\u0131k\ud83d\ude80\"");
        ByteBuffer out = ByteBuffer.allocateDirect(512);
        slice(text.toString()).writeJson(out);
        assertEquals(expected.toString(), utf8(out));
    }

    @Test
    void copiesRespectLimitPositionOrderAndOverlap() {
        ByteBuffer source = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);
        source.put("0123456789".getBytes(StandardCharsets.UTF_8)).position(2).limit(10);
        Utf8Slice slice = new Utf8Slice().reset(source, 1, 5);
        ByteBuffer out = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
        out.position(2).limit(8);
        slice.writeTo(out);
        assertEquals(7, out.position());
        assertEquals(2, source.position());
        assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
        assertEquals(ByteOrder.LITTLE_ENDIAN, out.order());
        assertEquals('1', out.get(2));
        assertEquals('5', out.get(6));
        assertThrows(java.nio.BufferOverflowException.class, () -> slice.writeTo(out));
        assertThrows(IndexOutOfBoundsException.class, () -> slice.writeTo(out, 4));
        assertThrows(java.nio.ReadOnlyBufferException.class, () -> slice.writeTo(out.asReadOnlyBuffer(), 0));
        slice.writeTo(source, 3);
        assertEquals("12345", new Utf8Slice().reset(source, 3, 5).materializeString());
        assertThrows(IllegalArgumentException.class, () -> slice.writeJson(source));
    }

    @Test
    void boundsOverflowAndDetachAreExplicit() {
        Utf8Slice slice = slice("123");
        CharSequence window = slice.subSequence(0, 1);
        slice.close();
        slice.clear();
        assertThrows(IllegalStateException.class, slice::byteLength);
        assertThrows(IllegalStateException.class, slice::parseLong);
        assertThrows(IllegalStateException.class, window::length);
        assertThrows(IllegalStateException.class, () -> window.charAt(0));
        ByteBuffer buffer = ByteBuffer.allocate(16).limit(8);
        assertThrows(IndexOutOfBoundsException.class, () -> slice.reset(buffer, 7, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> slice.reset(buffer, Integer.MAX_VALUE, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> slice.reset(buffer, 1, Integer.MAX_VALUE));
        assertThrows(IndexOutOfBoundsException.class, () -> slice.reset(buffer, -1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> slice.reset(buffer, 0, -1));
        assertThrows(NullPointerException.class, () -> slice.reset(null, 0, 0));
    }

    @Test
    void subsequencesRejectRebindingRatherThanSilentlyReadingTheNextBorrow() {
        Utf8Slice slice = slice("old");
        CharSequence old = slice.subSequence(0, 3);
        CharSequence nested = old.subSequence(0, 1);
        CharSequence empty = slice.subSequence(0, 0);
        byte[] next = "new".getBytes(StandardCharsets.UTF_8);
        slice.reset(ByteBuffer.wrap(next), 0, next.length);
        assertEquals("new", slice.materializeString());
        for (CharSequence expired : new CharSequence[]{old, nested, empty}) {
            assertThrows(IllegalStateException.class, expired::length);
            assertThrows(IllegalStateException.class, expired::toString);
            assertThrows(IllegalStateException.class, () -> expired.charAt(0));
            assertThrows(IllegalStateException.class, () -> expired.subSequence(0, 0));
        }
        CharSequence current = slice.subSequence(0, 3);
        assertEquals("new", current.toString());
        slice.close();
        assertThrows(IllegalStateException.class, current::length);
        // This guards Java-side rebinding only, not externally released native memory.
    }

    @Test
    void integerJsonUsesNoSignOverflowAndHonorsOutputLimit() {
        ByteBuffer out = ByteBuffer.allocate(64);
        for (long value : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, -17, 0, 17}) {
            out.clear();
            ViewSupport.jsonLong(out, value);
            assertEquals(Long.toString(value), utf8(out));
        }
        out.clear().limit(2);
        assertThrows(java.nio.BufferOverflowException.class, () -> ViewSupport.jsonLong(out, 100));
        assertThrows(IllegalArgumentException.class, () -> ViewSupport.addSize(Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> ViewSupport.descriptorEnd(Integer.MAX_VALUE, 1, 8, Integer.MAX_VALUE));
    }

    private static String utf8(ByteBuffer out) {
        byte[] bytes = new byte[out.position()];
        out.get(0, bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

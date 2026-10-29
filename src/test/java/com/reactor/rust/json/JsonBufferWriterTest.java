package com.reactor.rust.json;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonBufferWriterTest {

    @Test
    void reusableWriterResetsStateBetweenBuffersOnSameThread() {
        ByteBuffer firstBuffer = ByteBuffer.allocate(64);
        JsonBufferWriter first = JsonBufferWriter.reusable(firstBuffer, 0);
        first.beginObject().fieldInt("id", 1).endObject();
        int firstLength = first.result();

        ByteBuffer secondBuffer = ByteBuffer.allocate(64);
        JsonBufferWriter second = JsonBufferWriter.reusable(secondBuffer, 0);
        second.beginObject().fieldString("city", "İstanbul").endObject();
        int secondLength = second.result();

        assertSame(first, second);
        assertEquals("{\"id\":1}", read(firstBuffer, firstLength));
        assertEquals("{\"city\":\"İstanbul\"}", read(secondBuffer, secondLength));
    }

    @Test
    void reusableWriterReportsRequiredLengthWhenBufferIsTooSmall() {
        ByteBuffer small = ByteBuffer.allocate(8);
        JsonBufferWriter json = JsonBufferWriter.reusable(small, 0);

        json.beginObject().fieldString("city", "İstanbul").endObject();

        int result = json.result();
        assertTrue(result < 0);
        assertEquals("{\"city\":\"İstanbul\"}".getBytes(StandardCharsets.UTF_8).length, -result);
    }

    @Test
    void asciiPrefixIntStringAvoidsCallerSideStringConcatenation() {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        JsonBufferWriter json = JsonBufferWriter.reusable(buffer, 0);

        int written = json.beginObject()
                .fieldStringAsciiPrefixInt("sku", "test".getBytes(StandardCharsets.US_ASCII), 42)
                .endObject()
                .result();

        assertEquals("{\"sku\":\"test42\"}", read(buffer, written));
    }

    @Test
    void generatedFieldPrefixWritesOnlyTheSelectedUtf8Slice() {
        byte[] fields = "ignored\"şehir\":".getBytes(StandardCharsets.UTF_8);
        int offset = "ignored".getBytes(StandardCharsets.UTF_8).length;
        int length = fields.length - offset;
        ByteBuffer buffer = ByteBuffer.allocate(64);

        int written = JsonBufferWriter.reusable(buffer, 0)
                .beginObject()
                .fieldPrefix(fields, offset, length)
                .string("İstanbul")
                .endObject()
                .result();

        assertEquals("{\"şehir\":\"İstanbul\"}", read(buffer, written));
    }

    @Test
    void generatedFieldPrefixPreservesExactRetryLength() {
        byte[] field = "\"customerName\":".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(5);

        int result = JsonBufferWriter.reusable(buffer, 0)
                .beginObject()
                .fieldPrefix(field, 0, field.length)
                .string("Ada")
                .endObject()
                .result();

        assertEquals(-"{\"customerName\":\"Ada\"}".getBytes(StandardCharsets.UTF_8).length, result);
    }

    @Test
    void scalarWritesRespectLimitAndCanRetryIntoTheSameReopenedBuffer() {
        byte[] expected = "{\"city\":\"\u0130\ud83d\ude80\\n\\\"\\\\\",\"id\":-9223372036854775808}"
                .getBytes(StandardCharsets.UTF_8);
        int offset = 5;
        for (boolean direct : new boolean[]{false, true}) {
            for (boolean reusable : new boolean[]{false, true}) {
                for (int available = 0; available <= expected.length; available++) {
                    ByteBuffer out = (direct ? ByteBuffer.allocateDirect(offset + expected.length + 8)
                            : ByteBuffer.allocate(offset + expected.length + 8)).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < out.capacity(); i++) out.put(i, (byte) '!');
                    out.limit(offset + available).position(2).mark();

                    JsonBufferWriter json = reusable ? JsonBufferWriter.reusable(out, offset)
                            : JsonBufferWriter.wrap(out, offset);
                    assertEquals(available < expected.length ? -expected.length : expected.length,
                            writeScalarDocument(json));
                    assertEquals(2, out.position());
                    assertEquals(offset + available, out.limit());
                    assertSame(ByteOrder.LITTLE_ENDIAN, out.order());
                    out.reset();
                    byte[] prefix = new byte[available];
                    out.get(offset, prefix);
                    assertArrayEquals(Arrays.copyOf(expected, available), prefix);
                    assertUntouchedOutside(out, offset, available);

                    out.limit(offset + expected.length);
                    json = reusable ? JsonBufferWriter.reusable(out, offset)
                            : JsonBufferWriter.wrap(out, offset);
                    assertEquals(expected.length, writeScalarDocument(json));
                    assertEquals(2, out.position());
                    assertEquals(offset + expected.length, out.limit());
                    out.reset();
                    byte[] complete = new byte[expected.length];
                    out.get(offset, complete);
                    assertArrayEquals(expected, complete);
                    assertUntouchedOutside(out, offset, expected.length);
                }
            }
        }
    }

    @Test
    void offsetAtOrBeyondLimitCountsWithoutWritingAndClearRestoresCapacity() {
        for (int offset : new int[]{2, 5}) {
            ByteBuffer out = ByteBuffer.allocate(32);
            Arrays.fill(out.array(), (byte) '!');
            out.limit(2).position(1);
            assertEquals(-2, JsonBufferWriter.reusable(out, offset).beginObject().endObject().result());
            assertEquals(1, out.position());
            assertEquals(2, out.limit());
            assertUntouchedOutside(out, 0, 0);
            out.clear();
            assertEquals(2, JsonBufferWriter.reusable(out, offset).beginObject().endObject().result());
            assertEquals('{', out.get(offset));
            assertEquals('}', out.get(offset + 1));
        }
    }

    @Test
    void limitedReadOnlyTargetsRejectActualScalarAndSwarWrites() {
        for (int available = 1; available <= 8; available++) {
            ByteBuffer out = ByteBuffer.allocateDirect(16).asReadOnlyBuffer();
            out.limit(available);
            assertThrows(ReadOnlyBufferException.class,
                    () -> JsonBufferWriter.reusable(out, 0).beginObject());
            assertThrows(ReadOnlyBufferException.class,
                    () -> JsonBufferWriter.reusable(out, 0).writePrebakedLong(0));
            assertEquals(0, out.position());
            assertEquals(available, out.limit());
        }
    }

    private static int writeScalarDocument(JsonBufferWriter json) {
        return json.beginObject().fieldString("city", "\u0130\ud83d\ude80\n\"\\").comma()
                .fieldLong("id", Long.MIN_VALUE).endObject().result();
    }

    private static void assertUntouchedOutside(ByteBuffer out, int offset, int length) {
        ByteBuffer all = out.duplicate().clear();
        for (int i = 0; i < offset; i++) assertEquals((byte) '!', all.get(i));
        for (int i = offset + length; i < all.capacity(); i++) assertEquals((byte) '!', all.get(i));
    }

    private static String read(ByteBuffer buffer, int length) {
        byte[] bytes = new byte[length];
        buffer.position(0);
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

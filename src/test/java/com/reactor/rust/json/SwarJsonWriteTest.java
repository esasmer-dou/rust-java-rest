package com.reactor.rust.json;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SwarJsonWriteTest {
    private static final long WORD = 0x223a22656d616e22L; // "name":" in wire order

    @Test void heapAndDirectUnalignedWritesPreserveCallerByteOrderAndPosition() {
        for (boolean direct : new boolean[]{false, true}) {
            for (ByteOrder order : new ByteOrder[]{ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
                ByteBuffer out = (direct ? ByteBuffer.allocateDirect(32) : ByteBuffer.allocate(32)).order(order);
                out.position(7);
                int size = JsonBufferWriter.reusable(out, 1).writePrebakedLong(WORD).result();
                assertEquals(8, size);
                assertEquals(7, out.position());
                assertSame(order, out.order());
                byte[] actual = new byte[8];
                out.get(1, actual);
                assertArrayEquals("\"name\":\"".getBytes(StandardCharsets.US_ASCII), actual);
            }
        }
    }

    @Test void everyTruncatedCapacityReportsExactRetryAndWritesOnlyAvailableBytes() {
        byte[] expected = "\"name\":\"".getBytes(StandardCharsets.US_ASCII);
        for (int capacity = 0; capacity <= 8; capacity++) {
            ByteBuffer out = ByteBuffer.allocate(capacity);
            int size = JsonBufferWriter.reusable(out, 0).writePrebakedLong(WORD).result();
            assertEquals(capacity == 8 ? 8 : -8, size);
            for (int i = 0; i < capacity; i++) assertEquals(expected[i], out.get(i));
        }
        assertEquals(-9, JsonBufferWriter.reusable(ByteBuffer.allocate(0), 0)
                .writePrebakedLong(WORD).endObject().result());
    }

    @Test void readOnlyOutputIsRejected() {
        ByteBuffer out = ByteBuffer.allocate(8).asReadOnlyBuffer();
        assertThrows(ReadOnlyBufferException.class,
                () -> JsonBufferWriter.reusable(out, 0).writePrebakedLong(WORD));
    }

    @Test void limitedSlicesAndEveryAlignmentMatchTheByteTablePath() {
        byte[] literal = "\"name\":\"".getBytes(StandardCharsets.US_ASCII);
        for (boolean direct : new boolean[]{false, true}) {
            for (ByteOrder order : new ByteOrder[]{ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
                for (int offset = 0; offset < 8; offset++) {
                    for (int available = 0; available <= 9; available++) {
                        ByteBuffer backing = direct ? ByteBuffer.allocateDirect(40) : ByteBuffer.allocate(40);
                        for (int i = 0; i < backing.capacity(); i++) backing.put(i, (byte) '!');
                        backing.position(3);
                        ByteBuffer out = backing.slice().order(order);
                        out.limit(offset + available).position(offset);
                        int actual = JsonBufferWriter.reusable(out, offset)
                                .writePrebakedLong(WORD).endObject().result();
                        assertEquals(available < 9 ? -9 : 9, actual);
                        assertEquals(offset, out.position());
                        assertEquals(offset + available, out.limit());
                        assertSame(order, out.order());
                        byte[] expected = new byte[40];
                        Arrays.fill(expected, (byte) '!');
                        System.arraycopy(literal, 0, expected, 3 + offset, Math.min(available, 8));
                        if (available == 9) expected[3 + offset + 8] = '}';
                        byte[] bytes = new byte[40];
                        backing.get(0, bytes);
                        assertArrayEquals(expected, bytes);

                        ByteBuffer scalar = ByteBuffer.allocate(40).order(order);
                        scalar.limit(offset + available);
                        assertEquals(actual, JsonBufferWriter.reusable(scalar, offset)
                                .fieldPrefix(literal, 0, literal.length).endObject().result());
                        for (int i = 0; i < available; i++) {
                            assertEquals(scalar.get(offset + i), out.get(offset + i));
                        }
                    }
                }
            }
        }
    }

    @Test void countOnlyThenRetryDoesNotRetainOverflowState() {
        assertEquals(-9, JsonBufferWriter.reusable(null, 3).writePrebakedLong(WORD).endObject().result());
        ByteBuffer out = ByteBuffer.allocate(12);
        assertEquals(9, JsonBufferWriter.reusable(out, 3).writePrebakedLong(WORD).endObject().result());
        byte[] actual = new byte[9];
        out.get(3, actual);
        assertArrayEquals("\"name\":\"}".getBytes(StandardCharsets.US_ASCII), actual);
    }

    @Test void orderChangesBetweenWordsPreserveLiteralBytesAndBufferState() {
        byte[] expected = {'"', 'n', 'a', 'm', 'e', '"', ':', '"',
                0, 1, 2, 3, 4, 127, (byte) 128, (byte) 255};
        for (boolean direct : new boolean[]{false, true}) {
            for (ByteOrder initial : new ByteOrder[]{ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
                ByteBuffer out = (direct ? ByteBuffer.allocateDirect(24) : ByteBuffer.allocate(24)).order(initial);
                out.limit(17).position(7).mark();
                JsonBufferWriter writer = JsonBufferWriter.reusable(out, 1).writePrebakedLong(WORD);
                ByteOrder changed = initial == ByteOrder.BIG_ENDIAN ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
                out.order(changed);
                assertEquals(16, writer.writePrebakedLong(0xff807f0403020100L).result());
                assertSame(changed, out.order());
                assertEquals(17, out.limit());
                assertEquals(7, out.position());
                out.reset();
                byte[] actual = new byte[16];
                out.get(1, actual);
                assertArrayEquals(expected, actual);
            }
        }
    }

    @Test void fusedLiteralsCoverEveryLengthAlignmentLimitAndByteOrder() {
        for (int length = 8; length <= 16; length++) {
            byte[] literal = new byte[length];
            for (int i = 0; i < length; i++) literal[i] = (byte) (0x78 + i);
            long first = word(literal, 0);
            long last = word(literal, length - 8);
            for (boolean direct : new boolean[]{false, true}) {
                for (ByteOrder order : new ByteOrder[]{ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
                    for (int offset = 0; offset < 8; offset++) {
                        for (int available = 0; available <= length + 1; available++) {
                            ByteBuffer backing = direct ? ByteBuffer.allocateDirect(40) : ByteBuffer.allocate(40);
                            for (int i = 0; i < backing.capacity(); i++) backing.put(i, (byte) '!');
                            backing.position(3);
                            ByteBuffer out = backing.slice().order(order);
                            out.limit(offset + available).position(offset).mark();
                            JsonBufferWriter writer = JsonBufferWriter.reusable(out, offset)
                                    .writePrebakedLiteral(first, last, length);
                            assertEquals(available < length ? -length : length, writer.result());
                            assertEquals(available < length + 1 ? -(length + 1) : length + 1,
                                    writer.endObject().result());
                            assertSame(order, out.order());
                            assertEquals(offset + available, out.limit());
                            assertEquals(offset, out.position());
                            out.reset();
                            byte[] expected = new byte[40];
                            Arrays.fill(expected, (byte) '!');
                            System.arraycopy(literal, 0, expected, 3 + offset, Math.min(available, length));
                            if (available > length) expected[3 + offset + length] = '}';
                            byte[] actual = new byte[40];
                            backing.get(0, actual);
                            assertArrayEquals(expected, actual);
                        }
                    }
                }
            }
        }
    }

    @Test void fusedLiteralsRejectInvalidLengthsAndOverflowWithoutAccounting() {
        ByteBuffer out = ByteBuffer.allocate(32);
        JsonBufferWriter writer = JsonBufferWriter.wrap(out, 2).beginObject();
        for (int length : new int[]{Integer.MIN_VALUE, -1, 0, 7, 17, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writePrebakedLiteral(WORD, WORD, length));
            assertEquals(1, writer.result());
            for (int i = 3; i < out.capacity(); i++) assertEquals(0, out.get(i));
        }
        assertEquals(9, writer.writePrebakedLiteral(WORD, 0, 8).result());
        JsonBufferWriter overflow = JsonBufferWriter.reusable(null, Integer.MAX_VALUE - 7);
        assertThrows(ArithmeticException.class, () -> overflow.writePrebakedLiteral(WORD, WORD, 8));
        assertEquals(0, overflow.result());
        JsonBufferWriter boundary = JsonBufferWriter.reusable(null, Integer.MAX_VALUE - 16);
        assertEquals(-16, boundary.writePrebakedLiteral(WORD, WORD, 16).result());
        assertThrows(ArithmeticException.class, () -> boundary.writePrebakedLiteral(WORD, WORD, 8));
        assertEquals(-16, boundary.result());
        assertEquals(8, JsonBufferWriter.reusable(ByteBuffer.allocate(8), 0)
                .writePrebakedLiteral(WORD, 0, 8).result());
    }

    @Test void fusedCursorOverflowIsRejectedIndependentlyOfRequiredAccounting() throws ReflectiveOperationException {
        ByteBuffer out = ByteBuffer.allocate(24);
        JsonBufferWriter writer = JsonBufferWriter.wrap(out, 2).beginObject();
        // Isolate the cursor guard from the currently equal required counter.
        var cursor = JsonBufferWriter.class.getDeclaredField("cursor");
        cursor.setAccessible(true);
        cursor.setInt(writer, Integer.MAX_VALUE - 7);
        assertThrows(ArithmeticException.class, () -> writer.writePrebakedLiteral(WORD, WORD, 8));
        assertEquals(Integer.MAX_VALUE - 7, cursor.getInt(writer));
        assertEquals(1, writer.result());
        for (int i = 0; i < out.capacity(); i++) assertEquals(i == 2 ? '{' : 0, out.get(i));
    }

    @Test void fusedReadOnlyFailuresDoNotAdvanceAndEmptyLimitsStillCount() {
        for (int length = 8; length <= 16; length++) {
            int literalLength = length;
            for (int available = 0; available <= length; available++) {
                ByteBuffer out = ByteBuffer.allocate(24).asReadOnlyBuffer();
                out.limit(2 + available).position(1).mark();
                JsonBufferWriter writer = JsonBufferWriter.wrap(out, 2);
                if (available == 0) {
                    assertEquals(-length, writer.writePrebakedLiteral(WORD, WORD, length).result());
                } else {
                    assertThrows(ReadOnlyBufferException.class,
                            () -> writer.writePrebakedLiteral(WORD, WORD, literalLength));
                    assertEquals(0, writer.result());
                }
                assertEquals(1, out.position());
                assertEquals(2 + available, out.limit());
                out.reset();
            }
        }
    }

    @Test void fusedCountOnlyRetryAndMixedWritesResetCleanly() {
        byte[] literal = "\"customerName\":".getBytes(StandardCharsets.US_ASCII);
        long first = word(literal, 0);
        long last = word(literal, literal.length - 8);
        JsonBufferWriter counting = JsonBufferWriter.reusable(null, 3);
        assertEquals(-25, counting.beginObject().writePrebakedLiteral(first, last, literal.length)
                .writePrebakedLong(WORD).endObject().result());
        ByteBuffer out = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN);
        JsonBufferWriter retry = JsonBufferWriter.reusable(out, 3).beginObject();
        assertSame(counting, retry);
        retry.writePrebakedLiteral(first, last, literal.length);
        out.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(25, retry.writePrebakedLong(WORD).endObject().result());
        byte[] actual = new byte[25];
        out.get(3, actual);
        assertArrayEquals("{\"customerName\":\"name\":\"}".getBytes(StandardCharsets.US_ASCII), actual);
        ByteBuffer exactWord = ByteBuffer.allocate(8);
        assertEquals(8, JsonBufferWriter.reusable(exactWord, 0).writePrebakedLiteral(WORD, 0, 8).result());
        assertArrayEquals("\"name\":\"".getBytes(StandardCharsets.US_ASCII), exactWord.array());
    }

    private static long word(byte[] bytes, int offset) {
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) result |= (bytes[offset + i] & 255L) << (i * Byte.SIZE);
        return result;
    }
}

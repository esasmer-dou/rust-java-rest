package com.reactor.rust.tracing;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class W3cTraceparentTest {
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "0123456789abcdef";
    private static final String HEADER = "00-" + TRACE_ID + '-' + SPAN_ID + "-01";
    private static volatile byte[] observedTarget;
    private static volatile long checksum;
    private static volatile Object allocationControl;

    @Test
    void writesExactly55BytesAtCallerOffsetWithoutTouchingSurroundingBytes() {
        for (boolean sampled : new boolean[]{false, true}) {
            byte[] target = new byte[61];
            Arrays.fill(target, (byte) '!');
            TraceContext context = new TraceContext(TRACE_ID, SPAN_ID, sampled);

            assertEquals(55, W3cTraceparent.write(context, target, 3));

            assertEquals(context.traceparent(), new String(target, 3, 55, StandardCharsets.US_ASCII));
            for (int index = 0; index < target.length; index++) {
                if (index < 3 || index >= 58) assertEquals('!', target[index]);
            }
            assertEquals(context, W3cTraceparent.parse(context.traceparent()));
        }
        byte[] exact = new byte[55];
        assertEquals(55, W3cTraceparent.write(new TraceContext(TRACE_ID, SPAN_ID, true), exact, 0));
        assertEquals(HEADER, new String(exact, StandardCharsets.US_ASCII));
    }

    @Test
    void rejectsNullAndOverflowingOutputRangesBeforeWriting() {
        TraceContext context = new TraceContext(TRACE_ID, SPAN_ID, true);
        byte[] target = new byte[55];
        Arrays.fill(target, (byte) '!');
        byte[] original = target.clone();
        for (int offset : new int[]{-1, Integer.MIN_VALUE, 1, 55, Integer.MAX_VALUE}) {
            assertThrows(IndexOutOfBoundsException.class, () -> W3cTraceparent.write(context, target, offset));
            assertArrayEquals(original, target);
        }
        for (int length : new int[]{0, 1, 54}) {
            assertThrows(IndexOutOfBoundsException.class,
                    () -> W3cTraceparent.write(context, new byte[length], 0));
        }
        assertThrows(NullPointerException.class, () -> W3cTraceparent.write(null, target, 0));
        assertThrows(NullPointerException.class, () -> W3cTraceparent.write(context, null, 0));
        assertArrayEquals(original, target);
    }

    @Test
    void rejectsInvalidContextIdsBeforeWriting() {
        byte[] target = new byte[55];
        Arrays.fill(target, (byte) '!');
        byte[] original = target.clone();
        for (String invalid : new String[]{"", "0".repeat(31), "0".repeat(32), TRACE_ID + "0",
                TRACE_ID.toUpperCase(java.util.Locale.ROOT), "g".repeat(32), "\u0100".repeat(32)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> W3cTraceparent.write(new TraceContext(invalid, SPAN_ID, true), target, 0));
            assertArrayEquals(original, target);
        }
        for (String invalid : new String[]{"", "0".repeat(15), "0".repeat(16), SPAN_ID + "0",
                SPAN_ID.toUpperCase(java.util.Locale.ROOT), "g".repeat(16), "\uffff".repeat(16)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> W3cTraceparent.write(new TraceContext(TRACE_ID, invalid, true), target, 0));
            assertArrayEquals(original, target);
        }
    }

    @Test
    void rejectsNullTruncationAndVersion00Extensions() {
        assertNull(W3cTraceparent.parse(null));
        for (int length = 0; length < 55; length++) {
            assertNull(W3cTraceparent.parse(HEADER.substring(0, length)), "length=" + length);
        }
        for (String suffix : new String[]{"-", "-extra", "0", " ", "\t", "\r\n"}) {
            assertNull(W3cTraceparent.parse(HEADER + suffix));
        }
        assertNull(W3cTraceparent.parse(" " + HEADER));
    }

    @Test
    void rejectsBadSeparatorsAndNonLowercaseHexAtEveryPrefixPosition() {
        for (int position = 0; position < 55; position++) {
            if (position == 2 || position == 35 || position == 52) {
                assertNull(W3cTraceparent.parse(replace(HEADER, position, '0')));
                continue;
            }
            for (char invalid : new char[]{'/', ':', 'g', 'A', 'F', '-', ' ', '\0', '\r', '\n',
                    '\u00ff', '\u0100', '\uffff'}) {
                assertNull(W3cTraceparent.parse(replace(HEADER, position, invalid)),
                        "position=" + position + ", character=" + (int) invalid);
            }
        }
    }

    @Test
    void requiresNonzeroIdsButAllowsEverySingleNonzeroNibblePosition() {
        assertNull(W3cTraceparent.parse("00-" + "0".repeat(32) + '-' + SPAN_ID + "-01"));
        assertNull(W3cTraceparent.parse("00-" + TRACE_ID + '-' + "0".repeat(16) + "-01"));
        for (int position = 0; position < 32; position++) {
            String id = replace("0".repeat(32), position, '1');
            assertEquals(id, W3cTraceparent.parse("00-" + id + '-' + SPAN_ID + "-01").traceId());
        }
        for (int position = 0; position < 16; position++) {
            String id = replace("0".repeat(16), position, 'f');
            assertEquals(id, W3cTraceparent.parse("00-" + TRACE_ID + '-' + id + "-01").spanId());
        }
    }

    @Test
    void parsesEveryHexFlagByteUsingOnlyTheSampledBit() {
        for (int flags = 0; flags < 256; flags++) {
            TraceContext parsed = W3cTraceparent.parse("00-" + TRACE_ID + '-' + SPAN_ID + '-' + hexByte(flags));
            assertNotNull(parsed, "flags=" + flags);
            assertEquals((flags & 1) != 0, parsed.sampled(), "flags=" + flags);
            assertEquals("00-" + TRACE_ID + '-' + SPAN_ID + ((flags & 1) != 0 ? "-01" : "-00"),
                    parsed.traceparent());
        }
    }

    @Test
    void acceptsAllNonForbiddenVersionsAndSafelyIgnoresOpaqueFutureFields() {
        for (int version = 0; version < 255; version++) {
            String header = hexByte(version) + HEADER.substring(2);
            TraceContext parsed = W3cTraceparent.parse(header);
            assertEquals(new TraceContext(TRACE_ID, SPAN_ID, true), parsed, "version=" + version);
            if (version > 0) {
                for (String suffix : new String[]{"-", "-opaque.NOT-hex", "-one-two", "-" + "x".repeat(4096)}) {
                    assertEquals(parsed, W3cTraceparent.parse(header + suffix));
                }
                for (String suffix : new String[]{"0", "extra", " ", "\t", "\u0100"}) {
                    assertNull(W3cTraceparent.parse(header + suffix));
                }
                for (int length = 0; length < 55; length++) {
                    assertNull(W3cTraceparent.parse(header.substring(0, length)));
                }
                assertNull(W3cTraceparent.parse(replace(header, 3, 'g') + "-ignored"));
                assertNull(W3cTraceparent.parse(replace(header, 36, 'A') + "-ignored"));
                assertNull(W3cTraceparent.parse(replace(header, 54, 'z') + "-ignored"));
            }
        }
        assertNull(W3cTraceparent.parse("ff" + HEADER.substring(2)));
        assertNull(W3cTraceparent.parse("ff" + HEADER.substring(2) + "-ignored"));
    }

    @Test
    void oneMillionCallerBufferWritesAllocateAtMost16KiBWhenSupported() {
        var platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean, "No allocation-capable ThreadMXBean");
        ThreadMXBean bean = (ThreadMXBean) platformBean;
        assumeTrue(bean.isThreadAllocatedMemorySupported(), "Thread allocation measurement unsupported");
        boolean wasEnabled = bean.isThreadAllocatedMemoryEnabled();
        try {
            if (!wasEnabled) bean.setThreadAllocatedMemoryEnabled(true);
            assumeTrue(bean.isThreadAllocatedMemoryEnabled(), "Thread allocation measurement disabled");
            long threadId = Thread.currentThread().threadId();
            TraceContext[] contexts = {
                    new TraceContext(TRACE_ID, SPAN_ID, true),
                    new TraceContext("fedcba9876543210fedcba9876543210", "fedcba9876543210", false)
            };
            byte[] target = new byte[55];
            observedTarget = target;
            runWrites(contexts, target, 200_000);
            for (int index = 0; index < 1_000; index++) bean.getThreadAllocatedBytes(threadId);
            assumeTrue(bean.getThreadAllocatedBytes(threadId) >= 0L,
                    "Allocation counter unavailable for current thread");
            Object[] retained = new Object[100];
            allocationControl = retained;
            try {
                for (int batch = 0; batch < 8; batch++) {
                    long tinyStart = bean.getThreadAllocatedBytes(threadId);
                    for (int index = 0; index < retained.length; index++) retained[index] = new byte[32];
                    long tinyBytes = bean.getThreadAllocatedBytes(threadId) - tinyStart;
                    assumeTrue(tinyBytes >= 4000 && tinyBytes <= 6400,
                            "Fine allocation accounting unavailable; gate NOT certified: " + tinyBytes);
                }
            } finally { allocationControl = null; }
            long controlStart = bean.getThreadAllocatedBytes(threadId);
            allocationControl = new byte[256 * 1024];
            long controlBytes = bean.getThreadAllocatedBytes(threadId) - controlStart;
            allocationControl = null;
            assumeTrue(controlBytes >= 256 * 1024, "Allocation counter failed positive control: " + controlBytes);

            long before = bean.getThreadAllocatedBytes(threadId);
            runWrites(contexts, target, 1_000_000);
            long after = bean.getThreadAllocatedBytes(threadId);
            assertTrue(after >= before, "Allocation counter must be monotonic");
            long allocated = after - before;
            System.out.println("W3cTraceparent.write: iterations=1000000, allocatedBytes=" + allocated
                    + ", limitBytes=16384, vm=" + System.getProperty("java.vm.name"));
            assertTrue(allocated <= 16 * 1024, "Allocated " + allocated + " bytes for 1M writes");
            assertEquals(contexts[1].traceparent(), new String(target, StandardCharsets.US_ASCII));
            assertTrue(checksum > 0);
        } finally {
            observedTarget = null;
            if (!wasEnabled) bean.setThreadAllocatedMemoryEnabled(false);
        }
    }

    private static void runWrites(TraceContext[] contexts, byte[] target, int iterations) {
        long sum = 0;
        for (int index = 0; index < iterations; index++) {
            sum += W3cTraceparent.write(contexts[index & 1], target, 0);
            sum += target[index % 55];
        }
        checksum = sum;
    }

    private static String replace(String value, int position, char replacement) {
        char[] characters = value.toCharArray();
        characters[position] = replacement;
        return new String(characters);
    }

    private static String hexByte(int value) {
        char[] digits = "0123456789abcdef".toCharArray();
        return new String(new char[]{digits[value >>> 4], digits[value & 15]});
    }
}

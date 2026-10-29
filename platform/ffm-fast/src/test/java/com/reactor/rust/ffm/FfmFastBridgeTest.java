package com.reactor.rust.ffm;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Live SymbolLookup tests. The supplied library must contain the real slab ABI. */
@EnabledIfSystemProperty(named = "reactor.ffm.test.library", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
class FfmFastBridgeTest {
    private static FfmFastBridge bridge;
    private static volatile Object allocationControl;
    private static final Object[] fineControl = new Object[100];
    private static volatile long checksum;

    @BeforeAll static void activate() {
        System.load(System.getProperty("reactor.ffm.test.library"));
        bridge = FfmFastBridge.tryActivate(FfmFastBridge.SLAB);
        assertNotNull(bridge, "Live FFM activation failed, gate NOT certified: " + FfmFastBridge.activationFailure());
        assertEquals(16, bridge.activeSlots());
        assertEquals(1_048_576, bridge.payloadBytes());
    }

    @Test void capacityReuseAndStaleTokensAreCheckedByTheNativeOwner() {
        long[] leases = new long[bridge.activeSlots()];
        try {
            for (int i = 0; i < leases.length; i++) {
                leases[i] = bridge.acquire();
                assertTrue(leases[i] > 0);
            }
            assertEquals(0, bridge.acquire());
            long old = leases[0];
            assertEquals(FfmFastBridge.OK, bridge.release(old));
            leases[0] = bridge.acquire();
            assertNotEquals(old, leases[0]);
            assertEquals(FfmFastBridge.STALE, bridge.release(old));
            assertEquals(-FfmFastBridge.STALE, bridge.readByte(old, 0));
            assertEquals(FfmFastBridge.OK, bridge.writeByte(leases[0], 0, 255));
            assertEquals(255, bridge.readByte(leases[0], 0));
        } finally {
            int failures = 0;
            for (long lease : leases) if (lease > 0 && bridge.release(lease) != FfmFastBridge.OK) failures++;
            assertEquals(0, failures);
        }
    }

    @Test void copyBoundsReadOnlyAndClosedCallerBuffersFailWithoutSlabEscape() {
        long lease = bridge.acquire();
        assertTrue(lease > 0);
        ByteBuffer caller = ByteBuffer.allocateDirect(32);
        caller.position(3).limit(19);
        try (var source = bridge.wrapBuffer(caller);
             var target = bridge.wrapBuffer(ByteBuffer.allocateDirect(16));
             var readOnly = bridge.wrapBuffer(ByteBuffer.allocateDirect(16).asReadOnlyBuffer())) {
            assertEquals(16, source.capacity());
            for (int i = 0; i < 16; i++) source.put(i, (byte) (i + 1));
            assertEquals(FfmFastBridge.OK, bridge.copyIn(lease, 7, source, 16));
            assertEquals(FfmFastBridge.OK, bridge.copyOut(lease, 7, target, 16));
            for (int i = 0; i < 16; i++) assertEquals((byte) (i + 1), target.get(i));
            assertEquals(3, caller.position());
            assertEquals(19, caller.limit());
            assertEquals(FfmFastBridge.BOUNDS, bridge.copyOut(lease, 7, readOnly, 16));
            assertEquals(FfmFastBridge.BOUNDS, bridge.copyIn(lease, Integer.MAX_VALUE, source, 16));
            assertEquals(FfmFastBridge.BOUNDS, bridge.copyIn(lease, 0, source, 17));
            assertEquals(FfmFastBridge.BOUNDS, bridge.writeByte(lease, 65_536, 0));
            assertEquals(FfmFastBridge.BOUNDS, bridge.writeByte(lease, 0, 256));
            source.close();
            assertThrows(IllegalStateException.class, () -> bridge.copyIn(lease, 0, source, 1));
        } finally {
            assertEquals(FfmFastBridge.OK, bridge.release(lease));
        }
    }

    @Test void copiesToBorrowedResponseBufferWithoutChangingItsPositionOrLimit() {
        long lease = bridge.acquire();
        assertTrue(lease > 0);
        try {
            for (int i = 0; i < 8; i++) assertEquals(FfmFastBridge.OK, bridge.writeByte(lease, i, i + 1));
            ByteBuffer out = ByteBuffer.allocateDirect(32);
            out.position(3).limit(19);
            assertEquals(FfmFastBridge.OK, bridge.copyOut(lease, 0, out, 7, 8));
            for (int i = 0; i < 8; i++) assertEquals((byte) (i + 1), out.get(7 + i));
            assertEquals(3, out.position()); assertEquals(19, out.limit());
            assertEquals(FfmFastBridge.BOUNDS, bridge.copyOut(lease, 0, out, 2, 8));
            assertEquals(FfmFastBridge.BOUNDS, bridge.copyOut(lease, 0, out, 12, 8));
            assertEquals(FfmFastBridge.BOUNDS, bridge.copyOut(lease, 0, out.asReadOnlyBuffer(), 7, 8));
            assertEquals(FfmFastBridge.BOUNDS, bridge.copyOut(lease, 0, ByteBuffer.allocate(32), 7, 8));
        } finally { assertEquals(FfmFastBridge.OK, bridge.release(lease)); }
    }

    @Test void reusedSlotsMaskOldBytesAndOnlyInitializeWriteGaps() {
        try (var source = bridge.wrapBuffer(ByteBuffer.allocateDirect(32));
             var target = bridge.wrapBuffer(ByteBuffer.allocateDirect(32), new Object())) {
            for (int i = 0; i < 32; i++) source.put(i, (byte) 0xa5);
            long first = bridge.acquire();
            assertTrue(first > 0);
            try { assertEquals(FfmFastBridge.OK, bridge.copyIn(first, 0, source, 32)); }
            finally { assertEquals(FfmFastBridge.OK, bridge.release(first)); }
            long second = bridge.acquire();
            assertTrue(second > 0);
            try {
                assertNotEquals(first, second);
                assertEquals(first & 63, second & 63);
                assertEquals(0, bridge.readByte(second, 0));
                assertEquals(0, bridge.readByte(second, 31));
                assertEquals(FfmFastBridge.OK, bridge.copyOut(second, 0, target, 32));
                for (int i = 0; i < 32; i++) assertEquals(0, target.get(i));
                assertEquals(FfmFastBridge.OK, bridge.writeByte(second, 7, 42));
                assertEquals(FfmFastBridge.OK, bridge.copyOut(second, 0, target, 32));
                for (int i = 0; i < 32; i++) assertEquals(i == 7 ? 42 : 0, target.get(i));
                assertEquals(0, bridge.readByte(second, 65_535));
            } finally { assertEquals(FfmFastBridge.OK, bridge.release(second)); }
        }
    }

    @Test void externalOwnerOverloadRejectsNullAndCloseDetachesStorage() {
        ByteBuffer bytes = ByteBuffer.allocateDirect(8);
        assertThrows(NullPointerException.class, () -> bridge.wrapBuffer(bytes, null));
        assertThrows(IllegalArgumentException.class, () -> bridge.wrapBuffer(ByteBuffer.allocate(8)));
        var buffer = bridge.wrapBuffer(bytes, new Object());
        buffer.put(0, (byte) 12);
        assertEquals(12, buffer.get(0));
        buffer.close();
        buffer.close();
        assertEquals(12, bytes.get(0));
        assertThrows(IllegalStateException.class, () -> buffer.get(0));
        assertThrows(IllegalStateException.class, () -> buffer.put(0, (byte) 1));
        assertThrows(IllegalStateException.class, buffer::capacity);
    }

    @Test void activationCannotReconfigureOrReturnToFallbackAfterFirstActivation() {
        assertSame(bridge, FfmFastBridge.tryActivate(FfmFastBridge.SLAB));
        assertThrows(IllegalStateException.class, () -> FfmFastBridge.tryActivate(FfmFastBridge.SLAB, 32));
        assertThrows(IllegalArgumentException.class, () -> FfmFastBridge.tryActivate(7));
        assertThrows(IllegalStateException.class, () -> FfmFastBridge.tryActivateForRedisClient(7, 123, 16));
        assertEquals(FfmFastBridge.UNSUPPORTED, bridge.recordOperation(1, 1, false));
        assertEquals(FfmFastBridge.UNSUPPORTED, FfmFastBridge.status(bridge.redisGet(0, 1, 0, 1)));
    }

    @Test void concurrentStaleCompletionCannotReleaseReusedSlot() throws Exception {
        long stale = bridge.acquire();
        assertEquals(FfmFastBridge.OK, bridge.release(stale));
        long live = bridge.acquire();
        assertTrue(live > 0);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 10_000; i++) {
                    if (bridge.release(stale) != FfmFastBridge.STALE) throw new AssertionError("Stale release succeeded");
                }
            } catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        try {
            start.countDown();
            for (int i = 0; i < 10_000; i++) assertEquals(FfmFastBridge.OK, bridge.writeByte(live, 0, i & 255));
            worker.join(30_000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
        } finally {
            assertEquals(FfmFastBridge.OK, bridge.release(live));
        }
    }

    @Test void warmedLeaseAndCopyCallsMeasureAllocationWithPositiveControl() {
        var platform = ManagementFactory.getThreadMXBean();
        assumeTrue(platform instanceof ThreadMXBean, "Allocation accounting unsupported; gate NOT certified");
        ThreadMXBean bean = (ThreadMXBean) platform;
        assumeTrue(bean.isThreadAllocatedMemorySupported(), "Allocation accounting unsupported; gate NOT certified");
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < 300; i++) fineControl[i % 100] = new byte[32];
        long[] fineDeltas = new long[8];
        for (int batch = 0; batch < fineDeltas.length; batch++) {
            long before = bean.getThreadAllocatedBytes(thread);
            for (int i = 0; i < fineControl.length; i++) fineControl[i] = new byte[32];
            fineDeltas[batch] = bean.getThreadAllocatedBytes(thread) - before;
        }
        System.out.println("FFM fine allocation controls=" + Arrays.toString(fineDeltas));
        for (long delta : fineDeltas) assumeTrue(delta >= 4000 && delta <= 6400,
                "Coarse/invalid allocation counter; gate NOT certified; rerun on calibrated HotSpot: " + delta);
        long controlBefore = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 1000; i++) allocationControl = new byte[128];
        long control = bean.getThreadAllocatedBytes(thread) - controlBefore;
        assertTrue(control > 64 * 1024, "Allocation positive control failed");
        try (var source = bridge.wrapBuffer(ByteBuffer.allocateDirect(8));
             var target = bridge.wrapBuffer(ByteBuffer.allocateDirect(8))) {
            exercise(20_000, source, target);
            long before = bean.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            long sum = exercise(100_000, source, target);
            long elapsed = System.nanoTime() - started;
            long after = bean.getThreadAllocatedBytes(thread);
            assertTrue(before >= 0 && after >= before);
            long allocated = after - before;
            checksum = sum;
            System.out.println("FFM slab gate: calls=100000 allocatedBytes=" + allocated
                    + " positiveControlBytes=" + control + " checksum=" + checksum);
            System.out.println("FFM slab sample: nanosPerLeaseAndCopyCycle=" + (elapsed / 100_000.0)
                    + " (not JNI A/B, not RPS or acceptance timing)");
            assertTrue(sum > 0);
            assertTrue(allocated <= 8192, "Slab bridge allocated " + allocated + " bytes");
        }
    }

    private static long exercise(int count, FfmFastBridge.Buffer source, FfmFastBridge.Buffer target) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            long lease = bridge.acquire();
            if (lease == 0) throw new AssertionError("No lease");
            if (bridge.writeByte(lease, 0, i & 255) != FfmFastBridge.OK) throw new AssertionError("Write");
            sum += bridge.readByte(lease, 0);
            source.put(0, (byte) (i & 255));
            if (bridge.copyIn(lease, 7, source, 8) != FfmFastBridge.OK) throw new AssertionError("Copy in");
            if (bridge.copyOut(lease, 7, target, 8) != FfmFastBridge.OK) throw new AssertionError("Copy out");
            sum += target.get(0) & 255;
            if (bridge.release(lease) != FfmFastBridge.OK) throw new AssertionError("Release");
        }
        return sum;
    }
}

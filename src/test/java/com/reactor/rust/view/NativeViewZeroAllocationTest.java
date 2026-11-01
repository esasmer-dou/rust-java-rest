package com.reactor.rust.view;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeViewZeroAllocationTest {
    private static final boolean REQUIRE_FINE_ACCOUNTING =
            Boolean.getBoolean("reactor.test.require-fine-allocation");
    private static volatile Object allocationControl;
    @TempDir Path directory;

    @Test
    void warmed100kAccountBorrowsGettersAndJsonAllocateAtMost8KiB() throws Exception {
        var platformBean = ManagementFactory.getThreadMXBean();
        requireCounter(platformBean instanceof ThreadMXBean,
                "JVM does not expose com.sun.management.ThreadMXBean; gate NOT certified");
        ThreadMXBean bean = (ThreadMXBean) platformBean;
        requireCounter(bean.isThreadAllocatedMemorySupported(),
                "Thread allocation accounting unsupported; gate NOT certified");
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        System.out.println("NativeView allocation runtime: vm=" + System.getProperty("java.vm.name")
                + " vmVersion=" + System.getProperty("java.vm.version")
                + " runtimeVersion=" + System.getProperty("java.runtime.version")
                + " os=" + System.getProperty("os.name") + " arch=" + System.getProperty("os.arch")
                + " required=" + REQUIRE_FINE_ACCOUNTING);
        try (var loaded = NativeViewTestSupport.loadFixture(directory)) {
            var fixture = loaded.fixture();
            fixture.exercise(10_000);
            long thread = Thread.currentThread().threadId();
            checkFineAccounting(bean, thread, "before");
            long before = bean.getThreadAllocatedBytes(thread);
            long checksum = fixture.exercise(100_000);
            long after = bean.getThreadAllocatedBytes(thread);
            assertTrue(before >= 0 && after >= before, "Allocation counter must be supported and monotonic");
            long allocated = after - before;
            checkFineAccounting(bean, thread, "after");
            System.out.println("NativeView allocation gate: warmup=10000 iterations=100000 allocatedBytes=" + allocated
                    + " budgetBytes=8192 checksum=" + checksum);
            assertTrue(checksum > 0, "Measured work must be consumed");
            assertTrue(allocated <= 8192, "Account getter/JSON loop allocated " + allocated + " bytes (budget 8192)");
        }
    }

    private static void checkFineAccounting(ThreadMXBean bean, long thread, String phase) {
        Object[] retained = new Object[100];
        allocationControl = retained;
        try {
            // OpenJ9 TLH reservation accounting can report zero for these escaped allocations.
            for (int batch = 0; batch < 8; batch++) {
                long before = bean.getThreadAllocatedBytes(thread);
                for (int index = 0; index < retained.length; index++) retained[index] = new byte[32];
                long after = bean.getThreadAllocatedBytes(thread);
                long bytes = after - before;
                requireCounter(before >= 0 && after >= before && bytes >= 4000 && bytes <= 6400,
                        "Fine allocation accounting unavailable; gate NOT certified: phase=" + phase
                                + " batch=" + batch + " bytes=" + bytes);
            }
            long before = bean.getThreadAllocatedBytes(thread);
            allocationControl = new byte[256 * 1024];
            long after = bean.getThreadAllocatedBytes(thread);
            long bytes = after - before;
            requireCounter(before >= 0 && after >= before && bytes >= 256 * 1024,
                    "Allocation counter failed positive control; gate NOT certified: phase=" + phase
                            + " bytes=" + bytes);
            System.out.println("NativeView allocation controls: phase=" + phase
                    + " smallBatches=8 smallBytesPerBatch=4000..6400 largeBytes=" + bytes);
        } finally {
            allocationControl = null;
        }
    }

    private static void requireCounter(boolean available, String message) {
        if (REQUIRE_FINE_ACCOUNTING) assertTrue(available, message);
        else assumeTrue(available, message);
    }
}

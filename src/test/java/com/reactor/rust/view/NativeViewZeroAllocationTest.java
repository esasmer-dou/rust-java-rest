package com.reactor.rust.view;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeViewZeroAllocationTest {
    private static volatile Object allocationControl;
    @TempDir Path directory;

    @Test
    void warmed100kAccountBorrowsGettersAndJsonAllocateAtMost8KiB() throws Exception {
        var platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean, "JVM does not expose com.sun.management.ThreadMXBean; gate NOT certified");
        ThreadMXBean bean = (ThreadMXBean) platformBean;
        assumeTrue(bean.isThreadAllocatedMemorySupported(), "Thread allocation accounting unsupported; gate NOT certified");
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        try (var loaded = NativeViewTestSupport.loadFixture(directory)) {
            var fixture = loaded.fixture();
            fixture.exercise(10_000);
            long thread = Thread.currentThread().threadId();
            Object[] retained = new Object[100];
            allocationControl = retained;
            try {
                for (int batch = 0; batch < 8; batch++) {
                    long tinyStart = bean.getThreadAllocatedBytes(thread);
                    for (int index = 0; index < retained.length; index++) retained[index] = new byte[32];
                    long tinyBytes = bean.getThreadAllocatedBytes(thread) - tinyStart;
                    assumeTrue(tinyBytes >= 4000 && tinyBytes <= 6400,
                            "Fine allocation accounting unavailable; gate NOT certified: " + tinyBytes);
                }
            } finally { allocationControl = null; }
            long controlStart = bean.getThreadAllocatedBytes(thread);
            allocationControl = new byte[256 * 1024];
            long controlBytes = bean.getThreadAllocatedBytes(thread) - controlStart;
            allocationControl = null;
            assumeTrue(controlBytes >= 256 * 1024,
                    "Allocation counter failed positive control; gate NOT certified: " + controlBytes);
            long before = bean.getThreadAllocatedBytes(thread);
            long checksum = fixture.exercise(100_000);
            long after = bean.getThreadAllocatedBytes(thread);
            assertTrue(before >= 0 && after >= before, "Allocation counter must be supported and monotonic");
            long allocated = after - before;
            System.out.println("NativeView allocation gate: warmup=10000 iterations=100000 allocatedBytes=" + allocated
                    + " budgetBytes=8192 checksum=" + checksum);
            assertTrue(checksum > 0, "Measured work must be consumed");
            assertTrue(allocated <= 8192, "Account getter/JSON loop allocated " + allocated + " bytes (budget 8192)");
        }
    }
}

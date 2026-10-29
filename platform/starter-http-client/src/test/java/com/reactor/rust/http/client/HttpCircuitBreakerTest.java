package com.reactor.rust.http.client;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HttpCircuitBreakerTest {
    private static volatile Object allocationControl;
    @Test
    void openCircuitMapsTo503WithoutChangingLegacyClientExceptionMapping() {
        var shared = HttpCircuitOpenException.INSTANCE;
        var expected = new com.reactor.rust.exception.HttpErrorMapper.MappedError(
                503, "dependency_unavailable", "Dependency is temporarily unavailable");
        assertEquals(expected, com.reactor.rust.exception.HttpErrorMapper.map(shared));
        assertEquals(expected, com.reactor.rust.exception.HttpErrorMapper.map(
                new java.util.concurrent.CompletionException(shared)));
        assertEquals(0, shared.getStackTrace().length);
        assertNull(shared.getCause());
        assertEquals(500, com.reactor.rust.exception.HttpErrorMapper.map(
                new HttpClientException("legacy failure", 503)).status());
    }

    @Test
    void opensAfterEightFailuresInSixteenFinalCalls() {
        var state = state(new AtomicLong(), 64, 16, 50, 2);
        for (int i = 0; i < 15; i++) {
            finish(state, (i & 1) == 0);
            assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
        }
        finish(state, false);
        assertEquals(CircuitBreakerState.Phase.OPEN, state.phase());
        assertSame(HttpCircuitOpenException.INSTANCE, assertThrows(HttpCircuitOpenException.class, state::acquirePermission));
    }

    @Test
    void allSixtyFourResultsAndEvictionCountInFailureRate() {
        var state = state(new AtomicLong(), 64, 64, 51, 2);
        for (int i = 0; i < 64; i++) finish(state, i >= 32);
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
        finish(state, true); // 33/64, evicting the oldest success.
        assertEquals(CircuitBreakerState.Phase.OPEN, state.phase());
    }

    @Test
    void nonPowerOfTwoWindowUsesExactRateNotRoundedPercent() {
        var state = state(new AtomicLong(), 3, 3, 67, 1);
        finish(state, false);
        finish(state, true);
        finish(state, true);
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
        finish(state, true);
        assertEquals(CircuitBreakerState.Phase.OPEN, state.phase());
    }

    @Test
    void validatesEveryConfigurationBoundary() {
        int[][] invalid = {{0,1,50,2}, {65,16,50,2}, {64,0,50,2}, {3,4,50,2},
                {64,16,0,2}, {64,16,101,2}, {64,16,50,0}, {64,16,50,65}};
        for (int[] c : invalid) assertThrows(IllegalArgumentException.class,
                () -> state(new AtomicLong(), c[0], c[1], c[2], c[3]));
        for (long duration : new long[]{0, -1, Long.MAX_VALUE / 1_000_000L + 1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CircuitBreakerState(64,16,50,duration,2,true));
        }
        assertThrows(NullPointerException.class, () -> new CircuitBreakerState(64,16,50,10,2,true,null));
        assertDoesNotThrow(() -> new CircuitBreakerState(1,1,100,Long.MAX_VALUE / 1_000_000L,64,false));
    }

    @Test
    void deadlineAndHalfOpenAdmissionsAreBoundedUntilAllProbesFinish() {
        AtomicLong time = new AtomicLong(-20_000_000L);
        var state = state(time, 1, 1, 50, 2);
        finish(state, true);
        time.addAndGet(9_999_999L);
        assertThrows(HttpCircuitOpenException.class, state::acquirePermission);
        time.incrementAndGet();
        long first = state.acquirePermission();
        long second = state.acquirePermission();
        assertEquals(CircuitBreakerState.Phase.HALF_OPEN, state.phase());
        assertThrows(HttpCircuitOpenException.class, state::acquirePermission);
        state.recordSuccess(first);
        state.recordSuccess(first); // Duplicate probe completion must not count twice.
        assertThrows(HttpCircuitOpenException.class, state::acquirePermission);
        state.recordSuccess(second);
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
    }

    @Test
    void sixtyFourthProbeAndStaleGenerationsCannotResetOrCloseNewCircuit() {
        AtomicLong time = new AtomicLong();
        var state = state(time, 1, 1, 50, 64);
        long preOpen = state.acquirePermission();
        finish(state, true);
        time.addAndGet(10_000_000L);
        long[] probes = new long[64];
        for (int i = 0; i < 64; i++) probes[i] = state.acquirePermission();
        assertThrows(HttpCircuitOpenException.class, state::acquirePermission);
        state.recordFailure(probes[63]);
        for (long probe : probes) state.recordSuccess(probe);
        state.recordSuccess(preOpen);
        assertEquals(CircuitBreakerState.Phase.OPEN, state.phase());
        time.addAndGet(10_000_000L);
        for (int i = 0; i < 64; i++) state.recordSuccess(state.acquirePermission());
        state.recordFailure(preOpen);
        state.recordFailure(probes[0]);
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
        finish(state, false);
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
    }

    @Test
    void monotonicDeadlineSurvivesNanoTimeWrap() {
        AtomicLong time = new AtomicLong(Long.MAX_VALUE - 5_000_000L);
        var state = state(time, 1, 1, 50, 1);
        finish(state, true);
        time.addAndGet(10_000_000L);
        state.recordSuccess(state.acquirePermission());
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
    }

    @Test
    void concurrentRecordersLoseNoOutcomesAtMinimumCalls() throws Exception {
        var state = state(new AtomicLong(), 64, 64, 50, 2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 63; i++) {
                boolean failure = i < 31;
                long lease = state.acquirePermission();
                tasks.add(pool.submit(() -> {
                    try { assertTrue(start.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    if (failure) state.recordFailure(lease); else state.recordSuccess(lease);
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
        finish(state, true);
        assertEquals(CircuitBreakerState.Phase.OPEN, state.phase());
    }

    @Test
    void repeatedConcurrentRingTurnsDoNotLoseOrInventFailures() throws Exception {
        var state = state(new AtomicLong(), 64, 64, 100, 2);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> {
                for (int j = 0; j < 5_000; j++) finish(state, false);
            }));
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        for (int i = 0; i < 63; i++) finish(state, true);
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
        finish(state, true);
        assertEquals(CircuitBreakerState.Phase.OPEN, state.phase());
    }

    @Test
    void healthyWindowDoesNotPublishOrRewriteRingDescriptors() throws Exception {
        var state = state(new AtomicLong(), 64, 64, 100, 1);
        for (int i = 0; i < 100_000; i++) finish(state, false);
        var current = CircuitBreakerState.class.getDeclaredField("current");
        current.setAccessible(true);
        Object closed = ((java.util.concurrent.atomic.AtomicReference<?>) current.get(state)).get();
        var field = closed.getClass().getDeclaredField("results");
        field.setAccessible(true);
        var results = (java.util.concurrent.atomic.AtomicLongArray) field.get(closed);
        for (int i = 0; i < results.length(); i++) assertEquals(0, results.get(i));
        for (int i = 0; i < 63; i++) finish(state, true);
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
        finish(state, true);
        assertEquals(CircuitBreakerState.Phase.OPEN, state.phase());
    }

    @Test
    void healthyShortcutMatchesSequentialWindowOracleAfterEvictionAndRecovery() {
        for (int window : new int[]{1, 3, 8, 64}) {
            var time = new AtomicLong();
            var state = state(time, window, window, 51, 1);
            var outcomes = new java.util.ArrayDeque<Boolean>(window);
            var random = new java.util.Random(719 + window);
            int failures = 0;
            for (int i = 0; i < 40_000; i++) {
                boolean failure = i % 400 >= 200 && random.nextInt(7) == 0;
                if (outcomes.size() == window && outcomes.removeFirst()) failures--;
                outcomes.addLast(failure);
                if (failure) failures++;
                finish(state, failure);
                boolean open = outcomes.size() >= window && failures * 100 >= 51 * outcomes.size();
                assertEquals(open ? CircuitBreakerState.Phase.OPEN : CircuitBreakerState.Phase.CLOSED, state.phase(), "window=" + window + " event=" + i);
                if (open) {
                    time.addAndGet(10_000_000L);
                    state.recordSuccess(state.acquirePermission());
                    outcomes.clear(); failures = 0;
                }
            }
        }
    }

    @Test
    void concurrentMixedTurnsReturnToHealthyWithoutRevivingStaleFailures() throws Exception {
        var state = state(new AtomicLong(), 64, 64, 100, 1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> {
                for (int n = 0; n < 5_000; n++) finish(state, n % 127 == 0);
            }));
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        for (int i = 0; i < 10_000; i++) finish(state, false);
        for (int i = 0; i < 63; i++) finish(state, true);
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
        finish(state, true);
        assertEquals(CircuitBreakerState.Phase.OPEN, state.phase());
    }

    @Test
    void concurrentHalfOpenAdmissionsAndSuccessesStayBounded() throws Exception {
        AtomicLong time = new AtomicLong();
        var state = state(time, 1, 1, 50, 2);
        finish(state, true);
        time.addAndGet(10_000_000L);
        var leases = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 64; i++) tasks.add(pool.submit(() -> {
                try { leases.add(state.acquirePermission()); } catch (HttpCircuitOpenException expected) { }
            }));
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            assertEquals(2, leases.size());
            tasks.clear();
            for (long lease : leases) tasks.add(pool.submit(() -> state.recordSuccess(lease)));
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
    }

    @Test
    void sharedExceptionCannotAccumulateStackCauseOrSuppression() {
        var exception = HttpCircuitOpenException.INSTANCE;
        exception.fillInStackTrace();
        exception.initCause(new IllegalStateException("private caller data"));
        exception.setStackTrace(new StackTraceElement[]{new StackTraceElement("secret", "method", "file", 1)});
        exception.addSuppressed(new IllegalStateException("private caller data"));
        assertEquals(0, exception.getStackTrace().length);
        assertEquals(0, exception.getSuppressed().length);
        assertNull(exception.getCause());
        assertEquals(503, exception.status());
        assertEquals("Outbound HTTP circuit is open", exception.getMessage());
    }

    @Test
    void cancellationReleasesProbeAndLateCompletionDoesNotCountAgain() {
        AtomicLong time = new AtomicLong();
        var state = state(time, 1, 1, 50, 1);
        finish(state, true);
        time.addAndGet(10_000_000L);
        var source = new CompletableFuture<HttpClientResponse<String>>();
        var result = HttpCircuitBreakerInvocation.observe(source, state, state.acquirePermission(), HttpClientResponse::body)
                .toCompletableFuture();
        assertTrue(result.cancel(false));
        assertEquals(CircuitBreakerState.Phase.OPEN, state.phase());
        source.complete(new HttpClientResponse<>(200, null, "late"));
        assertEquals(CircuitBreakerState.Phase.OPEN, state.phase());
        time.addAndGet(10_000_000L);
        state.recordSuccess(state.acquirePermission());
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
    }

    @Test
    void warmedRejectionsAllocateAtMostThirtyTwoKiB() {
        var state = state(new AtomicLong(), 1, 1, 50, 1);
        finish(state, true);
        for (int i = 0; i < 20; i++) assertEquals(100_000, reject(state, 100_000));
        var platform = ManagementFactory.getThreadMXBean();
        assumeTrue(platform instanceof ThreadMXBean, "Thread allocation counter unavailable: allocation gate NOT certified");
        ThreadMXBean bean = (ThreadMXBean) platform;
        assumeTrue(bean.isThreadAllocatedMemorySupported(), "Thread allocation counter unsupported");
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        bean.getThreadAllocatedBytes(thread);
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
        assumeTrue(controlBytes >= 256 * 1024, "Allocation counter failed positive control: " + controlBytes);
        long before = bean.getThreadAllocatedBytes(thread);
        long started = System.nanoTime();
        int rejected = reject(state, 100_000);
        long elapsed = System.nanoTime() - started;
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        assertEquals(100_000, rejected);
        assertTrue(bytes >= 0 && bytes <= 32 * 1024, "rejection bytes=" + bytes);
        assertTrue(elapsed / 100_000.0 < 200, "OPEN rejection must stay below 200 ns/call: " + elapsed / 100_000.0);
        System.out.println("PHASE3A rejection gate: calls=100000 bytes=" + bytes + " ns/call=" + elapsed / 100_000.0);
    }

    @Test
    void decoderFailureCompletesTheFutureAndReleasesTheProbe() {
        AtomicLong time = new AtomicLong();
        var state = state(time, 1, 1, 50, 1);
        finish(state, true);
        time.addAndGet(10_000_000L);
        var failure = new AssertionError("decoder failure");
        var result = HttpCircuitBreakerInvocation.observe(
                CompletableFuture.completedFuture(new HttpClientResponse<>(200, null, "body")),
                state, state.acquirePermission(), ignored -> { throw failure; }).toCompletableFuture();
        assertTrue(result.isCompletedExceptionally());
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
        assertEquals(CircuitBreakerState.Phase.OPEN, state.phase());
        time.addAndGet(10_000_000L);
        state.recordSuccess(state.acquirePermission());
        assertEquals(CircuitBreakerState.Phase.CLOSED, state.phase());
    }

    private static int reject(CircuitBreakerState state, int calls) {
        int rejected = 0;
        for (int i = 0; i < calls; i++) {
            try { state.acquirePermission(); }
            catch (HttpCircuitOpenException expected) { if (expected == HttpCircuitOpenException.INSTANCE) rejected++; }
        }
        return rejected;
    }

    private static CircuitBreakerState state(AtomicLong time, int window, int min, int threshold, int probes) {
        return new CircuitBreakerState(window, min, threshold, 10, probes, true, time::get);
    }

    private static void finish(CircuitBreakerState state, boolean failed) {
        long lease = state.acquirePermission();
        if (failed) state.recordFailure(lease); else state.recordSuccess(lease);
    }
}

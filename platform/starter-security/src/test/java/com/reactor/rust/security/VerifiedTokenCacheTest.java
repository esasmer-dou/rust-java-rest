package com.reactor.rust.security;

import com.reactor.rust.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static com.reactor.rust.security.JwtTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedTokenCacheTest {
    @ParameterizedTest
    @EnumSource(value = JwtAlgorithm.class, names = {"RS256", "ES256", "EdDSA"})
    void chunkedHashingMatchesTheWholeTokenAndErasesScratchAfterFailure(JwtAlgorithm algorithm) throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 1);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0));
        String subject = "u".repeat(900);
        String signed = token(algorithm, payload(subject, 1100));
        assertTrue(signed.length() > 4 * AsymmetricJwtVerifier.ASCII_CHUNK_BYTES);
        assertEquals(subject, cache.verify(signed).subject());
        assertEquals(subject, cache.verify(signed).subject());
        assertEquals(1, verifier.signatureVerificationCount());
        var entriesField = VerifiedTokenCache.class.getDeclaredField("entries"); entriesField.setAccessible(true);
        var entries = (AtomicReferenceArray<?>) entriesField.get(cache);
        Object entry = entries.get(0) != null ? entries.get(0) : entries.get(1);
        byte[] expected = java.security.MessageDigest.getInstance("SHA-256")
                .digest(signed.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var bytes = java.nio.ByteBuffer.wrap(expected);
        for (String component : new String[]{"hi", "lo", "h2", "h3"}) {
            var method = entry.getClass().getDeclaredMethod(component); method.setAccessible(true);
            assertEquals(bytes.getLong(), method.invoke(entry));
        }
        String nonAscii = signed.substring(0, 300) + "\u0130" + signed.substring(301);
        assertThrows(UnauthorizedException.class, () -> cache.verify(nonAscii));
        assertEquals(subject, cache.verify(signed).subject());
        assertEquals(1, verifier.signatureVerificationCount());
        var workerField = VerifiedTokenCache.class.getDeclaredField("workers"); workerField.setAccessible(true);
        var pool = (JwtWorkPool<?>) workerField.get(cache);
        Object worker = pool.worker(0);
        var inputField = worker.getClass().getDeclaredField("input"); inputField.setAccessible(true);
        byte[] scratch = (byte[]) inputField.get(worker);
        assertEquals(256, scratch.length);
        assertArrayEquals(new byte[scratch.length], scratch);
    }

    @Test
    void zeroEntryBudgetDoesNotAllocateHashWorkersOrAnEntryTable() throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 1);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(65536, 256, 0, 0));
        var workerField = VerifiedTokenCache.class.getDeclaredField("workers"); workerField.setAccessible(true);
        assertNull(workerField.get(cache));
        assertEquals(0, cache.capacity());
        String signed = token(JwtAlgorithm.EdDSA, payload("u", 1100));
        for (int i = 0; i < 3; i++) assertEquals("u", cache.verify(signed).subject());
        assertEquals(3, verifier.signatureVerificationCount());
        assertEquals(0, cache.hitCount());
        assertEquals(3, cache.missCount());
        assertEquals(0, cache.retainedBytes());
        cache.clear();
        assertEquals(0, cache.retainedEntryCount());
    }

    @ParameterizedTest
    @EnumSource(value = JwtAlgorithm.class, names = {"RS256", "ES256", "EdDSA"})
    void tenThousandRepeatedCallsPerformExactlyOneSignatureVerify(JwtAlgorithm algorithm) throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 2);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(1024, 2, 0));
        String signed = token(algorithm, payload("cached", 1100));
        SecurityPrincipal first = cache.verify(signed);
        for (int i = 0; i < 9999; i++) assertSame(first, cache.verify(signed));
        assertEquals(1, verifier.signatureVerificationCount());
        assertEquals(9999, cache.hitCount());
        assertEquals(1, cache.missCount());
        assertEquals(1, cache.retainedEntryCount());
    }

    @Test
    void cachedExpirationAndNotBeforeUseClockOnEveryCallAndRemoveInvalidEntries() throws Exception {
        var clock = new MutableClock(1000);
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), clock, 1);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0));
        String signed = token(JwtAlgorithm.EdDSA, payload("u", 1010));
        cache.verify(signed);
        clock.set(1009);
        cache.verify(signed);
        assertEquals(1, verifier.signatureVerificationCount());
        clock.set(1010);
        assertThrows(UnauthorizedException.class, () -> cache.verify(signed));
        assertEquals(1, verifier.signatureVerificationCount());
        assertEquals(0, cache.retainedEntryCount());
        assertEquals(0, cache.retainedBytes());
        assertThrows(UnauthorizedException.class, () -> cache.verify(signed));
        assertEquals(2, verifier.signatureVerificationCount());
        assertEquals(0, cache.retainedEntryCount());
        clock.set(1001);
        String nbf = token(JwtAlgorithm.EdDSA, "{\"sub\":\"u\",\"iss\":\"issuer\",\"aud\":\"api\",\"nbf\":1000,\"exp\":1100}");
        cache.verify(nbf);
        long checks = verifier.signatureVerificationCount();
        clock.set(999);
        assertThrows(UnauthorizedException.class, () -> cache.verify(nbf));
        assertEquals(checks, verifier.signatureVerificationCount());
        assertEquals(0, cache.retainedEntryCount());
        assertEquals(0, cache.retainedBytes());
    }

    @Test
    void futureNotBeforeAndBadSignaturesAreNeverPositiveCached() throws Exception {
        var clock = new MutableClock(1000);
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), clock, 1);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0));
        String future = token(JwtAlgorithm.EdDSA, "{\"sub\":\"u\",\"iss\":\"issuer\",\"aud\":\"api\",\"nbf\":1001,\"exp\":1100}");
        assertThrows(UnauthorizedException.class, () -> cache.verify(future));
        assertEquals(0, cache.retainedEntryCount());
        assertEquals(0, cache.retainedBytes());
        clock.set(1001);
        cache.verify(future);
        assertEquals(2, verifier.signatureVerificationCount());
        String good = token(JwtAlgorithm.EdDSA, payload("u", 1100));
        byte[] signature = Base64.getUrlDecoder().decode(good.substring(good.lastIndexOf('.') + 1));
        signature[0] ^= 1;
        String bad = replaceSignature(good, signature);
        long checks = verifier.signatureVerificationCount();
        for (int i = 0; i < 3; i++) assertThrows(UnauthorizedException.class, () -> cache.verify(bad));
        assertEquals(checks + 3, verifier.signatureVerificationCount());
        assertEquals(1, cache.retainedEntryCount());
    }

    @Test
    void absentExpirationIsNotCachedUnlessExplicitBoundedTtlAndTtlReverifies() throws Exception {
        var clock = new MutableClock(1000);
        var policy = policy(false, "", "", 8192, 1024, 64, 64);
        var verifier = new AsymmetricJwtVerifier(keys(), policy, clock, 1);
        String signed = token(JwtAlgorithm.EdDSA, "{\"sub\":\"u\"}");
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0));
        cache.verify(signed); cache.verify(signed);
        assertEquals(2, verifier.signatureVerificationCount());
        assertEquals(0, cache.retainedEntryCount());
        var timed = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 5));
        timed.verify(signed);
        clock.set(1004); timed.verify(signed);
        assertEquals(3, verifier.signatureVerificationCount());
        clock.set(1005); timed.verify(signed);
        assertEquals(4, verifier.signatureVerificationCount());
        clock.set(999); timed.verify(signed);
        assertEquals(5, verifier.signatureVerificationCount());
        assertThrows(IllegalArgumentException.class, () -> new VerifiedTokenCache.Options(2, 1, 86_401));
    }

    @Test
    void collisionsUseTwoWaysWithoutCrossIdentityAndReplacementIsBounded() throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 1);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0));
        String a = token(JwtAlgorithm.EdDSA, payload("A", 1100));
        String b = token(JwtAlgorithm.EdDSA, payload("B", 1100));
        String c = token(JwtAlgorithm.EdDSA, payload("C", 1100));
        cache.verify(a); cache.verify(b);
        assertEquals("A", cache.verify(a).subject());
        assertEquals("B", cache.verify(b).subject());
        assertEquals(2, verifier.signatureVerificationCount());
        assertEquals("C", cache.verify(c).subject());
        assertEquals(2, cache.retainedEntryCount());
        assertEquals("A", cache.verify(a).subject());
        assertEquals("B", cache.verify(b).subject());
        assertTrue(verifier.signatureVerificationCount() >= 4);
        assertEquals(2, cache.capacity());
        cache.clear();
        assertEquals(0, cache.retainedEntryCount());
        long before = verifier.signatureVerificationCount();
        cache.verify(a);
        assertEquals(before + 1, verifier.signatureVerificationCount());
    }

    @Test
    void lastTwoHashWordsParticipateInEqualityNotJustFirst128Bits() throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 1);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0));
        String signed = token(JwtAlgorithm.EdDSA, payload("u", 1100));
        cache.verify(signed);
        // Test-only reflection injects matching first words but corrupts each remaining word.
        var field = VerifiedTokenCache.class.getDeclaredField("entries"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var entries = (AtomicReferenceArray<Object>) field.get(cache);
        for (int word : new int[]{2, 3}) {
            Object entry = entries.get(0) != null ? entries.get(0) : entries.get(1);
            int slot = entries.get(0) == entry ? 0 : 1;
            Class<?> type = entry.getClass();
            var components = type.getRecordComponents();
            Object[] values = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                var accessor = components[i].getAccessor(); accessor.setAccessible(true);
                values[i] = accessor.invoke(entry); types[i] = components[i].getType();
            }
            values[word] = (Long) values[word] ^ 1L;
            var constructor = type.getDeclaredConstructor(types); constructor.setAccessible(true);
            entries.set(slot, constructor.newInstance(values));
            long before = verifier.signatureVerificationCount();
            cache.verify(signed);
            assertEquals(before + 1, verifier.signatureVerificationCount());
            cache.clear(); cache.verify(signed);
        }
    }

    @Test
    void concurrentHitsColdMissesEvictionsAndClearsNeverMixClaims() throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 8);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 8, 0));
        String[] tokens = new String[8];
        for (int i = 0; i < tokens.length; i++) tokens[i] = token(JwtAlgorithm.EdDSA, payload("u" + i, 1100));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int thread = 0; thread < 8; thread++) {
                int id = thread;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 40; i++) {
                        assertEquals("u" + id, cache.verify(tokens[id]).subject());
                        if (i % 9 == 0) cache.clear();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        assertTrue(cache.retainedEntryCount() <= cache.capacity());
    }

    @Test
    void fixedWorkerPoolRejectsSaturationAndReleasesWithoutTls() {
        var pool = new JwtWorkPool<>(new String[]{"worker"});
        int slot = pool.acquire();
        var overloaded = assertThrows(RuntimeException.class, pool::acquire);
        assertInstanceOf(com.reactor.rust.exception.DependencyFailure.class, overloaded);
        assertEquals(503, com.reactor.rust.exception.HttpErrorMapper.map(overloaded).status());
        assertEquals("dependency_unavailable", com.reactor.rust.exception.HttpErrorMapper.map(overloaded).code());
        assertEquals(0, overloaded.getStackTrace().length);
        overloaded.addSuppressed(new IllegalStateException("not retained"));
        assertEquals(0, overloaded.getSuppressed().length);
        assertThrows(IllegalStateException.class, () -> overloaded.initCause(new RuntimeException()));
        for (int i = 0; i < 10000; i++) {
            try { pool.acquire(); fail("Must reject overload"); }
            catch (RuntimeException repeat) { assertSame(overloaded, repeat); }
        }
        pool.release(slot);
        assertEquals(slot, pool.acquire());
        pool.release(slot);
        assertThrows(IllegalArgumentException.class, () -> new VerifiedTokenCache.Options(3, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new VerifiedTokenCache.Options(131072, 1, 0));
    }

    @Test
    void exactByteBudgetAdmitsEvictsAndReleasesButInsufficientBudgetNeverRejectsValidTokens() throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 1);
        String a = token(JwtAlgorithm.EdDSA, payload("A", 1100));
        String b = token(JwtAlgorithm.EdDSA, payload("B", 1100));
        JwtClaims claims = verifier.verifyClaims(a);
        long bytes = VerifiedTokenCache.entryBytesEstimate(claims);
        assertEquals(784, bytes); // Includes UTF-16 Strings, not just token bytes or reference slots.
        var small = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0, bytes - 1));
        assertEquals("A", small.verify(a).subject());
        assertEquals("A", small.verify(a).subject());
        assertEquals(0, small.retainedEntryCount());
        assertEquals(0, small.retainedBytes());
        var exact = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0, bytes));
        exact.verify(a);
        assertEquals(bytes, exact.retainedBytes());
        exact.verify(b);
        assertEquals(bytes, exact.retainedBytes());
        assertEquals(1, exact.retainedEntryCount());
        long before = verifier.signatureVerificationCount();
        assertEquals("B", exact.verify(b).subject());
        assertEquals(before, verifier.signatureVerificationCount());
        exact.clear();
        assertEquals(0, exact.retainedBytes());
        var disabled = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0, 0));
        assertEquals("A", disabled.verify(a).subject());
        assertEquals(0, disabled.retainedBytes());
        assertEquals(1024 * 1024, VerifiedTokenCache.Options.defaults().maxBytes());
        assertTrue(exact.fixedStateBytesEstimate() <= 4096);
        assertEquals(0, disabled.capacity());
        assertEquals(1024, disabled.fixedStateBytesEstimate());
        assertThrows(IllegalArgumentException.class, () -> new VerifiedTokenCache.Options(2, 1, 0, -1));
    }

    @Test
    void failedPublicationReturnsItsReservationExactlyOnce() throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 1);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0, 4096));
        String signed = token(JwtAlgorithm.EdDSA, payload("A", 1100));
        JwtClaims claims = verifier.verifyClaims(signed);
        cache.verify(signed);
        long before = cache.retainedBytes();
        var field = VerifiedTokenCache.class.getDeclaredField("entries"); field.setAccessible(true);
        var entries = (AtomicReferenceArray<?>) field.get(cache);
        int occupied = entries.get(0) != null ? 0 : 1;
        var install = VerifiedTokenCache.class.getDeclaredMethod("install", int.class, long.class, long.class,
                long.class, long.class, JwtClaims.class, long.class, long.class, long.class, long.class);
        install.setAccessible(true);
        for (int i = 0; i < 100; i++) {
            assertEquals(false, install.invoke(cache, occupied, 1L, 2L, 3L, 4L, claims,
                    0L, 1000L, Long.MAX_VALUE, before));
            assertEquals(before, cache.retainedBytes());
        }
        cache.clear();
        assertEquals(0, cache.retainedBytes());
    }

    @Test
    void immutableClaimEstimateChargesUtf16AndRejectsNegativeExpiryOrFutureNbfBeforeReservation() throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(false, "", "", 8192, 1024, 64, 64),
                new MutableClock(1000), 1);
        String subject = "i".repeat(8) + "\ud83d\ude80";
        String signed = token(JwtAlgorithm.EdDSA, "{\"sub\":\"" + subject + "\",\"roles\":[\"reader\"],\"exp\":1100}");
        JwtClaims claims = verifier.verifyClaims(signed);
        assertEquals(10, claims.principal().subject().length());
        assertEquals(128, SecurityPrincipal.stringBytes(subject));
        long estimate = VerifiedTokenCache.entryBytesEstimate(claims);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0, estimate));
        cache.verify(signed);
        assertEquals(estimate, cache.retainedBytes());
        String[] roles = claims.principal().roles(); roles[0] = "admin";
        assertFalse(claims.principal().hasRole("admin"));
        assertEquals(estimate, VerifiedTokenCache.entryBytesEstimate(claims));
        cache.clear();
        for (String invalid : new String[]{"{\"sub\":\"u\",\"exp\":-1}",
                "{\"sub\":\"u\",\"exp\":-9223372036854775808}",
                "{\"sub\":\"u\",\"exp\":1100,\"nbf\":1001}"}) {
            assertThrows(UnauthorizedException.class, () -> cache.verify(token(JwtAlgorithm.EdDSA, invalid)));
            assertEquals(0, cache.retainedBytes());
            assertEquals(0, cache.retainedEntryCount());
        }
    }

    @Test
    void absentExpirationTtlRemainsBoundedEvenIfWallClockStops() throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(false, "", "", 8192, 1024, 64, 64),
                new MutableClock(1000), 1);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 1, 4096));
        String signed = token(JwtAlgorithm.EdDSA, "{\"sub\":\"u\"}");
        cache.verify(signed);
        Thread.sleep(1100);
        cache.verify(signed);
        assertEquals(2, verifier.signatureVerificationCount());
        assertEquals(1, cache.retainedEntryCount());
    }

    @Test
    void concurrentAdmissionsAndEvictionsCannotOvershootByteBudgetOrLeakReservations() throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 8);
        String[] tokens = new String[8];
        for (int i = 0; i < tokens.length; i++) tokens[i] = token(JwtAlgorithm.EdDSA, payload("u" + i, 1100));
        long bytes = VerifiedTokenCache.entryBytesEstimate(verifier.verifyClaims(tokens[0]));
        long budget = 2 * bytes;
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 8, 0, budget));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 8; t++) {
                int id = t;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 80; i++) {
                        assertEquals("u" + id, cache.verify(tokens[id]).subject());
                        long reserved = cache.retainedBytes();
                        assertTrue(reserved >= 0 && reserved <= budget, "Reservation budget overshoot");
                        if (i % 11 == 0) cache.clear();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        assertEquals(bytes * cache.retainedEntryCount(), cache.retainedBytes());
        cache.clear();
        assertEquals(0, cache.retainedBytes());
    }

    @Test
    void exhaustedEpochNeverWrapsAndPermanentlyBypassesCacheWithoutRejectingValidTokens() throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 1);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0, 4096));
        String signed = token(JwtAlgorithm.EdDSA, payload("u", 1100));
        cache.verify(signed);
        assertTrue(cache.retainedBytes() > 0);
        var field = VerifiedTokenCache.class.getDeclaredField("generation"); field.setAccessible(true);
        var epoch = (java.util.concurrent.atomic.AtomicLong) field.get(cache);
        epoch.set(Long.MAX_VALUE - 1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 4; t++) tasks.add(executor.submit(() -> {
                for (int i = 0; i < 100; i++) cache.clear();
            }));
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertEquals(Long.MAX_VALUE, epoch.get());
        assertEquals(0, cache.retainedBytes());
        long before = verifier.signatureVerificationCount();
        assertEquals("u", cache.verify(signed).subject());
        assertEquals("u", cache.verify(signed).subject());
        assertEquals(before + 2, verifier.signatureVerificationCount());
        assertEquals(0, cache.retainedEntryCount());
        assertEquals(0, cache.retainedBytes());
        cache.clear();
        assertEquals(Long.MAX_VALUE, epoch.get());
    }

    @Test
    void reportsActualWarmCacheHashingCostWithoutZeroAllocationCertification() throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 1);
        var cache = verifier.withCache(new VerifiedTokenCache.Options(2, 1, 0));
        String signed = token(JwtAlgorithm.EdDSA, payload("profiled", 1100));
        for (int i = 0; i < 10000; i++) cache.verify(signed);
        var platform = ManagementFactory.getThreadMXBean();
        if (!(platform instanceof com.sun.management.ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported()) {
            System.out.println("JWT cache allocation profiling unsupported; NO zero-allocation claim"); return;
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        JwtTestSupport.requireFineAllocationCounter(bean, thread);
        long before = bean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) cache.verify(signed);
        long elapsed = System.nanoTime() - start;
        long allocated = bean.getThreadAllocatedBytes(thread) - before;
        System.out.println("JWT cache warm hits: iterations=10000 tokenChars=" + signed.length()
                + " SHA256BytesPerHit=" + signed.length() + " allocatedBytes=" + allocated
                + " elapsedNanos=" + elapsed + " signatureVerifications=" + verifier.signatureVerificationCount()
                + " (profiling only; no all-path zero-allocation or latency guarantee)");
        assertEquals(1, verifier.signatureVerificationCount());
        assertTrue(allocated >= 0);
    }
}

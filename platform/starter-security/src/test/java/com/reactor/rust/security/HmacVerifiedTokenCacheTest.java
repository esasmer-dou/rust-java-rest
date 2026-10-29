package com.reactor.rust.security;

import com.reactor.rust.exception.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class HmacVerifiedTokenCacheTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String PREFIX = "reactor.security.jwt.";
    private final Map<String, String> previous = new LinkedHashMap<>();

    @BeforeEach
    void configure() {
        for (String name : new String[]{"hmac-secret", "issuer", "audience", "subject-claim", "roles-claim",
                "clock-skew-seconds", "require-expiration", "max-token-chars"}) {
            previous.put(name, System.getProperty(PREFIX + name));
            System.clearProperty(PREFIX + name);
        }
        property("hmac-secret", SECRET);
        property("clock-skew-seconds", "0");
    }

    @AfterEach
    void restore() {
        previous.forEach((name, value) -> {
            if (value == null) System.clearProperty(PREFIX + name);
            else property(name, value);
        });
    }

    @Test
    void tenThousandRepeatedTokensPerformExactlyOneActualMac() throws Exception {
        var cache = cache(new JwtTestSupport.MutableClock(1000));
        String token = token("{\"sub\":\"reader\",\"roles\":[\"read\"],\"exp\":1100}");
        SecurityPrincipal first = cache.verify(token);
        for (int i = 1; i < 10000; i++) assertSame(first, cache.verify(token));
        assertEquals(1, cache.macVerificationCount());
        assertEquals(9999, cache.hitCount());
        assertEquals(1, cache.missCount());
        assertTrue(first.hasRole("read"));
        String[] roles = first.roles();
        roles[0] = "admin";
        assertFalse(cache.verify(token).hasRole("admin"));
    }

    @Test
    void hitsRevalidateLegacySkewExpiryAndNbfAndReleaseEntries() throws Exception {
        property("clock-skew-seconds", "400"); // Intentionally exceeds new asymmetric policy bounds.
        var clock = new JwtTestSupport.MutableClock(1000);
        var cache = cache(clock);
        String token = token("{\"sub\":\"u\",\"nbf\":1400,\"exp\":601}");
        cache.verify(token);
        cache.verify(token);
        assertEquals(1, cache.macVerificationCount());
        clock.set(1001);
        assertThrows(UnauthorizedException.class, () -> cache.verify(token));
        assertEquals(1, cache.macVerificationCount());
        assertEquals(0, cache.retainedBytes());
        assertEquals(0, cache.retainedEntryCount());
        clock.set(1000);
        cache.verify(token);
        clock.set(999);
        assertThrows(UnauthorizedException.class, () -> cache.verify(token));
        assertEquals(2, cache.macVerificationCount());
        assertEquals(0, cache.retainedBytes());
    }

    @Test
    void noExpirationRequiresExplicitLegacyOptOutAndExplicitCacheTtl() throws Exception {
        var clock = new JwtTestSupport.MutableClock(1000);
        String token = token("{\"sub\":\"u\"}");
        var required = cache(clock);
        assertThrows(UnauthorizedException.class, () -> required.verify(token));
        assertEquals(0, required.retainedEntryCount());
        property("require-expiration", "false");
        var uncached = cache(clock);
        uncached.verify(token);
        uncached.verify(token);
        assertEquals(2, uncached.macVerificationCount());
        assertEquals(0, uncached.retainedBytes());
        var bounded = VerifiedTokenCache.hmacFromProperties(new VerifiedTokenCache.Options(2, 1, 5), clock);
        bounded.verify(token);
        clock.set(1004);
        bounded.verify(token);
        assertEquals(1, bounded.macVerificationCount());
        clock.set(1005);
        bounded.verify(token);
        assertEquals(2, bounded.macVerificationCount());
    }

    @Test
    void preservesLegacyCustomClaimsRoleCountStringSizeAndTokenBound() throws Exception {
        property("subject-claim", "identity");
        property("roles-claim", "permissions");
        property("issuer", "trusted");
        property("audience", "api");
        property("max-token-chars", "20000");
        String subject = "i".repeat(5000); // Above even the asymmetric policy's configurable string maximum.
        String roles = "\"r\",".repeat(99) + "\"r\"";
        String token = token("{\"identity\":\"" + subject + "\",\"permissions\":[" + roles
                + "],\"iss\":\"trusted\",\"aud\":\"api\",\"exp\":1100}");
        var cache = cache(new JwtTestSupport.MutableClock(1000));
        assertEquals(subject, cache.verify(token).subject());
        assertEquals(100, cache.verify(token).roles().length);
        assertEquals(1, cache.macVerificationCount());
        property("max-token-chars", "32");
        var small = cache(new JwtTestSupport.MutableClock(1000));
        assertThrows(UnauthorizedException.class, () -> small.verify(token));
        assertEquals(0, small.macVerificationCount());
    }

    @Test
    void headerConfusionMalformedSignaturesAndInvalidClaimsAreNeverCached() throws Exception {
        property("issuer", "trusted");
        property("audience", "api");
        var cache = cache(new JwtTestSupport.MutableClock(1000));
        String payload = "{\"sub\":\"u\",\"iss\":\"trusted\",\"aud\":\"api\",\"exp\":1100}";
        for (String header : new String[]{"{\"alg\":\"none\"}", "{\"alg\":\"RS256\"}",
                "{\"alg\":\"HS256\",\"alg\":\"HS256\"}", "{\"alg\":\"HS256\",\"crit\":[]}",
                "{\"alg\":\"HS256\",\"b64\":true}", "{\"alg\":\"HS256\",\"jku\":\"https://invalid\"}"}) {
            String bad = sign(header, payload, SECRET);
            assertThrows(UnauthorizedException.class, () -> cache.verify(bad));
        }
        assertEquals(0, cache.macVerificationCount());
        for (String bad : new String[]{token(payload.replace("trusted", "wrong")),
                token(payload.replace("api", "wrong")), token(payload.replace("1100", "-1")),
                token(payload.replace("\"exp\":1100", "\"nbf\":1001,\"exp\":1100")),
                sign("{\"alg\":\"HS256\"}", payload, "different-secret-0123456789abcdef"),
                token(payload).substring(0, token(payload).lastIndexOf('.') + 1) + "AA"}) {
            assertThrows(UnauthorizedException.class, () -> cache.verify(bad));
            assertThrows(UnauthorizedException.class, () -> cache.verify(bad));
            assertEquals(0, cache.retainedBytes());
        }
        assertEquals(12, cache.macVerificationCount());
    }

    @Test
    void factoriesSnapshotSecretAndPolicyAndRotationReplacesCache() throws Exception {
        var clock = new JwtTestSupport.MutableClock(1000);
        String a = token("{\"sub\":\"a\",\"exp\":1100}");
        var old = cache(clock);
        old.verify(a);
        String rotatedSecret = "rotated-secret-0123456789abcdef0123456789";
        property("hmac-secret", rotatedSecret);
        property("issuer", "rotated");
        var replacement = cache(clock);
        assertThrows(UnauthorizedException.class, () -> replacement.verify(a));
        String b = sign("{\"alg\":\"HS256\"}", "{\"sub\":\"b\",\"iss\":\"rotated\",\"exp\":1100}", rotatedSecret);
        assertEquals("b", replacement.verify(b).subject());
        assertEquals("a", old.verify(a).subject());
        assertEquals(1, old.macVerificationCount());
        assertEquals(2, replacement.macVerificationCount());
        assertEquals(1, replacement.retainedEntryCount());
    }

    @Test
    void byteBudgetZeroSkipsCacheWithoutRejectingAuthentication() throws Exception {
        var cache = VerifiedTokenCache.hmacFromProperties(new VerifiedTokenCache.Options(2, 1, 0, 0),
                new JwtTestSupport.MutableClock(1000));
        String token = token("{\"sub\":\"u\",\"exp\":1100}");
        assertEquals("u", cache.verify(token).subject());
        assertEquals("u", cache.verify(token).subject());
        assertEquals(2, cache.macVerificationCount());
        assertEquals(0, cache.retainedBytes());
        assertEquals(0, cache.retainedEntryCount());
    }

    @Test
    void concurrentMacWorkersMissesClearAndReplacementNeverMixPrincipals() throws Exception {
        var cache = VerifiedTokenCache.hmacFromProperties(new VerifiedTokenCache.Options(2, 8, 0, 2048),
                new JwtTestSupport.MutableClock(1000));
        String[] tokens = new String[8];
        for (int i = 0; i < tokens.length; i++) tokens[i] = token("{\"sub\":\"u" + i + "\",\"exp\":1100}");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 8; t++) {
                int id = t;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        assertEquals("u" + id, cache.verify(tokens[id]).subject());
                        long bytes = cache.retainedBytes();
                        assertTrue(bytes >= 0 && bytes <= cache.maxBytes());
                        if (i % 13 == 0) cache.clear();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        assertTrue(cache.macVerificationCount() > 1);
        assertTrue(cache.retainedEntryCount() <= 2);
        cache.clear();
        assertEquals(0, cache.retainedBytes());
    }

    @Test
    void integerSecondClockUsesFloorForNegativeMillisecondsAndNeverInstant() throws Exception {
        AtomicLong millis = new AtomicLong(-1);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { throw new AssertionError("No Instant allocation on JWT clock path"); }
            @Override public long millis() { return millis.get(); }
        };
        var cache = cache(clock);
        String token = token("{\"sub\":\"u\",\"nbf\":-1,\"exp\":0}");
        assertEquals("u", cache.verify(token).subject());
        millis.set(-1000);
        cache.verify(token);
        assertEquals(1, cache.macVerificationCount());
        millis.set(-1001);
        assertThrows(UnauthorizedException.class, () -> cache.verify(token));
        assertEquals(0, cache.retainedBytes());
        millis.set(-1);
        cache.verify(token);
        millis.set(0);
        assertThrows(UnauthorizedException.class, () -> cache.verify(token));
        assertEquals(2, cache.macVerificationCount());
    }

    @Test
    void defaultClockFactoryIsExplicitAndCompatibleWithLegacyPrincipal() throws Exception {
        String token = token("{\"sub\":\"u\",\"exp\":" + (Instant.now().getEpochSecond() + 120) + "}");
        SecurityPrincipal legacy = Hs256JwtVerifier.fromProperties().verify(token);
        var cache = VerifiedTokenCache.hmacFromProperties(new VerifiedTokenCache.Options(2, 1, 0));
        assertEquals(legacy.subject(), cache.verify(token).subject());
        cache.verify(token);
        assertEquals(1, cache.macVerificationCount());
    }

    @Test
    void reportsActualWarmHmacHitHashingAllocation() throws Exception {
        var cache = cache(new JwtTestSupport.MutableClock(1000));
        String token = token("{\"sub\":\"profiled\",\"roles\":[\"read\"],\"exp\":1100}");
        for (int i = 0; i < 10000; i++) cache.verify(token);
        var platform = ManagementFactory.getThreadMXBean();
        if (!(platform instanceof com.sun.management.ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported()) {
            System.out.println("HMAC cache allocation profiling unsupported; no zero-allocation claim");
            return;
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();
        JwtTestSupport.requireFineAllocationCounter(bean, id);
        long before = bean.getThreadAllocatedBytes(id);
        long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) cache.verify(token);
        long elapsed = System.nanoTime() - start;
        long allocated = bean.getThreadAllocatedBytes(id) - before;
        System.out.println("HMAC cache warm hits: iterations=10000 tokenChars=" + token.length()
                + " SHA256BytesPerHit=" + token.length() + " allocatedBytes=" + allocated
                + " elapsedNanos=" + elapsed + " macDoFinalCalls=" + cache.macVerificationCount()
                + " (profiling only; JCA/provider/cold paths are not certified allocation-free)");
        assertEquals(1, cache.macVerificationCount());
        assertTrue(allocated >= 0);
    }

    private static VerifiedTokenCache cache(Clock clock) {
        return VerifiedTokenCache.hmacFromProperties(new VerifiedTokenCache.Options(2, 1, 0), clock);
    }

    private static void property(String name, String value) { System.setProperty(PREFIX + name, value); }

    private static String token(String payload) throws Exception { return sign("{\"alg\":\"HS256\"}", payload, SECRET); }

    private static String sign(String header, String payload, String secret) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String input = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + '.'
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + '.' + encoder.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }
}

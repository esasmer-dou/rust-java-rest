package com.reactor.rust.security;

import java.security.DigestException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Positive-only, fixed-size two-way set-associative cache bound to one immutable verifier.
 * Asymmetric verification and explicit hmacFromProperties() share the same admission/time logic.
 * Stores the FULL SHA-256 fingerprint, not tokens or a truncated hash. SHA-256 collisions
 * remain a cryptographic assumption, not an equality guarantee. No negative caching,
 * unbounded maps, locks, TLS or maintenance threads. A concurrent cold miss may verify more
 * than once; entries are published atomically. Always hash the complete compact ASCII token
 * (identical to its UTF-8 encoding), then revalidate exp/nbf using the verifier's Clock.
 * Disable by using the verifier directly; no cache branch is added to legacy HS256.
 * maxBytes charges conservative Entry/claims/principal/array/String overhead and UTF-16
 * storage, including in-flight admission reservations. It bounds cache-owned retention,
 * not application-held principals, transient verification allocations, provider state or RSS.
 */
public final class VerifiedTokenCache implements JwtVerifier {
    private static final long EXHAUSTED_EPOCH = Long.MAX_VALUE;
    public record Options(int slots, int maxWorkers, long missingExpirationTtlSeconds, long maxBytes) {
        public Options {
            if (slots < 2 || slots > 65_536 || Integer.bitCount(slots) != 1
                    || missingExpirationTtlSeconds < 0 || missingExpirationTtlSeconds > 86_400
                    || maxBytes < 0 || maxBytes > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("JWT cache needs bounded power-of-two slots, TTL and 0..64MiB positive-entry budget");
            }
            JwtWorkPool.checkSize(maxWorkers);
        }
        public Options(int slots, int maxWorkers, long missingExpirationTtlSeconds) {
            this(slots, maxWorkers, missingExpirationTtlSeconds, 1024 * 1024);
        }
        public static Options defaults() { return new Options(1024, 16, 0, 1024 * 1024); }
    }

    private final VerifiedClaimsSource verifier;
    private final Options options;
    private final AtomicReferenceArray<Entry> entries;
    private final JwtWorkPool<HashWorker> workers;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong replacement = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong reservedBytes = new AtomicLong();

    public VerifiedTokenCache(AsymmetricJwtVerifier verifier, Options options) {
        this(new VerifiedClaimsSource.Asymmetric(verifier), options);
    }

    /** Explicit startup opt-in; snapshots legacy HS256 properties without changing its default path. */
    public static VerifiedTokenCache hmacFromProperties(Options options) {
        return hmacFromProperties(options, java.time.Clock.systemUTC());
    }

    /** Explicit Clock seam; legacy require-expiration/skew/claim settings remain authoritative. */
    public static VerifiedTokenCache hmacFromProperties(Options options, java.time.Clock clock) {
        Objects.requireNonNull(options, "options");
        return new VerifiedTokenCache(new VerifiedClaimsSource.Hmac(Hs256JwtVerifier.fromProperties(), clock,
                options.maxWorkers()), options);
    }

    private VerifiedTokenCache(VerifiedClaimsSource verifier, Options options) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.options = Objects.requireNonNull(options, "options");
        entries = new AtomicReferenceArray<>(options.maxBytes() == 0 ? 0 : options.slots());
        if (options.maxBytes() == 0) {
            workers = null;
            return;
        }
        HashWorker[] state = new HashWorker[options.maxWorkers()];
        try {
            for (int i = 0; i < state.length; i++) state[i] = new HashWorker(verifier.maxTokenChars());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
        workers = new JwtWorkPool<>(state);
    }

    @Override
    public SecurityPrincipal verify(String token) {
        AsymmetricJwtVerifier.checkTokenLength(token, verifier.maxTokenChars());
        if (workers == null) {
            misses.incrementAndGet();
            return verifier.verify(token);
        }
        long epoch = generation.get();
        if (epoch == EXHAUSTED_EPOCH) {
            misses.incrementAndGet();
            return verifier.verify(token);
        }
        long hi;
        long lo;
        long h2;
        long h3;
        int slot = workers.acquire();
        HashWorker worker = workers.worker(slot);
        try {
            for (int start = 0; start < token.length(); start += worker.input.length) {
                int length = AsymmetricJwtVerifier.copyAscii(token, start,
                        Math.min(token.length(), start + worker.input.length), worker.input);
                worker.digest.update(worker.input, 0, length);
            }
            worker.digest.digest(worker.hash, 0, worker.hash.length);
            hi = word(worker.hash, 0);
            lo = word(worker.hash, 8);
            h2 = word(worker.hash, 16);
            h3 = word(worker.hash, 24);
        } catch (DigestException error) {
            throw new IllegalStateException("SHA-256 provider returned an invalid length", error);
        } finally {
            worker.digest.reset();
            Arrays.fill(worker.input, (byte) 0);
            Arrays.fill(worker.hash, (byte) 0);
            workers.release(slot);
        }
        long now = verifier.now();
        JwtClaims cached = lookup(hi, lo, h2, h3, epoch, now);
        if (cached != null) {
            hits.incrementAndGet();
            return cached.principal();
        }
        misses.incrementAndGet();
        JwtClaims claims = verifier.verifyClaims(token);
        now = verifier.now();
        verifier.validateTime(claims, now);
        if (claims.hasExpiration() || options.missingExpirationTtlSeconds() > 0) {
            long deadline = claims.hasExpiration() ? Long.MAX_VALUE
                    : JwtVerificationPolicy.addSaturated(now, options.missingExpirationTtlSeconds());
            insert(hi, lo, h2, h3, claims, epoch, now, deadline);
        }
        return claims.principal();
    }

    public int capacity() { return entries.length(); }
    public long hitCount() { return hits.get(); }
    public long missCount() { return misses.get(); }
    /** Actual Mac.doFinal calls by this cache's optional HMAC source; zero for asymmetric sources. */
    public long macVerificationCount() { return verifier.macVerificationCount(); }
    public long maxBytes() { return options.maxBytes(); }

    /** Includes in-flight reservations, not just published entries. Never exceeds maxBytes. */
    public long retainedBytes() { return reservedBytes.get(); }

    /**
     * Conservative fixed cache metadata/hash-state estimate, separate from positive-entry maxBytes.
     * Excludes the source verifier's separate crypto workers, keys and JCA provider state. Not an RSS measurement.
     */
    public long fixedStateBytesEstimate() {
        if (workers == null) return 1024L;
        return 1024L + 8L * options.slots()
                + options.maxWorkers() * (2048L + Math.min(AsymmetricJwtVerifier.ASCII_CHUNK_BYTES, verifier.maxTokenChars()));
    }

    /**
     * O(capacity), rare explicit maintenance. Epoch prevents old in-flight misses repopulating
     * live entries. Epoch exhaustion permanently bypasses this cache rather than recycling epochs;
     * valid tokens still undergo full verification. Replace the verifier/cache to enable it again.
     */
    public void clear() {
        while (true) {
            long previous = generation.get();
            if (previous == EXHAUSTED_EPOCH || generation.compareAndSet(previous, previous + 1)) break;
        }
        for (int i = 0; i < entries.length(); i++) {
            Entry removed = entries.getAndSet(i, null);
            if (removed != null) reservedBytes.addAndGet(-removed.retainedBytes);
        }
    }

    public int retainedEntryCount() {
        long epoch = generation.get();
        int count = 0;
        for (int i = 0; i < entries.length(); i++) {
            Entry entry = entries.get(i);
            if (entry != null && entry.epoch == epoch) count++;
        }
        return count;
    }

    private JwtClaims lookup(long hi, long lo, long h2, long h3, long epoch, long now) {
        int first = setIndex(hi, lo, h2, h3);
        for (int i = first; i < first + 2; i++) {
            Entry entry = entries.get(i);
            if (entry == null || !entry.matches(hi, lo, h2, h3)) continue;
            if (entry.epoch != epoch) {
                remove(i, entry);
                continue;
            }
            try {
                verifier.validateTime(entry.claims, now);
            } catch (RuntimeException invalidTime) {
                remove(i, entry);
                throw invalidTime;
            }
            if (now < entry.insertedAt || now >= entry.deadline
                    || (!entry.claims.hasExpiration() && System.nanoTime() - entry.insertedNanos
                    >= options.missingExpirationTtlSeconds() * 1_000_000_000L)) {
                remove(i, entry);
                continue;
            }
            return entry.claims;
        }
        return null;
    }

    private void insert(long hi, long lo, long h2, long h3, JwtClaims claims,
                        long epoch, long insertedAt, long deadline) {
        if (epoch == EXHAUSTED_EPOCH || generation.get() != epoch) return;
        long bytes = entryBytesEstimate(claims);
        if (bytes > options.maxBytes()) return;
        int first = setIndex(hi, lo, h2, h3);
        for (int i = first; i < first + 2; i++) {
            Entry previous = entries.get(i);
            if (previous != null && previous.epoch == epoch && previous.matches(hi, lo, h2, h3)) return;
            if (previous != null && previous.epoch != epoch) remove(i, previous);
            if (entries.get(i) == null && install(i, hi, lo, h2, h3, claims, epoch, insertedAt, deadline, bytes)) return;
        }
        // Evict before reserving, so replacement can proceed even at an exact byte budget.
        int victim = first + (int) (replacement.getAndIncrement() & 1);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (generation.get() != epoch) return;
            int index = first + ((victim - first + attempt) & 1);
            Entry previous = entries.get(index);
            if (previous != null && !remove(index, previous)) continue;
            if (install(index, hi, lo, h2, h3, claims, epoch, insertedAt, deadline, bytes)) return;
        }
    }

    static long entryBytesEstimate(JwtClaims claims) { return 160 + claims.retainedBytesEstimate(); }

    private boolean reserve(long bytes) {
        while (true) {
            long current = reservedBytes.get();
            if (bytes > options.maxBytes() - current) return false;
            if (reservedBytes.compareAndSet(current, current + bytes)) return true;
        }
    }

    private boolean install(int index, long hi, long lo, long h2, long h3, JwtClaims claims,
                            long epoch, long insertedAt, long deadline, long bytes) {
        if (epoch == EXHAUSTED_EPOCH || generation.get() != epoch || !reserve(bytes)) return false;
        boolean published = false;
        try {
            Entry fresh = new Entry(hi, lo, h2, h3, claims, epoch, insertedAt, deadline, bytes, System.nanoTime());
            published = entries.compareAndSet(index, null, fresh);
            if (published && generation.get() != epoch) remove(index, fresh);
            return published;
        } finally {
            if (!published) reservedBytes.addAndGet(-bytes);
        }
    }

    private boolean remove(int index, Entry entry) {
        if (entries.compareAndSet(index, entry, null)) {
            reservedBytes.addAndGet(-entry.retainedBytes);
            return true;
        }
        return false;
    }

    private int setIndex(long hi, long lo, long h2, long h3) {
        long mixed = hi ^ Long.rotateLeft(lo, 13) ^ Long.rotateLeft(h2, 29) ^ Long.rotateLeft(h3, 47);
        return ((int) (mixed ^ (mixed >>> 32)) & (entries.length() / 2 - 1)) * 2;
    }

    private static long word(byte[] bytes, int start) {
        long value = 0;
        for (int i = start; i < start + 8; i++) value = (value << 8) | (bytes[i] & 255L);
        return value;
    }

    private record Entry(long hi, long lo, long h2, long h3, JwtClaims claims,
                         long epoch, long insertedAt, long deadline, long retainedBytes, long insertedNanos) {
        boolean matches(long a, long b, long c, long d) {
            return hi == a && lo == b && h2 == c && h3 == d;
        }
    }

    private static final class HashWorker {
        final MessageDigest digest;
        final byte[] input;
        final byte[] hash = new byte[32];
        HashWorker(int maxTokenChars) throws GeneralSecurityException {
            digest = MessageDigest.getInstance("SHA-256");
            input = new byte[Math.min(AsymmetricJwtVerifier.ASCII_CHUNK_BYTES, maxTokenChars)];
        }
    }
}

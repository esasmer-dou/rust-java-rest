package com.reactor.rust.security;

import com.reactor.rust.exception.UnauthorizedException;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Explicit RS256/P-256 ES256/Ed25519 verifier. No network, TLS, dynamic key lookup or hot
 * ServiceLoader. JCA providers are selected and initialized at startup. Each bounded worker
 * owns its Signature instances; exhaustion is a shared stackless DependencyFailure (framework HTTP 503).
 * Signature verification and miss-path JSON/base64 parsing may allocate; this is not a
 * zero-allocation verifier. Cache wrapping is explicit and never changes the legacy HS256 path.
 */
public final class AsymmetricJwtVerifier implements JwtVerifier {
    static final int ASCII_CHUNK_BYTES = 256;
    private final JwtKeySet keys;
    private final JwtVerificationPolicy policy;
    private final Clock clock;
    private final JwtWorkPool<Worker> workers;
    private final AtomicLong signatureChecks = new AtomicLong();
    private final AtomicLong keyInitializations = new AtomicLong();

    public AsymmetricJwtVerifier(JwtKeySet keys, JwtVerificationPolicy policy, Clock clock, int maxWorkers) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        JwtWorkPool.checkSize(maxWorkers);
        Worker[] state = new Worker[maxWorkers];
        try {
            for (int i = 0; i < state.length; i++) state[i] = new Worker(keys, policy.maxTokenChars(), keyInitializations);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Configured JWT algorithms/keys are unavailable in this JCA provider", error);
        }
        workers = new JwtWorkPool<>(state);
    }

    public AsymmetricJwtVerifier(JwtKeySet keys, JwtVerificationPolicy policy) {
        this(keys, policy, Clock.systemUTC(), 16);
    }

    @Override
    public SecurityPrincipal verify(String token) { return verifyClaims(token).principal(); }

    public VerifiedTokenCache withCache(VerifiedTokenCache.Options options) {
        return new VerifiedTokenCache(this, options);
    }

    public long signatureVerificationCount() { return signatureChecks.get(); }
    public long keyInitializationCount() { return keyInitializations.get(); }

    JwtVerificationPolicy policy() { return policy; }
    long now() { return Math.floorDiv(clock.millis(), 1000L); }

    JwtClaims verifyClaims(String token) {
        checkTokenLength(token, policy.maxTokenChars());
        int slot = workers.acquire();
        Worker worker = workers.worker(slot);
        try {
            int first = token.indexOf('.');
            int second = first < 0 ? -1 : token.indexOf('.', first + 1);
            if (first <= 0 || second <= first + 1 || second == token.length() - 1
                    || token.indexOf('.', second + 1) >= 0) throw JwtVerificationPolicy.invalid();
            JwtJson.Header header = JwtJson.header(Hs256JwtVerifier.decodeCanonical(token.substring(0, first)),
                    policy.maxClaimChars());
            JwtKeySet.Key key = keys.find(header.kid());
            if (key == null || !key.algorithm().name().equals(header.algorithm())) throw JwtVerificationPolicy.invalid();
            byte[] signature = Hs256JwtVerifier.decodeCanonical(token.substring(second + 1));
            int expectedLength = key.algorithm() == JwtAlgorithm.RS256
                    ? (((RSAPublicKey) key.publicKey()).getModulus().bitLength() + 7) / 8 : 64;
            if (signature.length != expectedLength) throw JwtVerificationPolicy.invalid();
            int algorithm = key.algorithm().ordinal();
            Signature verifier = worker.signatures[algorithm];
            if (worker.activeKeys[algorithm] != key || worker.dirty[algorithm]) {
                try {
                    verifier.initVerify(key.publicKey());
                } catch (GeneralSecurityException | RuntimeException | Error failure) {
                    worker.dirty[algorithm] = true;
                    throw failure;
                }
                keyInitializations.incrementAndGet();
                worker.activeKeys[algorithm] = key;
            }
            // verify() resets to the initialized key on a normal true/false return (JCA).
            // Partial update/provider failures must reinitialize before any later message.
            worker.dirty[algorithm] = true;
            for (int start = 0; start < second; start += worker.input.length) {
                int length = copyAscii(token, start, Math.min(second, start + worker.input.length), worker.input);
                verifier.update(worker.input, 0, length);
            }
            signatureChecks.incrementAndGet();
            boolean valid = verifier.verify(signature);
            worker.dirty[algorithm] = false;
            if (!valid) throw JwtVerificationPolicy.invalid();
            byte[] payload = Hs256JwtVerifier.decodeCanonical(token.substring(first + 1, second));
            JwtJson.Claims claims = JwtJson.claims(payload, policy.subjectClaim(), policy.rolesClaim(),
                    policy.issuer(), policy.audience(), policy.maxClaimChars(), policy.maxRoles(), policy.maxClaims());
            return policy.accept(claims, now());
        } catch (UnauthorizedException error) {
            throw error;
        } catch (GeneralSecurityException | IllegalArgumentException | IndexOutOfBoundsException error) {
            throw JwtVerificationPolicy.invalid();
        } finally {
            Arrays.fill(worker.input, (byte) 0);
            workers.release(slot);
        }
    }

    static void checkTokenLength(String token, int maximum) {
        if (token == null || token.isEmpty() || token.length() > maximum) throw JwtVerificationPolicy.invalid();
    }

    static int copyAscii(String token, byte[] target) {
        return copyAscii(token, 0, token.length(), target);
    }

    static int copyAscii(String token, int start, int end, byte[] target) {
        for (int i = start; i < end; i++) {
            char value = token.charAt(i);
            if (value > 127) {
                Arrays.fill(target, 0, i - start, (byte) 0);
                throw JwtVerificationPolicy.invalid();
            }
            target[i - start] = (byte) value;
        }
        return end - start;
    }

    private static final class Worker {
        final byte[] input;
        final Signature[] signatures = new Signature[JwtAlgorithm.values().length];
        final JwtKeySet.Key[] activeKeys = new JwtKeySet.Key[JwtAlgorithm.values().length];
        final boolean[] dirty = new boolean[JwtAlgorithm.values().length];

        Worker(JwtKeySet keys, int maxTokenChars, AtomicLong initializations) throws GeneralSecurityException {
            input = new byte[Math.min(ASCII_CHUNK_BYTES, maxTokenChars)];
            for (JwtAlgorithm algorithm : JwtAlgorithm.values()) {
                JwtKeySet.Key key = keys.first(algorithm);
                if (key != null) {
                    Signature signature = Signature.getInstance(algorithm.signatureName);
                    signature.initVerify(key.publicKey());
                    initializations.incrementAndGet();
                    signatures[algorithm.ordinal()] = signature;
                    activeKeys[algorithm.ordinal()] = key;
                }
            }
        }
    }
}

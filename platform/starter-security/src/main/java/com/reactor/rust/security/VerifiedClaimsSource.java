package com.reactor.rust.security;

import javax.crypto.Mac;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Internal immutable trust/policy seam. Cached HS256 does not impose asymmetric-policy bounds. */
interface VerifiedClaimsSource {
    int maxTokenChars();
    long now();
    JwtClaims verifyClaims(String token);
    SecurityPrincipal verify(String token);
    void validateTime(JwtClaims claims, long now);
    default long macVerificationCount() { return 0; }

    final class Asymmetric implements VerifiedClaimsSource {
        private final AsymmetricJwtVerifier verifier;
        Asymmetric(AsymmetricJwtVerifier verifier) { this.verifier = Objects.requireNonNull(verifier, "verifier"); }
        @Override public int maxTokenChars() { return verifier.policy().maxTokenChars(); }
        @Override public long now() { return verifier.now(); }
        @Override public JwtClaims verifyClaims(String token) { return verifier.verifyClaims(token); }
        @Override public SecurityPrincipal verify(String token) { return verifier.verify(token); }
        @Override public void validateTime(JwtClaims claims, long now) { verifier.policy().validateTime(claims, now); }
    }

    final class Hmac implements VerifiedClaimsSource {
        private final Hs256JwtVerifier verifier;
        private final Clock clock;
        private final JwtWorkPool<Worker> workers;
        private final AtomicLong macCalls = new AtomicLong();

        Hmac(Hs256JwtVerifier verifier, Clock clock, int maxWorkers) {
            this.verifier = Objects.requireNonNull(verifier, "verifier");
            this.clock = Objects.requireNonNull(clock, "clock");
            JwtWorkPool.checkSize(maxWorkers);
            Worker[] state = new Worker[maxWorkers];
            for (int i = 0; i < state.length; i++) {
                state[i] = new Worker(verifier.newMacForCache(), new byte[maxTokenChars()]);
            }
            workers = new JwtWorkPool<>(state);
        }

        @Override public int maxTokenChars() { return verifier.maxTokenCharsForCache(); }
        @Override public long now() { return Math.floorDiv(clock.millis(), 1000L); }
        @Override public long macVerificationCount() { return macCalls.get(); }
        @Override public void validateTime(JwtClaims claims, long now) { verifier.validateCachedTime(claims, now); }
        @Override public SecurityPrincipal verify(String token) { return verifyClaims(token).principal(); }

        @Override public JwtClaims verifyClaims(String token) {
            AsymmetricJwtVerifier.checkTokenLength(token, maxTokenChars());
            int slot = workers.acquire();
            Worker worker = workers.worker(slot);
            int used = 0;
            try {
                used = AsymmetricJwtVerifier.copyAscii(token, worker.input);
                JwtClaims claims = verifier.verifyClaimsForCache(token, worker.mac, worker.input, macCalls);
                validateTime(claims, now());
                return claims;
            } finally {
                Arrays.fill(worker.input, 0, used, (byte) 0);
                try { worker.mac.reset(); }
                finally { workers.release(slot); }
            }
        }

        private record Worker(Mac mac, byte[] input) {}
    }
}

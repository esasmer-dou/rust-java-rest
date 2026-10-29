package com.reactor.rust.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

final class JwtTestSupport {
    private static volatile Object allocationControl;
    static final KeyPair RSA = pair("RSA", 2048);
    static final KeyPair EC = pair("EC", 256);
    static final KeyPair ED = pair("Ed25519", 0);
    static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private JwtTestSupport() {}

    static void requireFineAllocationCounter(com.sun.management.ThreadMXBean bean, long thread) {
        Object[] retained = new Object[100];
        allocationControl = retained;
        try {
            for (int batch = 0; batch < 8; batch++) {
                long before = bean.getThreadAllocatedBytes(thread);
                for (int index = 0; index < retained.length; index++) retained[index] = new byte[32];
                long bytes = bean.getThreadAllocatedBytes(thread) - before;
                org.junit.jupiter.api.Assumptions.assumeTrue(bytes >= 4000 && bytes <= 6400,
                        "Fine allocation accounting unavailable; profiling NOT qualified: " + bytes);
            }
        } finally { allocationControl = null; }
    }

    static KeyPair pair(String algorithm, int size) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
            if (algorithm.equals("EC")) generator.initialize(new ECGenParameterSpec(size == 256 ? "secp256r1" : "secp384r1"));
            else if (size != 0) generator.initialize(size);
            return generator.generateKeyPair();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    static JwtKeySet keys() {
        return new JwtKeySet(new JwtKeySet.Key("rsa", JwtAlgorithm.RS256, RSA.getPublic()),
                new JwtKeySet.Key("ec", JwtAlgorithm.ES256, EC.getPublic()),
                new JwtKeySet.Key("ed", JwtAlgorithm.EdDSA, ED.getPublic()));
    }

    static JwtVerificationPolicy policy() {
        return policy(true, "issuer", "api", 8192, 1024, 64, 64);
    }

    static JwtVerificationPolicy policy(boolean requireExpiration, String issuer, String audience,
                                        int tokenChars, int claimChars, int roles, int claims) {
        return new JwtVerificationPolicy(issuer, audience, "sub", "roles", 0, requireExpiration,
                tokenChars, claimChars, roles, claims);
    }

    static String payload(String subject, long expiry) {
        return "{\"sub\":\"" + subject + "\",\"roles\":[\"reader\"],\"iss\":\"issuer\",\"aud\":\"api\",\"exp\":" + expiry + "}";
    }

    static String token(JwtAlgorithm algorithm, String payload) throws Exception {
        String kid = switch (algorithm) { case RS256 -> "rsa"; case ES256 -> "ec"; case EdDSA -> "ed"; default -> "hs"; };
        KeyPair key = switch (algorithm) { case RS256 -> RSA; case ES256 -> EC; case EdDSA -> ED; default -> throw new IllegalArgumentException(); };
        return token("{\"alg\":\"" + algorithm.name() + "\",\"kid\":\"" + kid + "\",\"typ\":\"JWT\"}", payload,
                key, algorithm.signatureName);
    }

    static String token(String header, String payload, KeyPair key, String algorithm) throws Exception {
        return token(header.getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8), key, algorithm);
    }

    static String token(byte[] header, byte[] payload, KeyPair key, String algorithm) throws Exception {
        String input = ENCODER.encodeToString(header) + '.' + ENCODER.encodeToString(payload);
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign(key.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + '.' + ENCODER.encodeToString(signature.sign());
    }

    static String replaceSignature(String token, byte[] signature) {
        return token.substring(0, token.lastIndexOf('.') + 1) + ENCODER.encodeToString(signature);
    }

    static final class MutableClock extends Clock {
        private final AtomicLong second;
        MutableClock(long initial) { second = new AtomicLong(initial); }
        void set(long value) { second.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!zone.equals(ZoneOffset.UTC)) throw new UnsupportedOperationException();
            return this;
        }
        @Override public Instant instant() { return Instant.ofEpochSecond(second.get()); }
        @Override public long millis() { return Math.multiplyExact(second.get(), 1000L); }
    }
}

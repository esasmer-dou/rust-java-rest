package com.reactor.rust.security;

import com.reactor.rust.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;

import static com.reactor.rust.security.JwtTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class AsymmetricJwtVerifierTest {
    @Test
    void failedKeyInitializationCannotReuseAChangedProviderKeyUnderTheOldKid() throws Exception {
        var second = pair("RSA", 2048);
        var keySet = new JwtKeySet(new JwtKeySet.Key("first", JwtAlgorithm.RS256, RSA.getPublic()),
                new JwtKeySet.Key("second", JwtAlgorithm.RS256, second.getPublic()));
        var verifier = new AsymmetricJwtVerifier(keySet, policy(), new MutableClock(1000), 1);
        var field = AsymmetricJwtVerifier.class.getDeclaredField("workers"); field.setAccessible(true);
        Object worker = ((JwtWorkPool<?>) field.get(verifier)).worker(0);
        var engines = worker.getClass().getDeclaredField("signatures"); engines.setAccessible(true);
        var signatures = (java.security.Signature[]) engines.get(worker);
        var injected = new FailingInitialization(signatures[JwtAlgorithm.RS256.ordinal()]);
        injected.initVerify(RSA.getPublic());
        injected.failNext = true;
        signatures[JwtAlgorithm.RS256.ordinal()] = injected;
        String next = token("{\"alg\":\"RS256\",\"kid\":\"second\"}", payload("second", 1100), second, "SHA256withRSA");
        assertThrows(UnauthorizedException.class, () -> verifier.verify(next));
        String forged = token("{\"alg\":\"RS256\",\"kid\":\"first\"}", payload("wrong", 1100), second, "SHA256withRSA");
        assertThrows(UnauthorizedException.class, () -> verifier.verify(forged));
        String first = token("{\"alg\":\"RS256\",\"kid\":\"first\"}", payload("first", 1100), RSA, "SHA256withRSA");
        assertEquals("first", verifier.verify(first).subject());
    }

    private static final class FailingInitialization extends java.security.Signature {
        final java.security.Signature delegate;
        boolean failNext;
        FailingInitialization(java.security.Signature delegate) { super("SHA256withRSA"); this.delegate = delegate; }
        @Override protected void engineInitVerify(java.security.PublicKey key) throws java.security.InvalidKeyException {
            delegate.initVerify(key);
            if (failNext) { failNext = false; throw new java.security.InvalidKeyException("Injected failure AFTER provider key change"); }
        }
        @Override protected void engineInitSign(java.security.PrivateKey key) throws java.security.InvalidKeyException { delegate.initSign(key); }
        @Override protected void engineUpdate(byte value) throws java.security.SignatureException { delegate.update(value); }
        @Override protected void engineUpdate(byte[] value, int start, int length) throws java.security.SignatureException { delegate.update(value, start, length); }
        @Override protected byte[] engineSign() throws java.security.SignatureException { return delegate.sign(); }
        @Override protected boolean engineVerify(byte[] value) throws java.security.SignatureException { return delegate.verify(value); }
        @Override @Deprecated protected void engineSetParameter(String name, Object value) { throw new UnsupportedOperationException(); }
        @Override @Deprecated protected Object engineGetParameter(String name) { throw new UnsupportedOperationException(); }
    }

    @ParameterizedTest
    @EnumSource(value = JwtAlgorithm.class, names = {"RS256", "ES256", "EdDSA"})
    void samePinnedKeyReusesInitializedEngineButEveryUncachedCallStillVerifies(JwtAlgorithm algorithm) throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 1);
        String signed = token(algorithm, payload("person", 1100));
        long initial = verifier.keyInitializationCount();
        for (int i = 0; i < 1000; i++) assertEquals("person", verifier.verify(signed).subject());
        assertEquals(1000, verifier.signatureVerificationCount());
        assertEquals(initial, verifier.keyInitializationCount());
    }

    @ParameterizedTest
    @EnumSource(value = JwtAlgorithm.class, names = {"RS256", "ES256", "EdDSA"})
    void partialInputFailureReinitializesBeforeReusingTheSameKey(JwtAlgorithm algorithm) throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 1);
        String signed = token(algorithm, payload("u".repeat(900), 1100));
        verifier.verify(signed);
        long initial = verifier.keyInitializationCount();
        String nonAscii = signed.substring(0, 300) + "\u0130" + signed.substring(301);
        assertThrows(UnauthorizedException.class, () -> verifier.verify(nonAscii));
        assertEquals("u".repeat(900), verifier.verify(signed).subject());
        assertEquals(initial + 1, verifier.keyInitializationCount());
        assertEquals(2, verifier.signatureVerificationCount());
    }

    @Test
    void switchingTrustedKeysReinitializesAndDoesNotAcceptThePreviousKey() throws Exception {
        var second = pair("RSA", 2048);
        var keys = new JwtKeySet(new JwtKeySet.Key("first", JwtAlgorithm.RS256, RSA.getPublic()),
                new JwtKeySet.Key("second", JwtAlgorithm.RS256, second.getPublic()));
        var verifier = new AsymmetricJwtVerifier(keys, policy(), new MutableClock(1000), 1);
        String first = token("{\"alg\":\"RS256\",\"kid\":\"first\"}", payload("first", 1100), RSA, "SHA256withRSA");
        String next = token("{\"alg\":\"RS256\",\"kid\":\"second\"}", payload("second", 1100), second, "SHA256withRSA");
        for (int i = 0; i < 30; i++) {
            assertEquals("first", verifier.verify(first).subject());
            assertEquals("second", verifier.verify(next).subject());
        }
        assertEquals(60, verifier.keyInitializationCount());
        String wrong = token("{\"alg\":\"RS256\",\"kid\":\"first\"}", payload("wrong", 1100), second, "SHA256withRSA");
        assertThrows(UnauthorizedException.class, () -> verifier.verify(wrong));
        assertEquals("first", verifier.verify(first).subject());
        assertEquals(61, verifier.keyInitializationCount());
    }

    private AsymmetricJwtVerifier verifier() { return new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 2); }

    @ParameterizedTest
    @EnumSource(value = JwtAlgorithm.class, names = {"RS256", "ES256", "EdDSA"})
    void verifiesRealSignaturesAndImmutablePrincipal(JwtAlgorithm algorithm) throws Exception {
        var verifier = verifier();
        SecurityPrincipal principal = verifier.verify(token(algorithm, payload("person", 1100)));
        assertEquals("person", principal.subject());
        assertTrue(principal.hasRole("reader"));
        String[] roles = principal.roles();
        roles[0] = "admin";
        assertFalse(principal.hasRole("admin"));
        assertEquals(1, verifier.signatureVerificationCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"alg\":\"none\",\"kid\":\"rsa\"}",
            "{\"alg\":\"HS256\",\"kid\":\"rsa\"}",
            "{\"alg\":\"ES256\",\"kid\":\"rsa\"}",
            "{\"alg\":\"EdDSA\",\"kid\":\"rsa\"}",
            "{\"alg\":\"RS256\",\"kid\":\"unknown\"}",
            "{\"alg\":\"RS256\",\"kid\":\"\"}",
            "{\"alg\":\"RS256\"}",
            "{\"alg\":\"rs256\",\"kid\":\"rsa\"}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\",\"alg\":\"RS256\"}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\",\"kid\":\"rsa\"}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\",\"typ\":\"JWT\",\"typ\":\"JWT\"}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\",\"other\":1,\"other\":2}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\",\"crit\":[]}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\",\"crit\":[\"b64\"],\"b64\":false}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\",\"b64\":true}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\",\"jku\":\"https://127.0.0.1/keys\"}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\",\"x5u\":\"file:///secret\"}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\",\"jwk\":{}}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\",\"x5c\":[]}",
            "{\"alg\":\"RS256\",\"kid\":null}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\",}",
            "{\"alg\":\"RS256\",\"kid\":\"rsa\"}true"
    })
    void rejectsHeaderConfusionDuplicatesExtensionsAndMalformedJsonBeforeVerification(String header) throws Exception {
        var verifier = verifier();
        String signed = token(header, payload("user", 1100), RSA, "SHA256withRSA");
        assertThrows(UnauthorizedException.class, () -> verifier.verify(signed));
        assertEquals(0, verifier.signatureVerificationCount());
    }

    @Test
    void escapedDuplicateHeaderNameIsStillADuplicate() throws Exception {
        String header = "{\"alg\":\"RS256\",\"kid\":\"rsa\",\"a\\u006cg\":\"RS256\"}";
        assertThrows(UnauthorizedException.class, () -> verifier().verify(token(header, payload("user", 1100), RSA, "SHA256withRSA")));
    }

    @Test
    void rejectsWrongSigningKeyTamperingAndMalformedSignature() throws Exception {
        var verifier = verifier();
        KeyPair other = pair("RSA", 2048);
        String wrongKey = token("{\"alg\":\"RS256\",\"kid\":\"rsa\"}", payload("user", 1100), other, "SHA256withRSA");
        assertThrows(UnauthorizedException.class, () -> verifier.verify(wrongKey));
        String valid = token(JwtAlgorithm.RS256, payload("user", 1100));
        assertThrows(UnauthorizedException.class, () -> verifier.verify(replaceSignature(valid, new byte[1])));
        byte[] changed = Base64.getUrlDecoder().decode(valid.substring(valid.lastIndexOf('.') + 1));
        changed[0] ^= 1;
        assertThrows(UnauthorizedException.class, () -> verifier.verify(replaceSignature(valid, changed)));
        assertThrows(UnauthorizedException.class, () -> verifier.verify(valid + "="));
        assertThrows(UnauthorizedException.class, () -> verifier.verify(valid + ".extra"));
        assertThrows(UnauthorizedException.class, () -> verifier.verify("a.b.c"));
        assertThrows(UnauthorizedException.class, () -> verifier.verify(null));
    }

    @Test
    void rejectsHmacSignedWithPublicKeyBytesRatherThanConfusingRsaWithHs256() throws Exception {
        String input = ENCODER.encodeToString("{\"alg\":\"HS256\",\"kid\":\"rsa\"}".getBytes(StandardCharsets.UTF_8))
                + '.' + ENCODER.encodeToString(payload("attacker", 1100).getBytes(StandardCharsets.UTF_8));
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(RSA.getPublic().getEncoded(), "HmacSHA256"));
        String forged = input + '.' + ENCODER.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        var verifier = verifier();
        assertThrows(UnauthorizedException.class, () -> verifier.verify(forged));
        assertEquals(0, verifier.signatureVerificationCount());
    }

    @Test
    void clockSkewBoundariesAndExtremeNumericDatesDoNotOverflow() throws Exception {
        var policy = new JwtVerificationPolicy("", "", "sub", "roles", 10, true, 8192, 1024, 64, 64);
        var verifier = new AsymmetricJwtVerifier(keys(), policy, new MutableClock(1000), 1);
        assertEquals("u", verifier.verify(token(JwtAlgorithm.EdDSA,
                "{\"sub\":\"u\",\"exp\":991,\"nbf\":1010}")).subject());
        assertThrows(UnauthorizedException.class, () -> verifier.verify(token(JwtAlgorithm.EdDSA,
                "{\"sub\":\"u\",\"exp\":990}")));
        assertThrows(UnauthorizedException.class, () -> verifier.verify(token(JwtAlgorithm.EdDSA,
                "{\"sub\":\"u\",\"exp\":1100,\"nbf\":1011}")));
        assertEquals("u", verifier.verify(token(JwtAlgorithm.EdDSA,
                "{\"sub\":\"u\",\"exp\":9223372036854775807,\"nbf\":-9223372036854775808}")).subject());
        StringBuilder header = new StringBuilder("{\"alg\":\"RS256\",\"kid\":\"rsa\"");
        for (int i = 0; i < 31; i++) header.append(",\"h").append(i).append("\":0");
        header.append('}');
        assertThrows(UnauthorizedException.class, () -> verifier.verify(token(header.toString(),
                payload("u", 1100), RSA, "SHA256withRSA")));
    }

    @Test
    void millisecondClockFloorsNegativeEpochsAndChecksExactExpNbfBoundaries() throws Exception {
        long[][] examples = {{-1001, -2}, {-1000, -1}, {-999, -1}, {-1, -1}, {0, 0}, {1, 0}, {999, 0}, {1000, 1}};
        var policy = policy(true, "", "", 8192, 1024, 64, 64);
        String expiresAtZero = token(JwtAlgorithm.EdDSA, "{\"sub\":\"u\",\"exp\":0,\"nbf\":-1}");
        String startsAtZero = token(JwtAlgorithm.EdDSA, "{\"sub\":\"u\",\"exp\":10,\"nbf\":0}");
        for (long[] example : examples) {
            java.time.Clock clock = new java.time.Clock() {
                @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
                @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public java.time.Instant instant() { throw new AssertionError("Hot path must use millis(), not allocate Instant"); }
                @Override public long millis() { return example[0]; }
            };
            var verifier = new AsymmetricJwtVerifier(keys(), policy, clock, 1);
            assertEquals(example[1], verifier.now());
            if (example[1] == -1) assertEquals("u", verifier.verify(expiresAtZero).subject());
            else assertThrows(UnauthorizedException.class, () -> verifier.verify(expiresAtZero));
            if (example[1] >= 0) assertEquals("u", verifier.verify(startsAtZero).subject());
            else assertThrows(UnauthorizedException.class, () -> verifier.verify(startsAtZero));
        }
    }

    @Test
    void es256RejectsDerAndNon64ByteP1363BeforeSignatureVerify() throws Exception {
        var verifier = verifier();
        String der = token("{\"alg\":\"ES256\",\"kid\":\"ec\"}", payload("user", 1100), EC, "SHA256withECDSA");
        assertThrows(UnauthorizedException.class, () -> verifier.verify(der));
        String raw = token(JwtAlgorithm.ES256, payload("user", 1100));
        assertEquals(64, Base64.getUrlDecoder().decode(raw.substring(raw.lastIndexOf('.') + 1)).length);
        for (int bytes : new int[]{0, 1, 63, 65, 128}) {
            assertThrows(UnauthorizedException.class, () -> verifier.verify(replaceSignature(raw, new byte[bytes])));
        }
        assertEquals(0, verifier.signatureVerificationCount());
        verifier.verify(raw);
        assertEquals(1, verifier.signatureVerificationCount());
    }

    @Test
    void validatesIssuerAudienceExpiryNotBeforeAndMissingDates() throws Exception {
        var verifier = verifier();
        for (String bad : new String[]{
                "{\"sub\":\"u\",\"iss\":\"wrong\",\"aud\":\"api\",\"exp\":1100}",
                "{\"sub\":\"u\",\"iss\":\"issuer\",\"aud\":\"other\",\"exp\":1100}",
                "{\"sub\":\"u\",\"iss\":\"issuer\",\"aud\":\"api\",\"exp\":1000}",
                "{\"sub\":\"u\",\"iss\":\"issuer\",\"aud\":\"api\",\"exp\":1100,\"nbf\":1001}",
                "{\"sub\":\"u\",\"iss\":\"issuer\",\"aud\":\"api\"}",
                "{\"sub\":\"u\",\"iss\":\"issuer\",\"aud\":\"api\",\"exp\":-9223372036854775808}",
                "{\"sub\":\"u\",\"iss\":\"issuer\",\"aud\":\"api\",\"exp\":1100,\"nbf\":9223372036854775807}",
                "{\"sub\":\"u\",\"sub\":\"v\",\"iss\":\"issuer\",\"aud\":\"api\",\"exp\":1100}"}) {
            String signed = token(JwtAlgorithm.EdDSA, bad);
            assertThrows(UnauthorizedException.class, () -> verifier.verify(signed), bad);
        }
        String good = "{\"sub\":\"u\",\"iss\":\"issuer\",\"aud\":[\"other\",\"api\"],\"exp\":1100,\"nbf\":1000}";
        assertEquals("u", verifier.verify(token(JwtAlgorithm.EdDSA, good)).subject());
    }

    @Test
    void boundsTokenClaimsRolesHeaderAndUnicode() throws Exception {
        var policy = policy(true, "", "", 8192, 16, 2, 4);
        var verifier = new AsymmetricJwtVerifier(keys(), policy, new MutableClock(1000), 1);
        for (String bad : new String[]{
                "{\"sub\":\"" + "u".repeat(17) + "\",\"exp\":1100}",
                "{\"sub\":\"u\",\"exp\":1100,\"roles\":[\"a\",\"b\",\"c\"]}",
                "{\"sub\":\"u\",\"exp\":1100,\"x\":0,\"y\":0,\"z\":0}"}) {
            assertThrows(UnauthorizedException.class, () -> verifier.verify(token(JwtAlgorithm.EdDSA, bad)));
        }
        String unicode = "{\"sub\":\"\\u0130stanbul \\ud83d\\ude80\",\"exp\":1100,\"aud\":\"ignored\"}";
        assertEquals("\u0130stanbul \ud83d\ude80", verifier.verify(token(JwtAlgorithm.EdDSA, unicode)).subject());
        assertThrows(UnauthorizedException.class, () -> verifier.verify("x".repeat(8193)));
        assertThrows(UnauthorizedException.class, () -> verifier.verify("\u0130.a.b"));
        String oversizedKid = "{\"alg\":\"RS256\",\"kid\":\"" + "k".repeat(129) + "\"}";
        assertThrows(UnauthorizedException.class, () -> verifier().verify(token(oversizedKid, payload("u", 1100), RSA, "SHA256withRSA")));
    }

    @Test
    void rejectsMalformedUtf8RawControlsAndUnpairedSurrogates() throws Exception {
        var verifier = new AsymmetricJwtVerifier(keys(), policy(true, "", "", 8192, 1024, 64, 64), new MutableClock(1000), 1);
        for (String bad : new String[]{"{\"sub\":\"a\\n\nb\",\"exp\":1100}",
                "{\"sub\":\"\\ud800\",\"exp\":1100}", "{\"sub\":\"\\udc00\",\"exp\":1100}"}) {
            assertThrows(UnauthorizedException.class, () -> verifier.verify(token(JwtAlgorithm.EdDSA, bad)));
        }
        byte[] payload = "{\"sub\":\"x\",\"exp\":1100}".getBytes(StandardCharsets.UTF_8);
        payload[8] = (byte) 0xff;
        byte[] header = "{\"alg\":\"EdDSA\",\"kid\":\"ed\"}".getBytes(StandardCharsets.UTF_8);
        assertThrows(UnauthorizedException.class, () -> verifier.verify(token(header, payload, ED, "Ed25519")));
    }

    @Test
    void explicitProviderDefaultsToNoCacheAndIsInstanceBound() throws Exception {
        var provider = JwtVerifierProvider.asymmetric(keys(), policy(), new MutableClock(1000), 1, null);
        JwtVerifier first = provider.create();
        assertInstanceOf(AsymmetricJwtVerifier.class, first);
        assertNotSame(first, provider.create());
        var cachedProvider = JwtVerifierProvider.asymmetric(keys(), policy(), new MutableClock(1000), 1,
                new VerifiedTokenCache.Options(2, 1, 0));
        assertInstanceOf(VerifiedTokenCache.class, cachedProvider.create());
        assertNotSame(cachedProvider.create(), cachedProvider.create());
    }
}

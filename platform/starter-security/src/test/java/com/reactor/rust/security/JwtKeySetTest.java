package com.reactor.rust.security;

import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import static com.reactor.rust.security.JwtTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class JwtKeySetTest {
    @Test
    void rejectsTypeConfusionWeakRsaWrongCurveAndEd448() {
        for (JwtKeySet.Key key : new JwtKeySet.Key[]{
                new JwtKeySet.Key("x", JwtAlgorithm.HS256, RSA.getPublic()),
                new JwtKeySet.Key("x", JwtAlgorithm.RS256, EC.getPublic()),
                new JwtKeySet.Key("x", JwtAlgorithm.ES256, RSA.getPublic()),
                new JwtKeySet.Key("x", JwtAlgorithm.EdDSA, EC.getPublic()),
                new JwtKeySet.Key("x", JwtAlgorithm.RS256, pair("RSA", 1024).getPublic()),
                new JwtKeySet.Key("x", JwtAlgorithm.ES256, pair("EC", 384).getPublic()),
                new JwtKeySet.Key("x", JwtAlgorithm.EdDSA, pair("Ed448", 0).getPublic())}) {
            assertThrows(IllegalArgumentException.class, () -> new JwtKeySet(key), key.algorithm().name());
        }
    }

    @Test
    void enforcesKeyCapsAndUniqueKidsWithoutRetainingInputArray() {
        var original = new JwtKeySet.Key("ed", JwtAlgorithm.EdDSA, ED.getPublic());
        JwtKeySet.Key[] input = {original};
        var set = new JwtKeySet(input);
        input[0] = null;
        assertEquals(1, set.size());
        assertNotSame(original.publicKey(), set.find("ed").publicKey());
        assertArrayEquals(ED.getPublic().getEncoded(), set.find("ed").publicKey().getEncoded());
        assertThrows(IllegalArgumentException.class, () -> new JwtKeySet());
        assertThrows(IllegalArgumentException.class, () -> new JwtKeySet(original, original));
        assertThrows(IllegalArgumentException.class, () -> new JwtKeySet(new JwtKeySet.Key[65]));
        assertThrows(IllegalArgumentException.class, () -> new JwtKeySet.Key("", JwtAlgorithm.EdDSA, ED.getPublic()));
        assertThrows(IllegalArgumentException.class, () -> new JwtKeySet.Key("x".repeat(129), JwtAlgorithm.EdDSA, ED.getPublic()));
    }

    @Test
    void sameKidRotationRequiresNewVerifierAndCacheAndRejectsOldSignatures() throws Exception {
        var clock = new MutableClock(1000);
        var old = new AsymmetricJwtVerifier(new JwtKeySet(new JwtKeySet.Key("ed", JwtAlgorithm.EdDSA, ED.getPublic())),
                policy(), clock, 1).withCache(new VerifiedTokenCache.Options(2, 1, 0));
        String oldToken = token(JwtAlgorithm.EdDSA, payload("old", 1100));
        old.verify(oldToken);
        KeyPair next = pair("Ed25519", 0);
        var replacement = new AsymmetricJwtVerifier(new JwtKeySet(new JwtKeySet.Key("ed", JwtAlgorithm.EdDSA, next.getPublic())),
                policy(), clock, 1).withCache(new VerifiedTokenCache.Options(2, 1, 0));
        assertThrows(com.reactor.rust.exception.UnauthorizedException.class, () -> replacement.verify(oldToken));
        assertEquals(0, replacement.retainedEntryCount());
        String newToken = token("{\"alg\":\"EdDSA\",\"kid\":\"ed\"}", payload("new", 1100), next, "Ed25519");
        assertEquals("new", replacement.verify(newToken).subject());
        assertEquals("old", old.verify(oldToken).subject());
        assertNotSame(old, replacement);
        var changedPolicy = new AsymmetricJwtVerifier(keys(), policy(true, "another-issuer", "api", 8192, 1024, 64, 64),
                clock, 1).withCache(new VerifiedTokenCache.Options(2, 1, 0));
        assertThrows(com.reactor.rust.exception.UnauthorizedException.class, () -> changedPolicy.verify(oldToken));
    }

    @Test
    void rejectsInvalidPolicyAndWorkerBoundsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 3));
        assertThrows(IllegalArgumentException.class, () -> new AsymmetricJwtVerifier(keys(), policy(), new MutableClock(1000), 512));
        assertThrows(IllegalArgumentException.class, () -> policy(true, "", "", 65_537, 1024, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(true, "", "", 8192, 4097, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> policy(true, "", "", 8192, 1024, 257, 1));
        assertThrows(IllegalArgumentException.class, () -> new JwtVerificationPolicy("", "", "exp", "roles", 0, true, 8192, 1024, 64, 64));
        assertThrows(IllegalArgumentException.class, () -> new JwtVerificationPolicy("", "", "sub", "sub", 0, true, 8192, 1024, 64, 64));
    }
}

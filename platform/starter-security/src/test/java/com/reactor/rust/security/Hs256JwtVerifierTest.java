package com.reactor.rust.security;

import com.reactor.rust.exception.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Hs256JwtVerifierTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @AfterEach
    void clear() {
        System.clearProperty("reactor.security.jwt.hmac-secret");
        System.clearProperty("reactor.security.jwt.issuer");
        System.clearProperty("reactor.security.jwt.audience");
        System.clearProperty("reactor.security.jwt.require-expiration");
        System.clearProperty("reactor.security.jwt.max-token-chars");
    }

    @Test
    void verifiesSubjectRolesIssuerAudienceAndExpiry() throws Exception {
        System.setProperty("reactor.security.jwt.hmac-secret", SECRET);
        System.setProperty("reactor.security.jwt.issuer", "issuer-a");
        System.setProperty("reactor.security.jwt.audience", "orders-api");
        long expiry = Instant.now().getEpochSecond() + 120;
        String token = token("{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
                "{\"sub\":\"mustafa\",\"roles\":[\"reader\",\"admin\"],"
                        + "\"iss\":\"issuer-a\",\"aud\":[\"other\",\"orders-api\"],\"exp\":" + expiry + "}");

        SecurityPrincipal principal = Hs256JwtVerifier.fromProperties().verify(token);

        assertEquals("mustafa", principal.subject());
        assertTrue(principal.hasRole("admin"));
    }

    @Test
    void rejectsTamperedToken() throws Exception {
        System.setProperty("reactor.security.jwt.hmac-secret", SECRET);
        long expiry = Instant.now().getEpochSecond() + 120;
        String token = token("{\"alg\":\"HS256\"}", "{\"sub\":\"user\",\"exp\":" + expiry + "}");
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        assertThrows(UnauthorizedException.class, () -> Hs256JwtVerifier.fromProperties().verify(tampered));
    }

    @Test
    void requiresExpirationByDefault() throws Exception {
        System.setProperty("reactor.security.jwt.hmac-secret", SECRET);
        String token = token("{\"alg\":\"HS256\"}", "{\"sub\":\"user\"}");

        assertThrows(UnauthorizedException.class, () -> Hs256JwtVerifier.fromProperties().verify(token));
    }

    @Test
    void rejectsTokenAboveConfiguredBound() throws Exception {
        System.setProperty("reactor.security.jwt.hmac-secret", SECRET);
        System.setProperty("reactor.security.jwt.max-token-chars", "32");
        long expiry = Instant.now().getEpochSecond() + 120;
        String token = token("{\"alg\":\"HS256\"}", "{\"sub\":\"user\",\"exp\":" + expiry + "}");

        assertThrows(UnauthorizedException.class, () -> Hs256JwtVerifier.fromProperties().verify(token));
    }

    @Test
    void rejectsDuplicateSecurityClaim() throws Exception {
        System.setProperty("reactor.security.jwt.hmac-secret", SECRET);
        long expiry = Instant.now().getEpochSecond() + 120;
        String token = token("{\"alg\":\"HS256\"}",
                "{\"sub\":\"trusted\",\"sub\":\"attacker\",\"exp\":" + expiry + "}");

        assertThrows(UnauthorizedException.class, () -> Hs256JwtVerifier.fromProperties().verify(token));
    }

    @Test
    void rejectsDuplicateAlgorithmAndTrailingJson() throws Exception {
        System.setProperty("reactor.security.jwt.hmac-secret", SECRET);
        long expiry = Instant.now().getEpochSecond() + 120;
        String duplicateAlgorithm = token(
                "{\"alg\":\"HS256\",\"alg\":\"none\"}",
                "{\"sub\":\"user\",\"exp\":" + expiry + "}");
        String trailingPayload = token(
                "{\"alg\":\"HS256\"}",
                "{\"sub\":\"user\",\"exp\":" + expiry + "} true");

        Hs256JwtVerifier verifier = Hs256JwtVerifier.fromProperties();
        assertThrows(UnauthorizedException.class, () -> verifier.verify(duplicateAlgorithm));
        assertThrows(UnauthorizedException.class, () -> verifier.verify(trailingPayload));
    }

    @Test
    void rejectsMalformedUnknownNestedClaim() throws Exception {
        System.setProperty("reactor.security.jwt.hmac-secret", SECRET);
        long expiry = Instant.now().getEpochSecond() + 120;
        String token = token("{\"alg\":\"HS256\"}",
                "{\"sub\":\"user\",\"meta\":{\"items\":[1,]},\"exp\":" + expiry + "}");

        assertThrows(UnauthorizedException.class, () -> Hs256JwtVerifier.fromProperties().verify(token));
    }

    @Test
    void rejectsTrailingCommaInSignedClaims() throws Exception {
        System.setProperty("reactor.security.jwt.hmac-secret", SECRET);
        long expiry = Instant.now().getEpochSecond() + 120;
        String token = token("{\"alg\":\"HS256\"}",
                "{\"sub\":\"user\",\"exp\":" + expiry + ",}");

        assertThrows(UnauthorizedException.class, () -> Hs256JwtVerifier.fromProperties().verify(token));
    }

    @Test
    void canonicalBase64UrlChecksUnusedBitsWithoutReencoding() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        for (int length = 1; length <= 8; length++) {
            byte[] bytes = new byte[length];
            java.util.Arrays.fill(bytes, (byte) 0xff);
            String canonical = encoder.encodeToString(bytes);
            assertArrayEquals(bytes, Hs256JwtVerifier.decodeCanonical(canonical));
            if ((canonical.length() & 3) != 0) {
                int digit = alphabet.indexOf(canonical.charAt(canonical.length() - 1));
                String alias = canonical.substring(0, canonical.length() - 1) + alphabet.charAt(digit + 1);
                assertArrayEquals(bytes, Base64.getUrlDecoder().decode(alias));
                assertThrows(UnauthorizedException.class, () -> Hs256JwtVerifier.decodeCanonical(alias));
            }
        }
    }

    @Test
    void rejectsPaddingInvalidRemainderAndNonUrlAlphabet() {
        for (String invalid : new String[]{"", "A", "AAAAA", "AA==", "A+", "AA/", "AA\n"}) {
            assertThrows(RuntimeException.class, () -> Hs256JwtVerifier.decodeCanonical(invalid), invalid);
        }
    }

    @Test
    void consumesAudienceStringEvenWhenAudiencePolicyIsEmpty() throws Exception {
        System.setProperty("reactor.security.jwt.hmac-secret", SECRET);
        long expiry = Instant.now().getEpochSecond() + 120;
        String signed = token("{\"alg\":\"HS256\"}",
                "{\"sub\":\"u\",\"aud\":\"another-service\",\"exp\":" + expiry + "}");
        assertEquals("u", Hs256JwtVerifier.fromProperties().verify(signed).subject());
    }

    @Test
    void escapedPrefixDoesNotPermitRawControlCharactersAndUnicodeRemainsValid() throws Exception {
        System.setProperty("reactor.security.jwt.hmac-secret", SECRET);
        long expiry = Instant.now().getEpochSecond() + 120;
        for (int control = 0; control < 32; control++) {
            String signed = token("{\"alg\":\"HS256\"}",
                    "{\"sub\":\"a\\n" + (char) control + "b\",\"exp\":" + expiry + "}");
            assertThrows(UnauthorizedException.class, () -> Hs256JwtVerifier.fromProperties().verify(signed));
        }
        String good = token("{\"alg\":\"HS256\"}",
                "{\"sub\":\"\\u0130stanbul \ud83d\ude80\",\"exp\":" + expiry + "}");
        assertEquals("\u0130stanbul \ud83d\ude80", Hs256JwtVerifier.fromProperties().verify(good).subject());
    }

    @Test
    void explicitMinimumLongExpirationCannotMasqueradeAsAnAbsentClaim() throws Exception {
        System.setProperty("reactor.security.jwt.hmac-secret", SECRET);
        System.setProperty("reactor.security.jwt.require-expiration", "false");
        String absent = token("{\"alg\":\"HS256\"}", "{\"sub\":\"u\"}");
        assertEquals("u", Hs256JwtVerifier.fromProperties().verify(absent).subject());
        String expired = token("{\"alg\":\"HS256\"}", "{\"sub\":\"u\",\"exp\":-9223372036854775808}");
        assertThrows(UnauthorizedException.class, () -> Hs256JwtVerifier.fromProperties().verify(expired));
    }

    @Test
    void sharedHeaderParserRejectsDuplicatesAndUnsupportedJoseExtensionsWithoutRequiringHmacKid() throws Exception {
        System.setProperty("reactor.security.jwt.hmac-secret", SECRET);
        String payload = "{\"sub\":\"u\",\"exp\":" + (Instant.now().getEpochSecond() + 120) + "}";
        for (String header : new String[]{
                "{\"alg\":\"HS256\",\"crit\":[\"unknown\"]}",
                "{\"alg\":\"HS256\",\"b64\":false}",
                "{\"alg\":\"HS256\",\"b64\":true}",
                "{\"alg\":\"HS256\",\"kid\":\"a\",\"kid\":\"b\"}",
                "{\"alg\":\"HS256\",\"typ\":\"JWT\",\"typ\":\"JWT\"}",
                "{\"alg\":\"HS256\",\"jku\":\"https://localhost/keys\"}"}) {
            assertThrows(UnauthorizedException.class, () -> Hs256JwtVerifier.fromProperties().verify(token(header, payload)));
        }
        assertEquals("u", Hs256JwtVerifier.fromProperties().verify(token("{\"alg\":\"HS256\"}", payload)).subject());
    }

    private static String token(String header, String payload) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String encodedHeader = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + '.' + encodedPayload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return signingInput + '.' + encoder.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
    }
}

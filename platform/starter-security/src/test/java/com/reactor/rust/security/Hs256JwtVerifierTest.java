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

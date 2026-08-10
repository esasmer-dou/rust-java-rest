package com.reactor.rust.security;

import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.exception.UnauthorizedException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;

/** Minimal HS256 verifier with issuer, audience, expiry, not-before, subject, and role checks. */
final class Hs256JwtVerifier implements JwtVerifier {
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecretKeySpec key;
    private final String issuer;
    private final String audience;
    private final String rolesClaim;
    private final String subjectClaim;
    private final long clockSkewSeconds;
    private final int maxTokenChars;
    private final boolean requireExpiration;
    private final ThreadLocal<Mac> mac;

    private Hs256JwtVerifier(
            byte[] secret,
            String issuer,
            String audience,
            String rolesClaim,
            String subjectClaim,
            long clockSkewSeconds,
            int maxTokenChars,
            boolean requireExpiration) {
        this.key = new SecretKeySpec(secret, "HmacSHA256");
        this.issuer = issuer;
        this.audience = audience;
        this.rolesClaim = rolesClaim;
        this.subjectClaim = subjectClaim;
        this.clockSkewSeconds = clockSkewSeconds;
        this.maxTokenChars = maxTokenChars;
        this.requireExpiration = requireExpiration;
        this.mac = ThreadLocal.withInitial(this::newMac);
    }

    static Hs256JwtVerifier fromProperties() {
        String secret = PropertiesLoader.require("reactor.security.jwt.hmac-secret");
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("reactor.security.jwt.hmac-secret must contain at least 32 UTF-8 bytes");
        }
        String rolesClaim = claimName(
                PropertiesLoader.get("reactor.security.jwt.roles-claim", "roles"),
                "roles",
                "reactor.security.jwt.roles-claim");
        String subjectClaim = claimName(
                PropertiesLoader.get("reactor.security.jwt.subject-claim", "sub"),
                "sub",
                "reactor.security.jwt.subject-claim");
        if (rolesClaim.equals(subjectClaim)) {
            throw new IllegalStateException("JWT subject and roles claims must use different names");
        }
        return new Hs256JwtVerifier(
                secretBytes,
                trim(PropertiesLoader.get("reactor.security.jwt.issuer", "")),
                trim(PropertiesLoader.get("reactor.security.jwt.audience", "")),
                rolesClaim,
                subjectClaim,
                Math.max(0L, PropertiesLoader.getLong("reactor.security.jwt.clock-skew-seconds", 30L)),
                positive(PropertiesLoader.getInt("reactor.security.jwt.max-token-chars", 8_192),
                        "reactor.security.jwt.max-token-chars"),
                PropertiesLoader.getBoolean("reactor.security.jwt.require-expiration", true)
        );
    }

    @Override
    public SecurityPrincipal verify(String token) {
        try {
            if (token == null || token.length() > maxTokenChars) throw invalid();
            int firstDot = token.indexOf('.');
            int secondDot = firstDot < 0 ? -1 : token.indexOf('.', firstDot + 1);
            if (firstDot <= 0 || secondDot <= firstDot + 1 || secondDot == token.length() - 1
                    || token.indexOf('.', secondDot + 1) >= 0) {
                throw invalid();
            }

            byte[] headerBytes = decodeCanonical(token.substring(0, firstDot));
            String algorithm = Json.objectString(headerBytes, "alg");
            if (!"HS256".equals(algorithm)) throw invalid();

            byte[] expected = sign(token.substring(0, secondDot));
            byte[] actual = decodeCanonical(token.substring(secondDot + 1));
            if (!MessageDigest.isEqual(expected, actual)) throw invalid();

            byte[] payload = decodeCanonical(token.substring(firstDot + 1, secondDot));
            Claims claims = Json.claims(payload, subjectClaim, rolesClaim, issuer, audience);
            long now = Instant.now().getEpochSecond();
            if (requireExpiration && claims.expiry == Long.MIN_VALUE) throw invalid();
            if (claims.expiry != Long.MIN_VALUE && now - clockSkewSeconds >= claims.expiry) throw invalid();
            if (claims.notBefore != Long.MIN_VALUE && now + clockSkewSeconds < claims.notBefore) throw invalid();
            if (!issuer.isEmpty() && !issuer.equals(claims.issuer)) throw invalid();
            if (!audience.isEmpty() && !claims.audienceMatched) throw invalid();
            if (claims.subject == null || claims.subject.isBlank()) throw invalid();
            return new SecurityPrincipal(claims.subject, claims.roles);
        } catch (UnauthorizedException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    private byte[] sign(String input) {
        Mac local = mac.get();
        local.reset();
        return local.doFinal(input.getBytes(StandardCharsets.US_ASCII));
    }

    private Mac newMac() {
        try {
            Mac value = Mac.getInstance("HmacSHA256");
            value.init(key);
            return value;
        } catch (Exception error) {
            throw new IllegalStateException("HmacSHA256 is unavailable", error);
        }
    }

    private static byte[] decodeCanonical(String value) {
        if (value.isEmpty() || value.indexOf('=') >= 0 || (value.length() & 3) == 1) throw invalid();
        byte[] decoded = URL_DECODER.decode(value);
        if (!URL_ENCODER.encodeToString(decoded).equals(value)) throw invalid();
        return decoded;
    }

    private static int positive(int value, String key) {
        if (value <= 0) throw new IllegalArgumentException(key + " must be > 0");
        return value;
    }

    private static UnauthorizedException invalid() {
        return new UnauthorizedException("Bearer token is invalid or expired");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nonBlank(String value, String fallback) {
        String normalized = trim(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String claimName(String value, String fallback, String key) {
        String normalized = nonBlank(value, fallback);
        if ("iss".equals(normalized) || "aud".equals(normalized)
                || "exp".equals(normalized) || "nbf".equals(normalized)) {
            throw new IllegalStateException(key + " must not shadow a registered JWT claim");
        }
        return normalized;
    }

    private static final class Claims {
        private String subject;
        private String issuer;
        private String[] roles = new String[0];
        private long expiry = Long.MIN_VALUE;
        private long notBefore = Long.MIN_VALUE;
        private boolean audienceMatched;
        private boolean subjectSeen;
        private boolean rolesSeen;
        private boolean issuerSeen;
        private boolean audienceSeen;
        private boolean expirySeen;
        private boolean notBeforeSeen;
    }

    /** Small strict parser for signed JWT objects; it intentionally is not a general JSON API. */
    private static final class Json {
        private Json() {}

        static String objectString(byte[] jsonBytes, String wantedKey) {
            Cursor cursor = new Cursor(jsonBytes);
            String result = null;
            boolean found = false;
            cursor.space();
            cursor.expect('{');
            while (true) {
                cursor.space();
                if (cursor.take('}')) break;
                String key = cursor.string();
                cursor.space();
                cursor.expect(':');
                cursor.space();
                if (wantedKey.equals(key)) {
                    if (found) throw new IllegalArgumentException("Duplicate JWT JSON member: " + wantedKey);
                    result = cursor.string();
                    found = true;
                } else {
                    cursor.skipValue();
                }
                cursor.space();
                if (cursor.take('}')) break;
                cursor.expect(',');
                cursor.rejectNext('}');
            }
            cursor.end();
            return result;
        }

        static Claims claims(
                byte[] jsonBytes,
                String subjectClaim,
                String rolesClaim,
                String expectedIssuer,
                String expectedAudience) {
            Cursor cursor = new Cursor(jsonBytes);
            Claims claims = new Claims();
            cursor.space();
            cursor.expect('{');
            while (true) {
                cursor.space();
                if (cursor.take('}')) break;
                String key = cursor.string();
                cursor.space();
                cursor.expect(':');
                cursor.space();
                if (subjectClaim.equals(key)) {
                    duplicate(claims.subjectSeen, key);
                    claims.subjectSeen = true;
                    claims.subject = cursor.stringOrNull();
                } else if (rolesClaim.equals(key)) {
                    duplicate(claims.rolesSeen, key);
                    claims.rolesSeen = true;
                    claims.roles = cursor.strings();
                } else if ("iss".equals(key)) {
                    duplicate(claims.issuerSeen, key);
                    claims.issuerSeen = true;
                    claims.issuer = cursor.stringOrNull();
                } else if ("aud".equals(key)) {
                    duplicate(claims.audienceSeen, key);
                    claims.audienceSeen = true;
                    claims.audienceMatched = cursor.matchesStringOrArray(expectedAudience);
                } else if ("exp".equals(key)) {
                    duplicate(claims.expirySeen, key);
                    claims.expirySeen = true;
                    claims.expiry = cursor.longValue();
                } else if ("nbf".equals(key)) {
                    duplicate(claims.notBeforeSeen, key);
                    claims.notBeforeSeen = true;
                    claims.notBefore = cursor.longValue();
                } else {
                    cursor.skipValue();
                }
                cursor.space();
                if (cursor.take('}')) break;
                cursor.expect(',');
                cursor.rejectNext('}');
            }
            cursor.end();
            return claims;
        }

        private static void duplicate(boolean seen, String key) {
            if (seen) throw new IllegalArgumentException("Duplicate JWT claim: " + key);
        }
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int index;

        private Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        private void space() {
            while (index < bytes.length) {
                byte value = bytes[index];
                if (value != ' ' && value != '\n' && value != '\r' && value != '\t') return;
                index++;
            }
        }

        private boolean take(char expected) {
            if (index < bytes.length && bytes[index] == (byte) expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw new IllegalArgumentException("Invalid JWT JSON");
        }

        private void end() {
            space();
            if (index != bytes.length) throw new IllegalArgumentException("Trailing JWT JSON data");
        }

        private void rejectNext(char forbidden) {
            space();
            if (index < bytes.length && bytes[index] == (byte) forbidden) {
                throw new IllegalArgumentException("Trailing comma in JWT JSON");
            }
        }

        private String stringOrNull() {
            if (literal("null")) return null;
            return string();
        }

        private String string() {
            expect('"');
            int start = index;
            StringBuilder escaped = null;
            while (index < bytes.length) {
                int value = bytes[index++] & 0xff;
                if (value == '"') {
                    if (escaped == null) {
                        return new String(bytes, start, index - start - 1, StandardCharsets.UTF_8);
                    }
                    return escaped.toString();
                }
                if (value == '\\') {
                    if (escaped == null) {
                        escaped = new StringBuilder(index - start + 16);
                        escaped.append(new String(bytes, start, index - start - 1, StandardCharsets.UTF_8));
                    }
                    if (index >= bytes.length) throw new IllegalArgumentException("Invalid JWT JSON escape");
                    int escape = bytes[index++] & 0xff;
                    switch (escape) {
                        case '"', '\\', '/' -> escaped.append((char) escape);
                        case 'b' -> escaped.append('\b');
                        case 'f' -> escaped.append('\f');
                        case 'n' -> escaped.append('\n');
                        case 'r' -> escaped.append('\r');
                        case 't' -> escaped.append('\t');
                        case 'u' -> escaped.append(unicode());
                        default -> throw new IllegalArgumentException("Invalid JWT JSON escape");
                    }
                    start = index;
                } else if (escaped != null && value >= 0x80) {
                    int sequenceStart = index - 1;
                    while (index < bytes.length && (bytes[index] & 0xc0) == 0x80) index++;
                    escaped.append(new String(bytes, sequenceStart, index - sequenceStart, StandardCharsets.UTF_8));
                    start = index;
                } else if (escaped != null) {
                    escaped.append((char) value);
                    start = index;
                } else if (value < 0x20) {
                    throw new IllegalArgumentException("Invalid JWT JSON string");
                }
            }
            throw new IllegalArgumentException("Unterminated JWT JSON string");
        }

        private char unicode() {
            if (index + 4 > bytes.length) throw new IllegalArgumentException("Invalid JWT JSON unicode escape");
            int value = 0;
            for (int count = 0; count < 4; count++) {
                int digit = Character.digit((char) bytes[index++], 16);
                if (digit < 0) throw new IllegalArgumentException("Invalid JWT JSON unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private String[] strings() {
            if (index >= bytes.length) throw new IllegalArgumentException("Invalid JWT string claim");
            if (bytes[index] == '"') return new String[] {string()};
            if (literal("null")) return new String[0];
            expect('[');
            ArrayList<String> values = new ArrayList<>(4);
            while (true) {
                space();
                if (take(']')) return values.toArray(String[]::new);
                values.add(string());
                space();
                if (take(']')) return values.toArray(String[]::new);
                expect(',');
                rejectNext(']');
            }
        }

        private boolean matchesStringOrArray(String expected) {
            if (index >= bytes.length) throw new IllegalArgumentException("Invalid JWT audience claim");
            if (bytes[index] == '"') return expected.isEmpty() || expected.equals(string());
            if (literal("null")) return expected.isEmpty();
            expect('[');
            boolean matched = expected.isEmpty();
            while (true) {
                space();
                if (take(']')) return matched;
                if (expected.equals(string())) matched = true;
                space();
                if (take(']')) return matched;
                expect(',');
                rejectNext(']');
            }
        }

        private long longValue() {
            int start = index;
            if (index < bytes.length && bytes[index] == '-') index++;
            int digits = index;
            while (index < bytes.length && bytes[index] >= '0' && bytes[index] <= '9') index++;
            if (digits == index || (index - digits > 1 && bytes[digits] == '0')) {
                throw new IllegalArgumentException("Invalid JWT numeric claim");
            }
            return Long.parseLong(new String(bytes, start, index - start, StandardCharsets.US_ASCII));
        }

        private boolean literal(String value) {
            byte[] literal = value.getBytes(StandardCharsets.US_ASCII);
            if (index + literal.length > bytes.length) return false;
            for (int i = 0; i < literal.length; i++) {
                if (bytes[index + i] != literal[i]) return false;
            }
            index += literal.length;
            return true;
        }

        private void skipValue() {
            skipValue(0);
        }

        private void skipValue(int depth) {
            if (depth > 16) throw new IllegalArgumentException("JWT JSON nesting is too deep");
            space();
            if (index >= bytes.length) throw new IllegalArgumentException("Invalid JWT JSON");
            byte value = bytes[index];
            if (value == '"') {
                string();
                return;
            }
            if (take('{')) {
                space();
                if (take('}')) return;
                while (true) {
                    space();
                    string();
                    space();
                    expect(':');
                    skipValue(depth + 1);
                    space();
                    if (take('}')) return;
                    expect(',');
                }
            }
            if (take('[')) {
                space();
                if (take(']')) return;
                while (true) {
                    skipValue(depth + 1);
                    space();
                    if (take(']')) return;
                    expect(',');
                }
            }
            if (literal("true") || literal("false") || literal("null")) return;
            skipNumber();
        }

        private void skipNumber() {
            if (take('-') && index >= bytes.length) throw new IllegalArgumentException("Invalid JWT JSON number");
            if (take('0')) {
                if (index < bytes.length && bytes[index] >= '0' && bytes[index] <= '9') {
                    throw new IllegalArgumentException("Invalid JWT JSON number");
                }
            } else {
                int start = index;
                while (index < bytes.length && bytes[index] >= '0' && bytes[index] <= '9') index++;
                if (start == index) throw new IllegalArgumentException("Invalid JWT JSON value");
            }
            if (take('.')) {
                int start = index;
                while (index < bytes.length && bytes[index] >= '0' && bytes[index] <= '9') index++;
                if (start == index) throw new IllegalArgumentException("Invalid JWT JSON number");
            }
            if (index < bytes.length && (bytes[index] == 'e' || bytes[index] == 'E')) {
                index++;
                if (index < bytes.length && (bytes[index] == '+' || bytes[index] == '-')) index++;
                int start = index;
                while (index < bytes.length && bytes[index] >= '0' && bytes[index] <= '9') index++;
                if (start == index) throw new IllegalArgumentException("Invalid JWT JSON number");
            }
        }
    }
}

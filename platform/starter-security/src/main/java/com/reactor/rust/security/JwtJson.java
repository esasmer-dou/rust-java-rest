package com.reactor.rust.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Shared strict signed-JWT parser; not a general JSON API. */
final class JwtJson {
    private JwtJson() {}

    record Header(String algorithm, String kid) {}

    static Header header(byte[] bytes, int maxStringChars) {
        return header(bytes, maxStringChars, true);
    }

    private static Header header(byte[] bytes, int maxStringChars, boolean requireKid) {
        Cursor cursor = new Cursor(bytes, maxStringChars, 1);
        String[] names = new String[32];
        int count = 0;
        String algorithm = null;
        String kid = null;
        cursor.space();
        cursor.expect('{');
        while (true) {
            cursor.space();
            if (cursor.take('}')) break;
            String name = cursor.string();
            if (count == names.length) throw new IllegalArgumentException("Too many JWT header members");
            for (int i = 0; i < count; i++) duplicate(name.equals(names[i]), name);
            names[count++] = name;
            cursor.space();
            cursor.expect(':');
            cursor.space();
            switch (name) {
                case "alg" -> algorithm = cursor.string();
                case "kid" -> kid = cursor.string();
                case "typ" -> cursor.string();
                case "crit", "b64", "jku", "x5u", "jwk", "x5c" ->
                        throw new IllegalArgumentException("Unsupported JWT header extension");
                default -> cursor.skipValue();
            }
            cursor.space();
            if (cursor.take('}')) break;
            cursor.expect(',');
            cursor.rejectNext('}');
        }
        cursor.end();
        if (algorithm == null || (requireKid && (kid == null || kid.isBlank() || kid.length() > JwtKeySet.MAX_KID_CHARS))) {
            throw new IllegalArgumentException("JWT alg and bounded kid are required");
        }
        return new Header(algorithm, kid);
    }

    static final class Claims {
        String subject;
        String issuer;
        String[] roles = new String[0];
        long expiry = Long.MIN_VALUE;
        long notBefore = Long.MIN_VALUE;
        boolean audienceMatched;
        boolean subjectSeen;
        boolean rolesSeen;
        boolean issuerSeen;
        boolean audienceSeen;
        boolean expirySeen;
        boolean notBeforeSeen;
    }

    /** Small strict parser for signed JWT objects; it intentionally is not a general JSON API. */
    static String objectString(byte[] jsonBytes, String wantedKey) {
        // Legacy HS256 still uses one configured secret and does not require kid, but must
        // reject ambiguous/unsupported JOSE headers through the same strict parser.
        if (wantedKey.equals("alg")) return header(jsonBytes, jsonBytes.length, false).algorithm();
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
        return claims(jsonBytes, subjectClaim, rolesClaim, expectedIssuer, expectedAudience,
                jsonBytes.length, jsonBytes.length, jsonBytes.length);
    }

    static Claims claims(byte[] jsonBytes, String subjectClaim, String rolesClaim,
                         String expectedIssuer, String expectedAudience,
                         int maxStringChars, int maxRoles, int maxClaims) {
        Cursor cursor = new Cursor(jsonBytes, maxStringChars, maxRoles);
        Claims claims = new Claims();
        int count = 0;
        cursor.space();
        cursor.expect('{');
        while (true) {
            cursor.space();
            if (cursor.take('}')) break;
            if (++count > maxClaims) throw new IllegalArgumentException("Too many JWT claims");
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
    private static final class Cursor {
        private final byte[] bytes;
        private final int maxStringChars;
        private final int maxRoles;
        private int index;

        private Cursor(byte[] bytes) {
            this(bytes, bytes.length, bytes.length);
        }

        private Cursor(byte[] bytes, int maxStringChars, int maxRoles) {
            this.bytes = bytes;
            this.maxStringChars = maxStringChars;
            this.maxRoles = maxRoles;
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
            int chars = 0;
            StringBuilder escaped = null;
            while (index < bytes.length) {
                int value = bytes[index++] & 0xff;
                if (value == '"') {
                    if (escaped == null) {
                        return new String(bytes, start, index - start - 1, StandardCharsets.UTF_8);
                    }
                    return escaped.toString();
                }
                if (value < 0x20) throw new IllegalArgumentException("Invalid JWT JSON string");
                if (value == '\\') {
                    if (escaped == null) {
                        escaped = new StringBuilder(Math.min(maxStringChars, index - start + 16));
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
                        case 'u' -> {
                            char ch = unicode();
                            escaped.append(ch);
                            if (Character.isHighSurrogate(ch)) {
                                expect('\\');
                                expect('u');
                                char low = unicode();
                                if (!Character.isLowSurrogate(low)) throw new IllegalArgumentException("Invalid JSON surrogate");
                                escaped.append(low);
                            } else if (Character.isLowSurrogate(ch)) {
                                throw new IllegalArgumentException("Invalid JSON surrogate");
                            }
                        }
                        default -> throw new IllegalArgumentException("Invalid JWT JSON escape");
                    }
                    chars = escaped.length();
                } else if (value >= 0x80) {
                    int sequenceStart = index - 1;
                    int width = value >= 0xc2 && value <= 0xdf ? 2
                            : value >= 0xe0 && value <= 0xef ? 3
                            : value >= 0xf0 && value <= 0xf4 ? 4 : 0;
                    if (width == 0 || sequenceStart > bytes.length - width) throw new IllegalArgumentException("Invalid UTF-8");
                    int cp = value & (0x7f >> width);
                    for (int i = 1; i < width; i++) {
                        int next = bytes[index++] & 255;
                        if ((next & 0xc0) != 0x80) throw new IllegalArgumentException("Invalid UTF-8");
                        cp = (cp << 6) | (next & 63);
                    }
                    if ((width == 2 && cp < 0x80) || (width == 3 && cp < 0x800)
                            || (width == 4 && cp < 0x10000) || cp > 0x10ffff
                            || (cp >= 0xd800 && cp <= 0xdfff)) throw new IllegalArgumentException("Invalid UTF-8 scalar");
                    chars += Character.charCount(cp);
                    if (escaped != null) escaped.append(new String(bytes, sequenceStart, width, StandardCharsets.UTF_8));
                } else {
                    chars++;
                    if (escaped != null) escaped.append((char) value);
                }
                if (chars > maxStringChars) throw new IllegalArgumentException("JWT string claim exceeds bound");
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
                if (values.size() >= maxRoles) throw new IllegalArgumentException("Too many JWT roles");
                values.add(string());
                space();
                if (take(']')) return values.toArray(String[]::new);
                expect(',');
                rejectNext(']');
            }
        }

        private boolean matchesStringOrArray(String expected) {
            if (index >= bytes.length) throw new IllegalArgumentException("Invalid JWT audience claim");
            if (bytes[index] == '"') {
                String actual = string();
                return expected.isEmpty() || expected.equals(actual);
            }
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
            if (index + value.length() > bytes.length) return false;
            for (int i = 0; i < value.length(); i++) {
                if (bytes[index + i] != value.charAt(i)) return false;
            }
            index += value.length();
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

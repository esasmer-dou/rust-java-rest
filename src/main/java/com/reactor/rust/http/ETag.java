package com.reactor.rust.http;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Utility class for ETag generation and comparison.
 * Supports both weak and strong ETags.
 */
public final class ETag {

    private final String value;
    private final boolean weak;

    private ETag(String value, boolean weak) {
        this.value = value;
        this.weak = weak;
    }

    /**
     * Get the ETag value (without quotes).
     */
    public String getValue() {
        return value;
    }

    /**
     * Check if this is a weak ETag.
     */
    public boolean isWeak() {
        return weak;
    }

    /**
     * Create a strong ETag.
     */
    public static ETag strong(String value) {
        return new ETag(value, false);
    }

    /**
     * Create a weak ETag.
     * Weak ETags indicate semantic equivalence, not byte-for-byte equality.
     */
    public static ETag weak(String value) {
        return new ETag(value, true);
    }

    /**
     * Generate ETag from content bytes using MD5.
     */
    public static ETag fromContent(byte[] content) {
        return fromContent(content, false);
    }

    /**
     * Generate ETag from content bytes.
     * @param content The content bytes
     * @param weak Whether to generate a weak ETag
     */
    public static ETag fromContent(byte[] content, boolean weak) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content);
            return new ETag(bytesToHex(digest).substring(0, 16), weak);
        } catch (NoSuchAlgorithmException e) {
            // Fallback to hash code
            return new ETag(Integer.toHexString(Objects.hash(content)), weak);
        }
    }

    /**
     * Generate ETag from content and last modified timestamp.
     * This is useful for cache validation.
     */
    public static ETag fromContentAndTimestamp(byte[] content, long lastModified) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(content);
            md.update(Long.toString(lastModified).getBytes());
            byte[] digest = md.digest();
            return new ETag(bytesToHex(digest).substring(0, 16), false);
        } catch (NoSuchAlgorithmException e) {
            return new ETag(Long.toHexString(lastModified), false);
        }
    }

    /**
     * Check if the client's If-None-Match header matches this ETag.
     * Used for conditional GET requests (304 Not Modified).
     *
     * @param ifNoneMatch The If-None-Match header value (can be * for wildcard)
     * @return true if matches (client has current version)
     */
    public boolean matchesIfNoneMatch(String ifNoneMatch) {
        if (ifNoneMatch == null || ifNoneMatch.isEmpty()) {
            return false;
        }

        // Wildcard matches everything
        if ("*".equals(ifNoneMatch.trim())) {
            return true;
        }

        // Parse the header - can contain multiple ETags
        String[] etags = ifNoneMatch.split(",");
        for (String etag : etags) {
            etag = etag.trim();
            if (matchesETagHeader(etag)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if the client's If-Match header matches this ETag.
     * Used for conditional PUT/PATCH requests (optimistic locking).
     *
     * @param ifMatch The If-Match header value (can be * for wildcard)
     * @return true if matches (client can proceed)
     */
    public boolean matchesIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isEmpty()) {
            return true; // No If-Match header means proceed
        }

        // Wildcard matches everything
        if ("*".equals(ifMatch.trim())) {
            return true;
        }

        // Parse the header - can contain multiple ETags
        String[] etags = ifMatch.split(",");
        for (String etag : etags) {
            etag = etag.trim();
            if (matchesETagHeader(etag)) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesETagHeader(String headerValue) {
        boolean isWeak = headerValue.startsWith("W/");
        String value = headerValue;

        if (isWeak) {
            value = value.substring(2);
        }

        // Remove quotes
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }

        // Weak comparison: only compare values
        if (this.weak || isWeak) {
            return this.value.equals(value);
        }

        // Strong comparison: both must be strong and values match
        return !this.weak && !isWeak && this.value.equals(value);
    }

    /**
     * Format ETag for HTTP header.
     * Returns format: "value" or W/"value" for weak ETags.
     */
    public String toHeader() {
        if (weak) {
            return "W/\"" + value + "\"";
        }
        return "\"" + value + "\"";
    }

    @Override
    public String toString() {
        return toHeader();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ETag eTag = (ETag) o;
        return weak == eTag.weak && Objects.equals(value, eTag.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, weak);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

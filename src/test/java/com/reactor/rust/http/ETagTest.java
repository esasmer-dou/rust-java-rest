package com.reactor.rust.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ETag.
 */
class ETagTest {

    @Test
    @DisplayName("Create strong ETag")
    void testStrongETag() {
        ETag etag = ETag.strong("abc123");

        assertEquals("abc123", etag.getValue());
        assertFalse(etag.isWeak());
        assertEquals("\"abc123\"", etag.toHeader());
    }

    @Test
    @DisplayName("Create weak ETag")
    void testWeakETag() {
        ETag etag = ETag.weak("xyz789");

        assertEquals("xyz789", etag.getValue());
        assertTrue(etag.isWeak());
        assertEquals("W/\"xyz789\"", etag.toHeader());
    }

    @Test
    @DisplayName("Generate ETag from content")
    void testFromContent() {
        byte[] content1 = "Hello, World!".getBytes();
        byte[] content2 = "Hello, World!".getBytes();
        byte[] content3 = "Different content".getBytes();

        ETag etag1 = ETag.fromContent(content1);
        ETag etag2 = ETag.fromContent(content2);
        ETag etag3 = ETag.fromContent(content3);

        assertEquals(etag1.getValue(), etag2.getValue());
        assertNotEquals(etag1.getValue(), etag3.getValue());
    }

    @Test
    @DisplayName("Generate weak ETag from content")
    void testFromContentWeak() {
        byte[] content = "Test content".getBytes();

        ETag etag = ETag.fromContent(content, true);

        assertTrue(etag.isWeak());
        assertTrue(etag.toHeader().startsWith("W/"));
    }

    @Test
    @DisplayName("Generate ETag from content and timestamp")
    void testFromContentAndTimestamp() {
        byte[] content = "Test".getBytes();
        long timestamp1 = System.currentTimeMillis();
        long timestamp2 = timestamp1 + 1000;

        ETag etag1 = ETag.fromContentAndTimestamp(content, timestamp1);
        ETag etag2 = ETag.fromContentAndTimestamp(content, timestamp1);
        ETag etag3 = ETag.fromContentAndTimestamp(content, timestamp2);

        assertEquals(etag1.getValue(), etag2.getValue());
        assertNotEquals(etag1.getValue(), etag3.getValue());
    }

    @Test
    @DisplayName("matchesIfNoneMatch with wildcard")
    void testMatchesIfNoneMatchWildcard() {
        ETag etag = ETag.strong("abc123");

        assertTrue(etag.matchesIfNoneMatch("*"));
        assertTrue(etag.matchesIfNoneMatch(" * "));
    }

    @Test
    @DisplayName("matchesIfNoneMatch with matching ETag")
    void testMatchesIfNoneMatchMatching() {
        ETag etag = ETag.strong("abc123");

        assertTrue(etag.matchesIfNoneMatch("\"abc123\""));
        assertTrue(etag.matchesIfNoneMatch(" \"abc123\" "));
    }

    @Test
    @DisplayName("matchesIfNoneMatch with non-matching ETag")
    void testMatchesIfNoneMatchNonMatching() {
        ETag etag = ETag.strong("abc123");

        assertFalse(etag.matchesIfNoneMatch("\"xyz789\""));
    }

    @Test
    @DisplayName("matchesIfNoneMatch with multiple ETags")
    void testMatchesIfNoneMatchMultiple() {
        ETag etag = ETag.strong("abc123");

        assertTrue(etag.matchesIfNoneMatch("\"xyz789\", \"abc123\", \"def456\""));
        assertFalse(etag.matchesIfNoneMatch("\"xyz789\", \"def456\""));
    }

    @Test
    @DisplayName("matchesIfNoneMatch with null header")
    void testMatchesIfNoneMatchNull() {
        ETag etag = ETag.strong("abc123");

        assertFalse(etag.matchesIfNoneMatch(null));
        assertFalse(etag.matchesIfNoneMatch(""));
    }

    @Test
    @DisplayName("matchesIfMatch with wildcard")
    void testMatchesIfMatchWildcard() {
        ETag etag = ETag.strong("abc123");

        assertTrue(etag.matchesIfMatch("*"));
    }

    @Test
    @DisplayName("matchesIfMatch with matching ETag")
    void testMatchesIfMatchMatching() {
        ETag etag = ETag.strong("abc123");

        assertTrue(etag.matchesIfMatch("\"abc123\""));
    }

    @Test
    @DisplayName("matchesIfMatch with non-matching ETag")
    void testMatchesIfMatchNonMatching() {
        ETag etag = ETag.strong("abc123");

        assertFalse(etag.matchesIfMatch("\"xyz789\""));
    }

    @Test
    @DisplayName("matchesIfMatch with null header returns true")
    void testMatchesIfMatchNull() {
        ETag etag = ETag.strong("abc123");

        // No If-Match header means proceed
        assertTrue(etag.matchesIfMatch(null));
        assertTrue(etag.matchesIfMatch(""));
    }

    @Test
    @DisplayName("Weak ETag comparison")
    void testWeakETagComparison() {
        ETag weakEtag = ETag.weak("abc123");
        ETag strongEtag = ETag.strong("abc123");

        // Weak comparison: same value matches
        assertTrue(weakEtag.matchesIfNoneMatch("W/\"abc123\""));
        assertTrue(weakEtag.matchesIfNoneMatch("\"abc123\""));

        // Strong comparison: weak vs weak matches, weak vs strong doesn't for strong validator
        assertTrue(strongEtag.matchesIfNoneMatch("\"abc123\""));
    }

    @Test
    @DisplayName("ETag equals and hashCode")
    void testEqualsAndHashCode() {
        ETag etag1 = ETag.strong("abc");
        ETag etag2 = ETag.strong("abc");
        ETag etag3 = ETag.strong("xyz");
        ETag etag4 = ETag.weak("abc");

        assertEquals(etag1, etag2);
        assertEquals(etag1.hashCode(), etag2.hashCode());
        assertNotEquals(etag1, etag3);
        assertNotEquals(etag1, etag4);
    }

    @Test
    @DisplayName("ETag toString returns header format")
    void testToString() {
        ETag strong = ETag.strong("abc");
        ETag weak = ETag.weak("abc");

        assertEquals("\"abc\"", strong.toString());
        assertEquals("W/\"abc\"", weak.toString());
    }
}

package com.reactor.rust.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CompressionUtils.
 */
class CompressionUtilsTest {

    @Test
    @DisplayName("shouldCompress returns true for compressible content")
    void testShouldCompressCompressibleContent() {
        assertTrue(CompressionUtils.isCompressible("text/html"));
        assertTrue(CompressionUtils.isCompressible("text/css"));
        assertTrue(CompressionUtils.isCompressible("application/json"));
        assertTrue(CompressionUtils.isCompressible("application/javascript"));
        assertTrue(CompressionUtils.isCompressible("application/xml"));
    }

    @Test
    @DisplayName("shouldCompress returns false for non-compressible content")
    void testShouldCompressNonCompressibleContent() {
        assertFalse(CompressionUtils.isCompressible("image/png"));
        assertFalse(CompressionUtils.isCompressible("image/jpeg"));
        assertFalse(CompressionUtils.isCompressible("video/mp4"));
        assertFalse(CompressionUtils.isCompressible("application/octet-stream"));
        assertFalse(CompressionUtils.isCompressible(null));
    }

    @Test
    @DisplayName("supportsGzip detects gzip support")
    void testSupportsGzip() {
        assertTrue(CompressionUtils.supportsGzip("gzip"));
        assertTrue(CompressionUtils.supportsGzip("gzip, deflate"));
        assertTrue(CompressionUtils.supportsGzip("deflate, gzip"));
        assertTrue(CompressionUtils.supportsGzip("*"));
        assertFalse(CompressionUtils.supportsGzip("deflate"));
        assertFalse(CompressionUtils.supportsGzip(null));
    }

    @Test
    @DisplayName("supportsDeflate detects deflate support")
    void testSupportsDeflate() {
        assertTrue(CompressionUtils.supportsDeflate("deflate"));
        assertTrue(CompressionUtils.supportsDeflate("gzip, deflate"));
        assertTrue(CompressionUtils.supportsDeflate("*"));
        assertFalse(CompressionUtils.supportsDeflate("gzip"));
        assertFalse(CompressionUtils.supportsDeflate(null));
    }

    @Test
    @DisplayName("getBestEncoding prefers gzip over deflate")
    void testGetBestEncoding() {
        assertEquals("gzip", CompressionUtils.getBestEncoding("gzip"));
        assertEquals("gzip", CompressionUtils.getBestEncoding("gzip, deflate"));
        assertEquals("deflate", CompressionUtils.getBestEncoding("deflate"));
        assertEquals("gzip", CompressionUtils.getBestEncoding("*"));
        assertNull(CompressionUtils.getBestEncoding("br"));
        assertNull(CompressionUtils.getBestEncoding(null));
    }

    @Test
    @DisplayName("shouldCompress considers all factors")
    void testShouldCompressAllFactors() {
        // Compressible content, sufficient size, gzip support
        assertTrue(CompressionUtils.shouldCompress("application/json", 2000, "gzip"));

        // Too small
        assertFalse(CompressionUtils.shouldCompress("application/json", 100, "gzip"));

        // Non-compressible content type
        assertFalse(CompressionUtils.shouldCompress("image/png", 2000, "gzip"));

        // No gzip support
        assertFalse(CompressionUtils.shouldCompress("application/json", 2000, null));
    }

    @Test
    @DisplayName("gzip compresses data")
    void testGzip() throws IOException {
        byte[] original = "Hello, World! This is a test string that should compress well. ".repeat(10).getBytes();

        byte[] compressed = CompressionUtils.gzip(original);

        assertNotNull(compressed);
        assertTrue(compressed.length < original.length);
        assertTrue(compressed.length > 0);
    }

    @Test
    @DisplayName("gzip handles empty data")
    void testGzipEmpty() throws IOException {
        byte[] empty = new byte[0];

        byte[] result = CompressionUtils.gzip(empty);

        assertSame(empty, result);
    }

    @Test
    @DisplayName("gzip handles null data - returns same reference")
    void testGzipNull() throws IOException {
        byte[] result = CompressionUtils.gzip(null);

        assertNull(result);
    }

    @Test
    @DisplayName("deflate compresses data")
    void testDeflate() {
        byte[] original = "Hello, World! This is a test string that should compress well. ".repeat(10).getBytes();

        byte[] compressed = CompressionUtils.deflate(original);

        assertNotNull(compressed);
        assertTrue(compressed.length < original.length);
        assertTrue(compressed.length > 0);
    }

    @Test
    @DisplayName("deflate handles empty data")
    void testDeflateEmpty() {
        byte[] empty = new byte[0];

        byte[] result = CompressionUtils.deflate(empty);

        assertSame(empty, result);
    }

    @Test
    @DisplayName("deflate handles null data")
    void testDeflateNull() {
        byte[] result = CompressionUtils.deflate(null);

        assertNull(result);
    }

    @Test
    @DisplayName("compress with specific encoding")
    void testCompress() throws IOException {
        byte[] data = "Test data for compression".repeat(50).getBytes();

        byte[] gzipped = CompressionUtils.compress(data, "gzip");
        byte[] deflated = CompressionUtils.compress(data, "deflate");
        byte[] unchanged = CompressionUtils.compress(data, "unknown");

        assertTrue(gzipped.length < data.length);
        assertTrue(deflated.length < data.length);
        assertSame(data, unchanged);
    }

    @Test
    @DisplayName("compress with null encoding returns same data")
    void testCompressNullEncoding() throws IOException {
        byte[] data = "Test data".getBytes();

        byte[] result = CompressionUtils.compress(data, null);

        // Returns the same data when encoding is null
        assertSame(data, result);
    }

    @Test
    @DisplayName("compressionRatio calculation")
    void testCompressionRatio() {
        // 50% compression
        double ratio = CompressionUtils.compressionRatio(1000, 500);
        assertEquals(0.5, ratio, 0.001);

        // No compression
        ratio = CompressionUtils.compressionRatio(1000, 1000);
        assertEquals(1.0, ratio, 0.001);

        // Expansion (compressed is larger)
        ratio = CompressionUtils.compressionRatio(100, 150);
        assertEquals(1.5, ratio, 0.001);

        // Zero original size
        ratio = CompressionUtils.compressionRatio(0, 0);
        assertEquals(0.0, ratio, 0.001);
    }

    @Test
    @DisplayName("GZIP can be decompressed")
    void testGzipRoundTrip() throws IOException {
        byte[] original = "This is a test string that should be compressible when repeated. ".repeat(20).getBytes();

        byte[] compressed = CompressionUtils.gzip(original);

        // Decompress using Java's GZIPInputStream
        try (var gzipIn = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            byte[] decompressed = gzipIn.readAllBytes();
            assertArrayEquals(original, decompressed);
        }
    }

    @Test
    @DisplayName("Deflate can be decompressed")
    void testDeflateRoundTrip() throws Exception {
        byte[] original = "This is a test string that should be compressible when repeated. ".repeat(20).getBytes();

        byte[] compressed = CompressionUtils.deflate(original);

        // Decompress using Java's Inflater
        var inflater = new java.util.zip.Inflater();
        inflater.setInput(compressed);
        byte[] decompressed = new byte[original.length * 2];
        int len = inflater.inflate(decompressed);
        inflater.end();

        assertArrayEquals(original, Arrays.copyOf(decompressed, len));
    }
}

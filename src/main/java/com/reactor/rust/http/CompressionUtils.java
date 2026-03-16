package com.reactor.rust.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Utility class for response compression.
 * Supports GZIP and Deflate compression.
 */
public final class CompressionUtils {

    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final int MIN_COMPRESSION_SIZE = 1024; // Don't compress small responses

    private CompressionUtils() {
        // Utility class
    }

    /**
     * Check if compression should be applied based on content type and size.
     *
     * @param contentType The response content type
     * @param contentLength The response content length
     * @param acceptEncoding The Accept-Encoding header value
     * @return true if compression should be applied
     */
    public static boolean shouldCompress(String contentType, int contentLength, String acceptEncoding) {
        if (acceptEncoding == null || acceptEncoding.isEmpty()) {
            return false;
        }

        if (contentLength > 0 && contentLength < MIN_COMPRESSION_SIZE) {
            return false;
        }

        // Only compress text-based and compressible content types
        return isCompressible(contentType) && supportsGzip(acceptEncoding);
    }

    /**
     * Check if content type is compressible.
     */
    public static boolean isCompressible(String contentType) {
        if (contentType == null) {
            return false;
        }

        String type = contentType.toLowerCase();

        // Text types
        if (type.startsWith("text/")) {
            return true;
        }

        // Application types that are text-based
        return type.equals("application/json") ||
               type.equals("application/javascript") ||
               type.equals("application/xml") ||
               type.equals("application/xhtml+xml") ||
               type.equals("application/atom+xml") ||
               type.equals("application/rss+xml") ||
               type.contains("svg") ||
               type.contains("font/");
    }

    /**
     * Check if Accept-Encoding header supports GZIP.
     */
    public static boolean supportsGzip(String acceptEncoding) {
        if (acceptEncoding == null) {
            return false;
        }
        String encoding = acceptEncoding.toLowerCase();
        return encoding.contains("gzip") || encoding.contains("*");
    }

    /**
     * Check if Accept-Encoding header supports Deflate.
     */
    public static boolean supportsDeflate(String acceptEncoding) {
        if (acceptEncoding == null) {
            return false;
        }
        String encoding = acceptEncoding.toLowerCase();
        return encoding.contains("deflate") || encoding.contains("*");
    }

    /**
     * Get the best compression encoding based on Accept-Encoding header.
     *
     * @param acceptEncoding The Accept-Encoding header value
     * @return "gzip", "deflate", or null if no compression supported
     */
    public static String getBestEncoding(String acceptEncoding) {
        if (acceptEncoding == null) {
            return null;
        }

        String encoding = acceptEncoding.toLowerCase();

        // Prefer gzip over deflate (better compression)
        if (encoding.contains("gzip")) {
            return "gzip";
        }
        if (encoding.contains("deflate")) {
            return "deflate";
        }
        if (encoding.contains("*")) {
            return "gzip"; // Default to gzip for wildcard
        }

        return null;
    }

    /**
     * Compress data using GZIP.
     *
     * @param data The data to compress
     * @return Compressed data
     * @throws IOException If compression fails
     */
    public static byte[] gzip(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return data;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length / 2);
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(data);
        }
        return baos.toByteArray();
    }

    /**
     * Compress data using Deflate.
     *
     * @param data The data to compress
     * @return Compressed data
     */
    public static byte[] deflate(byte[] data) {
        if (data == null || data.length == 0) {
            return data;
        }

        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length / 2);
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];

        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            baos.write(buffer, 0, count);
        }

        deflater.end();
        return baos.toByteArray();
    }

    /**
     * Compress data with the specified encoding.
     *
     * @param data The data to compress
     * @param encoding The encoding to use ("gzip" or "deflate")
     * @return Compressed data
     * @throws IOException If compression fails
     */
    public static byte[] compress(byte[] data, String encoding) throws IOException {
        if (encoding == null) {
            return data;
        }

        return switch (encoding.toLowerCase()) {
            case "gzip" -> gzip(data);
            case "deflate" -> deflate(data);
            default -> data;
        };
    }

    /**
     * Calculate compression ratio.
     *
     * @param original Original data size
     * @param compressed Compressed data size
     * @return Compression ratio (0.0 to 1.0, lower is better)
     */
    public static double compressionRatio(int original, int compressed) {
        if (original == 0) {
            return 0.0;
        }
        return (double) compressed / original;
    }
}

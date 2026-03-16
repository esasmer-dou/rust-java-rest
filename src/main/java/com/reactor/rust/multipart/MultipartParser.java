package com.reactor.rust.multipart;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parser for multipart/form-data requests.
 * Zero-dependency, efficient parsing.
 */
public final class MultipartParser {

    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB default

    /**
     * Parse multipart form data.
     *
     * @param data Raw request body bytes
     * @param contentType Content-Type header value (must contain boundary)
     * @return Map of field name to values (String for simple fields, MultipartFile for files)
     */
    public static Map<String, Object> parse(byte[] data, String contentType) {
        return parse(data, contentType, MAX_FILE_SIZE);
    }

    /**
     * Parse multipart form data with custom max file size.
     *
     * @param data Raw request body bytes
     * @param contentType Content-Type header value
     * @param maxFileSize Maximum file size in bytes
     * @return Map of field name to values
     */
    public static Map<String, Object> parse(byte[] data, String contentType, long maxFileSize) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (data == null || data.length == 0 || contentType == null) {
            return result;
        }

        // Extract boundary
        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            return result;
        }

        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

        // Split by boundary
        int start = 0;
        while (start < data.length) {
            // Find next boundary
            int boundaryStart = indexOf(data, boundaryBytes, start);
            if (boundaryStart < 0) {
                break;
            }

            // Skip boundary and CRLF
            int partStart = boundaryStart + boundaryBytes.length;
            if (partStart < data.length && data[partStart] == '\r') partStart++;
            if (partStart < data.length && data[partStart] == '\n') partStart++;

            // Find end of this part (next boundary or end of data)
            int partEnd = indexOf(data, boundaryBytes, partStart);
            if (partEnd < 0) {
                partEnd = data.length;
            }

            // Trim trailing CRLF before next boundary
            if (partEnd > partStart && data[partEnd - 1] == '\n') partEnd--;
            if (partEnd > partStart && data[partEnd - 1] == '\r') partEnd--;

            // Parse this part
            PartInfo part = parsePart(data, partStart, partEnd);
            if (part != null) {
                if (part.filename != null) {
                    // It's a file
                    if (part.data.length <= maxFileSize) {
                        result.put(part.name, new MultipartFile(
                            part.name,
                            part.filename,
                            part.contentType,
                            part.data
                        ));
                    }
                } else {
                    // It's a simple field
                    String value = new String(part.data, StandardCharsets.UTF_8);
                    result.put(part.name, value);
                }
            }

            start = partEnd;
        }

        return result;
    }

    /**
     * Extract boundary from Content-Type header.
     */
    private static String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }

        // Find boundary= in content type
        int boundaryIndex = contentType.indexOf("boundary=");
        if (boundaryIndex < 0) {
            return null;
        }

        boundaryIndex += 9; // "boundary=".length()

        // Extract boundary value (may be quoted)
        String boundary;
        if (contentType.charAt(boundaryIndex) == '"') {
            // Quoted boundary
            int endQuote = contentType.indexOf('"', boundaryIndex + 1);
            if (endQuote < 0) {
                return null;
            }
            boundary = contentType.substring(boundaryIndex + 1, endQuote);
        } else {
            // Unquoted boundary - ends at semicolon or end
            int endBoundary = contentType.indexOf(';', boundaryIndex);
            if (endBoundary < 0) {
                boundary = contentType.substring(boundaryIndex);
            } else {
                boundary = contentType.substring(boundaryIndex, endBoundary);
            }
        }

        return boundary.trim();
    }

    /**
     * Parse a single part of the multipart data.
     */
    private static PartInfo parsePart(byte[] data, int start, int end) {
        // Find header/body separator (double CRLF)
        int headerEnd = indexOf(data, new byte[]{'\r', '\n', '\r', '\n'}, start);
        if (headerEnd < 0 || headerEnd >= end) {
            return null;
        }

        // Parse headers
        String headers = new String(data, start, headerEnd - start, StandardCharsets.ISO_8859_1);
        Map<String, String> headerMap = parseHeaders(headers);

        // Get content-disposition info
        String contentDisposition = headerMap.get("content-disposition");
        if (contentDisposition == null) {
            return null;
        }

        String name = extractValue(contentDisposition, "name");
        String filename = extractValue(contentDisposition, "filename");
        String contentType = headerMap.get("content-type");
        if (contentType == null) {
            contentType = "application/octet-stream";
        }

        // Extract body data
        int bodyStart = headerEnd + 4; // Skip CRLFCRLF
        int bodyEnd = end;
        byte[] bodyData = new byte[bodyEnd - bodyStart];
        System.arraycopy(data, bodyStart, bodyData, 0, bodyData.length);

        PartInfo info = new PartInfo();
        info.name = name;
        info.filename = filename;
        info.contentType = contentType;
        info.data = bodyData;

        return info;
    }

    /**
     * Parse headers string into map.
     */
    private static Map<String, String> parseHeaders(String headers) {
        Map<String, String> map = new HashMap<>();
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                map.put(key, value);
            }
        }
        return map;
    }

    /**
     * Extract quoted value from header parameter.
     */
    private static String extractValue(String header, String paramName) {
        String search = paramName + "=";
        int index = header.indexOf(search);
        if (index < 0) {
            return null;
        }

        index += search.length();
        if (index >= header.length()) {
            return null;
        }

        if (header.charAt(index) == '"') {
            // Quoted value
            int endQuote = header.indexOf('"', index + 1);
            if (endQuote < 0) {
                return header.substring(index + 1);
            }
            return header.substring(index + 1, endQuote);
        } else {
            // Unquoted value - ends at semicolon or end
            int endValue = header.indexOf(';', index);
            if (endValue < 0) {
                return header.substring(index);
            }
            return header.substring(index, endValue).trim();
        }
    }

    /**
     * Find byte sequence in data.
     */
    private static int indexOf(byte[] data, byte[] pattern, int start) {
        outer:
        for (int i = start; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static class PartInfo {
        String name;
        String filename;
        String contentType;
        byte[] data;
    }
}

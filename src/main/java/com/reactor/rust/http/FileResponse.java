package com.reactor.rust.http;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Response marker for Rust-native file streaming.
 *
 * <p>The file bytes are never serialized through Java/JNI. Java returns only the
 * normalized path and headers; Rust opens the file asynchronously and streams it
 * with socket backpressure.</p>
 */
public final class FileResponse {

    private final Path path;
    private final String contentType;
    private final Map<String, String> headers;
    private volatile String encodedHeadersString;

    private FileResponse(Path path, String contentType, Map<String, String> headers) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        this.path = path.toAbsolutePath().normalize();
        this.contentType = contentType;
        this.headers = headers != null ? headers : new HashMap<>();
        if (contentType != null && !contentType.isBlank()) {
            this.headers.putIfAbsent("Content-Type", contentType);
        }
    }

    public static FileResponse of(Path path) {
        return new FileResponse(path, null, new HashMap<>());
    }

    public static FileResponse of(Path path, String contentType) {
        return new FileResponse(path, contentType, new HashMap<>());
    }

    public static FileResponse download(Path path, String fileName, String contentType) {
        FileResponse response = new FileResponse(path, contentType, new HashMap<>());
        if (fileName != null && !fileName.isBlank()) {
            response.header("Content-Disposition", "attachment; filename=\"" + sanitizeFileName(fileName) + "\"");
        }
        return response;
    }

    public Path getPath() {
        return path;
    }

    public String getAbsolutePath() {
        return path.toString();
    }

    public String getContentType() {
        return contentType;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public FileResponse header(String name, String value) {
        if (name != null && value != null) {
            headers.put(name, value);
            encodedHeadersString = null;
        }
        return this;
    }

    public String getEncodedHeadersString() {
        String cached = encodedHeadersString;
        if (cached != null) {
            return cached;
        }
        if (headers.isEmpty()) {
            encodedHeadersString = "";
            return "";
        }
        StringBuilder sb = new StringBuilder(headers.size() * 32);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        }
        String encoded = sb.toString();
        encodedHeadersString = encoded;
        return encoded;
    }

    public byte[] getEncodedHeaders() {
        return getEncodedHeadersString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sanitizeFileName(String fileName) {
        return fileName
                .replace("\\", "_")
                .replace("/", "_")
                .replace("\r", "_")
                .replace("\n", "_")
                .replace("\"", "'");
    }
}

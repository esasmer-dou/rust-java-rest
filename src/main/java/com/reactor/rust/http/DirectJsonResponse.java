package com.reactor.rust.http;

import com.reactor.rust.json.DirectJsonWriter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Response type for explicit direct-buffer JSON serialization.
 *
 * <p>This keeps Java business objects in Java, but bypasses generic DTO serialization:
 * the supplied {@link DirectJsonWriter} writes directly into the native response buffer.</p>
 */
public final class DirectJsonResponse<T> {

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final byte[] DEFAULT_JSON_CONTENT_TYPE_HEADER =
            ("Content-Type: " + MediaType.APPLICATION_JSON_UTF8 + "\n").getBytes(StandardCharsets.UTF_8);

    private final T body;
    private final DirectJsonWriter<? super T> writer;
    private HeaderMap headers;
    private int statusCode;
    private volatile byte[] encodedHeaders;
    private volatile byte[] encodedHeadersWithDefaultJson;

    private DirectJsonResponse(T body, DirectJsonWriter<? super T> writer, int statusCode) {
        this.body = body;
        this.writer = Objects.requireNonNull(writer, "writer");
        this.statusCode = statusCode;
    }

    public static <T> DirectJsonResponse<T> ok(T body, DirectJsonWriter<? super T> writer) {
        return new DirectJsonResponse<>(body, writer, 200);
    }

    public static <T> DirectJsonResponse<T> status(int statusCode, T body, DirectJsonWriter<? super T> writer) {
        return new DirectJsonResponse<>(body, writer, statusCode);
    }

    public DirectJsonResponse<T> status(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    public DirectJsonResponse<T> header(String name, String value) {
        if (name != null && !name.isBlank() && value != null) {
            mutableHeaders().put(name, value);
        }
        return this;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Map<String, String> getHeaders() {
        return mutableHeaders();
    }

    public byte[] getEncodedHeaders() {
        byte[] cached = encodedHeaders;
        if (cached != null) {
            return cached;
        }
        byte[] encoded = encodeHeaders(headers);
        encodedHeaders = encoded;
        return encoded;
    }

    public byte[] getEncodedHeadersWithDefaultJson() {
        byte[] cached = encodedHeadersWithDefaultJson;
        if (cached != null) {
            return cached;
        }
        byte[] encoded = encodeHeadersWithDefaultJson(headers);
        encodedHeadersWithDefaultJson = encoded;
        return encoded;
    }

    public int writeBody(ByteBuffer out, int offset) {
        return writer.write(body, out, offset);
    }

    private void invalidateEncodedHeaders() {
        encodedHeaders = null;
        encodedHeadersWithDefaultJson = null;
    }

    private HeaderMap mutableHeaders() {
        HeaderMap current = headers;
        if (current == null) {
            current = new HeaderMap(this);
            headers = current;
        }
        return current;
    }

    private static byte[] encodeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return EMPTY_BYTES;
        }
        StringBuilder sb = new StringBuilder(headers.size() * 32);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        if (sb.length() == 0) {
            return EMPTY_BYTES;
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encodeHeadersWithDefaultJson(Map<String, String> headers) {
        byte[] headerBytes = encodeHeaders(headers);
        if (hasContentType(headers)) {
            return headerBytes;
        }
        if (headerBytes.length == 0) {
            return DEFAULT_JSON_CONTENT_TYPE_HEADER;
        }
        byte[] merged = new byte[DEFAULT_JSON_CONTENT_TYPE_HEADER.length + headerBytes.length];
        System.arraycopy(DEFAULT_JSON_CONTENT_TYPE_HEADER, 0, merged, 0, DEFAULT_JSON_CONTENT_TYPE_HEADER.length);
        System.arraycopy(headerBytes, 0, merged, DEFAULT_JSON_CONTENT_TYPE_HEADER.length, headerBytes.length);
        return merged;
    }

    private static boolean hasContentType(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        for (String key : headers.keySet()) {
            if ("Content-Type".equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeTextualContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return contentType;
        }
        String value = contentType.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("charset=")) {
            return value;
        }
        if (lower.startsWith("text/")
                || lower.startsWith("application/json")
                || lower.contains("+json")) {
            return value + "; charset=utf-8";
        }
        return value;
    }

    private static final class HeaderMap extends LinkedHashMap<String, String> {
        private final DirectJsonResponse<?> owner;

        private HeaderMap(DirectJsonResponse<?> owner) {
            this.owner = owner;
        }

        @Override
        public String put(String key, String value) {
            owner.invalidateEncodedHeaders();
            return super.put(key, normalizeHeaderValue(key, value));
        }

        @Override
        public void putAll(Map<? extends String, ? extends String> map) {
            owner.invalidateEncodedHeaders();
            for (Map.Entry<? extends String, ? extends String> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    super.put(entry.getKey(), normalizeHeaderValue(entry.getKey(), entry.getValue()));
                }
            }
        }

        @Override
        public String remove(Object key) {
            owner.invalidateEncodedHeaders();
            return super.remove(key);
        }

        @Override
        public void clear() {
            owner.invalidateEncodedHeaders();
            super.clear();
        }

        private static String normalizeHeaderValue(String key, String value) {
            if (key == null || value == null) {
                return value;
            }
            return "Content-Type".equalsIgnoreCase(key)
                    ? normalizeTextualContentType(value)
                    : value;
        }
    }
}

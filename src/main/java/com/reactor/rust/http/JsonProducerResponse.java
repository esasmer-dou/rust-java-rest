package com.reactor.rust.http;

import com.reactor.rust.json.JsonBodyProducer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Response type for heavy dynamic JSON without building a Java DTO graph.
 *
 * <p>Use this when the handler can compute scalar inputs and write the response directly into the
 * native buffer. This keeps business logic in Java while avoiding intermediate lists, records,
 * {@code byte[]}, or {@code StringBuilder} payloads.</p>
 */
public final class JsonProducerResponse {

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final byte[] DEFAULT_JSON_CONTENT_TYPE_HEADER =
            ("Content-Type: " + MediaType.APPLICATION_JSON_UTF8 + "\n").getBytes(StandardCharsets.UTF_8);

    private final JsonBodyProducer producer;
    private final HeaderMap headers = new HeaderMap(this);
    private int statusCode;
    private volatile byte[] encodedHeaders;
    private volatile byte[] encodedHeadersWithDefaultJson;

    private JsonProducerResponse(int statusCode, JsonBodyProducer producer) {
        this.statusCode = statusCode;
        this.producer = Objects.requireNonNull(producer, "producer");
    }

    public static JsonProducerResponse ok(JsonBodyProducer producer) {
        return new JsonProducerResponse(200, producer);
    }

    public static JsonProducerResponse status(int statusCode, JsonBodyProducer producer) {
        return new JsonProducerResponse(statusCode, producer);
    }

    public JsonProducerResponse status(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    public JsonProducerResponse header(String name, String value) {
        if (name != null && !name.isBlank() && value != null) {
            headers.put(name, value);
        }
        return this;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Map<String, String> getHeaders() {
        return headers;
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
        return producer.write(out, offset);
    }

    private void invalidateEncodedHeaders() {
        encodedHeaders = null;
        encodedHeadersWithDefaultJson = null;
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
        private final JsonProducerResponse owner;

        private HeaderMap(JsonProducerResponse owner) {
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

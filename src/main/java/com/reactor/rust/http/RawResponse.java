package com.reactor.rust.http;

import com.reactor.rust.bridge.NativeBridge;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Response marker for pre-serialized bytes.
 *
 * <p>Use for pre-serialized/cached payloads where rebuilding an object graph per
 * request would dominate latency and allocation. The caller must not mutate the
 * byte array after passing it to RawResponse.</p>
 */
public final class RawResponse {

    private static final byte[] EMPTY_BYTES = new byte[0];

    private final byte[] body;
    private final HeaderMap headers;
    private final int nativeId;
    private volatile byte[] encodedHeaders;

    private RawResponse(byte[] body, Map<String, String> headers) {
        this(body, headers, 0);
    }

    private RawResponse(byte[] body, Map<String, String> headers, int nativeId) {
        this.body = body != null ? body : EMPTY_BYTES;
        this.headers = new HeaderMap(this);
        if (headers != null && !headers.isEmpty()) {
            this.headers.putAll(headers);
        }
        this.nativeId = nativeId;
    }

    public static RawResponse text(String body, String contentType) {
        RawResponse response = new RawResponse(
                body != null ? body.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES,
                Map.of()
        );
        response.header("Content-Type", normalizeTextualContentType(
                contentType != null ? contentType : MediaType.TEXT_PLAIN_UTF8
        ));
        return response;
    }

    public static RawResponse bytes(byte[] body, String contentType) {
        RawResponse response = new RawResponse(body, Map.of());
        response.header("Content-Type", normalizeTextualContentType(
                contentType != null ? contentType : MediaType.APPLICATION_OCTET_STREAM
        ));
        return response;
    }

    public static RawResponse json(byte[] body) {
        return bytes(body, MediaType.APPLICATION_JSON_UTF8);
    }

    /**
     * Registers immutable response bytes in Rust once and returns only a small native id per request.
     * Use for cached/read-heavy payloads, not per-request dynamic responses.
     */
    public static RawResponse registeredJson(byte[] body) {
        return registeredBytes(body, MediaType.APPLICATION_JSON_UTF8);
    }

    /**
     * Registers immutable bytes in Rust once and returns only a native id per request.
     *
     * <p>Use this for small/medium static files, precomputed exports and read-heavy
     * payloads. Large files should use {@link FileResponse} to avoid pinning the body
     * in native memory.</p>
     */
    public static RawResponse registeredBytes(byte[] body, String contentType) {
        Map<String, String> headers = contentType != null && !contentType.isBlank()
                ? Map.of("Content-Type", normalizeTextualContentType(contentType))
                : Map.of();
        return registered(body, headers, 200);
    }

    public static RawResponse registered(byte[] body, Map<String, String> headers, int statusCode) {
        byte[] safeBody = body != null ? body : EMPTY_BYTES;
        Map<String, String> normalizedHeaders = normalizeHeaders(headers);
        int nativeId = NativeBridge.registerStaticResponse(
                safeBody,
                encodeHeadersString(normalizedHeaders),
                statusCode
        );
        return new RawResponse(EMPTY_BYTES, normalizedHeaders, nativeId);
    }

    public static RawResponse nativeJson(int nativeId) {
        RawResponse response = nativeResponse(nativeId);
        response.header("Content-Type", MediaType.APPLICATION_JSON_UTF8);
        return response;
    }

    public static RawResponse nativeResponse(int nativeId) {
        return new RawResponse(EMPTY_BYTES, Map.of(), nativeId);
    }

    public byte[] getBody() {
        return body;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Returns cached UTF-8 native header bytes.
     *
     * <p>RawResponse is commonly used for cached/pre-serialized payloads. Encoding
     * headers once avoids StringBuilder and UTF-8 allocation on every request.</p>
     */
    public byte[] getEncodedHeaders() {
        byte[] cached = encodedHeaders;
        if (cached != null) {
            return cached;
        }
        byte[] encoded = encodeHeaders(headers);
        encodedHeaders = encoded;
        return encoded;
    }

    public int getNativeId() {
        return nativeId;
    }

    public RawResponse header(String name, String value) {
        if (name != null && value != null) {
            headers.put(name, value);
        }
        return this;
    }

    private void invalidateEncodedHeaders() {
        encodedHeaders = null;
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

    private static String encodeHeadersString(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(headers.size() * 32);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        Map<String, String> normalized = new HashMap<>();
        if (headers == null || headers.isEmpty()) {
            return normalized;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                normalized.put(entry.getKey(), normalizeHeaderValue(entry.getKey(), entry.getValue()));
            }
        }
        return normalized;
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

    private static String normalizeHeaderValue(String key, String value) {
        if (key == null || value == null) {
            return value;
        }
        return "Content-Type".equalsIgnoreCase(key)
                ? normalizeTextualContentType(value)
                : value;
    }

    private static final class HeaderMap extends HashMap<String, String> {
        private final RawResponse owner;

        private HeaderMap(RawResponse owner) {
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
                    ? RawResponse.normalizeTextualContentType(value)
                    : value;
        }
    }
}

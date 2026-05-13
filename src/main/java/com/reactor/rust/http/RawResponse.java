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

    private final byte[] body;
    private final Map<String, String> headers;
    private final int nativeId;

    private RawResponse(byte[] body, Map<String, String> headers) {
        this(body, headers, 0);
    }

    private RawResponse(byte[] body, Map<String, String> headers, int nativeId) {
        this.body = body != null ? body : new byte[0];
        this.headers = headers != null ? headers : new HashMap<>();
        this.nativeId = nativeId;
    }

    public static RawResponse text(String body, String contentType) {
        RawResponse response = new RawResponse(
                body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0],
                new HashMap<>()
        );
        response.header("Content-Type", normalizeTextualContentType(
                contentType != null ? contentType : MediaType.TEXT_PLAIN_UTF8
        ));
        return response;
    }

    public static RawResponse bytes(byte[] body, String contentType) {
        RawResponse response = new RawResponse(body, new HashMap<>());
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
        byte[] safeBody = body != null ? body : new byte[0];
        int nativeId = NativeBridge.registerStaticResponse(
                safeBody,
                "Content-Type: " + MediaType.APPLICATION_JSON_UTF8 + "\n",
                200
        );
        RawResponse response = new RawResponse(safeBody, new HashMap<>(), nativeId);
        response.header("Content-Type", MediaType.APPLICATION_JSON_UTF8);
        return response;
    }

    public static RawResponse nativeJson(int nativeId) {
        RawResponse response = new RawResponse(new byte[0], new HashMap<>(), nativeId);
        response.header("Content-Type", MediaType.APPLICATION_JSON_UTF8);
        return response;
    }

    public byte[] getBody() {
        return body;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public int getNativeId() {
        return nativeId;
    }

    public RawResponse header(String name, String value) {
        if (name != null && value != null) {
            headers.put(name, "Content-Type".equalsIgnoreCase(name)
                    ? normalizeTextualContentType(value)
                    : value);
        }
        return this;
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
}

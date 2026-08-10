package com.reactor.rust.middleware;

import com.reactor.rust.util.UrlCodec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context object passed through the middleware chain.
 * Contains request information and allows storing attributes.
 */
@Deprecated(forRemoval = true)
public final class MiddlewareContext {

    private final String method;
    private final String path;
    private final String queryString;
    private final Map<String, String> headers;
    private final Map<String, String> pathParams;
    private final byte[] body;

    // Mutable attributes - middleware can store data here
    private volatile Map<String, Object> attributes;

    public MiddlewareContext(String method, String path, String queryString,
                             Map<String, String> headers, Map<String, String> pathParams, byte[] body) {
        this.method = method;
        this.path = path;
        this.queryString = queryString;
        this.headers = headers;
        this.pathParams = pathParams;
        this.body = body;
    }

    public String method() { return method; }
    public String path() { return path; }
    public String queryString() { return queryString; }
    public Map<String, String> headers() { return headers; }
    public Map<String, String> pathParams() { return pathParams; }
    public byte[] body() { return body; }

    // Attribute methods
    public Object getAttribute(String key) {
        Map<String, Object> current = attributes;
        return current == null ? null : current.get(key);
    }

    public void setAttribute(String key, Object value) {
        mutableAttributes().put(key, value);
    }

    public boolean hasAttribute(String key) {
        Map<String, Object> current = attributes;
        return current != null && current.containsKey(key);
    }

    public void removeAttribute(String key) {
        Map<String, Object> current = attributes;
        if (current != null) current.remove(key);
    }

    public Map<String, Object> attributes() {
        Map<String, Object> current = attributes;
        return current == null ? Map.of() : current;
    }

    // Header helpers
    public String getHeader(String name) {
        return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public String getHeader(String name, String defaultValue) {
        String value = headers.get(name.toLowerCase(java.util.Locale.ROOT));
        return value != null ? value : defaultValue;
    }

    // Path param helpers
    public String getPathParam(String name) {
        return pathParams.get(name);
    }

    public String getPathParam(String name, String defaultValue) {
        String value = pathParams.get(name);
        return value != null ? value : defaultValue;
    }

    // Query param helpers
    public String getQueryParam(String name) {
        if (queryString == null || queryString.isEmpty()) return null;
        int start = 0;
        while (start <= queryString.length()) {
            int end = queryString.indexOf('&', start);
            if (end < 0) end = queryString.length();
            int separator = queryString.indexOf('=', start);
            if (separator < 0 || separator > end) separator = end;
            int keyLength = separator - start;
            if (keyLength == name.length()
                    && queryString.regionMatches(start, name, 0, keyLength)) {
                if (separator == end) return "";
                return UrlCodec.decodeComponent(queryString.substring(separator + 1, end), true);
            }
            if (end == queryString.length()) break;
            start = end + 1;
        }
        return null;
    }

    public String getQueryParam(String name, String defaultValue) {
        String value = getQueryParam(name);
        return value != null ? value : defaultValue;
    }

    /**
     * Get HTTP method (convenience alias for method()).
     */
    public String getMethod() {
        return method;
    }

    private Map<String, Object> mutableAttributes() {
        Map<String, Object> current = attributes;
        if (current != null) return current;
        synchronized (this) {
            current = attributes;
            if (current == null) {
                current = new ConcurrentHashMap<>(4);
                attributes = current;
            }
            return current;
        }
    }
}

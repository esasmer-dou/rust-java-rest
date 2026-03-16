package com.reactor.rust.middleware;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context object passed through the middleware chain.
 * Contains request information and allows storing attributes.
 */
public final class MiddlewareContext {

    private final String method;
    private final String path;
    private final String queryString;
    private final Map<String, String> headers;
    private final Map<String, String> pathParams;
    private final byte[] body;

    // Mutable attributes - middleware can store data here
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

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
    public Object getAttribute(String key) { return attributes.get(key); }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public boolean hasAttribute(String key) { return attributes.containsKey(key); }
    public void removeAttribute(String key) { attributes.remove(key); }
    public Map<String, Object> attributes() { return attributes; }

    // Header helpers
    public String getHeader(String name) {
        return headers.get(name.toLowerCase());
    }

    public String getHeader(String name, String defaultValue) {
        String value = headers.get(name.toLowerCase());
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
        for (String pair : queryString.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && pair.substring(0, idx).equals(name)) {
                return idx < pair.length() - 1 ? pair.substring(idx + 1) : "";
            }
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
}

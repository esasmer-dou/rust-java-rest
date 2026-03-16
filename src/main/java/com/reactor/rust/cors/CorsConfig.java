package com.reactor.rust.cors;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CORS (Cross-Origin Resource Sharing) configuration.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * @Component
 * public class MyCorsConfig extends CorsConfig {
 *     public MyCorsConfig() {
 *         super(true, "http://localhost:3000", "GET,POST,PUT,DELETE", "Authorization,Content-Type");
 *     }
 * }
 * }</pre>
 */
public class CorsConfig {

    private final boolean enabled;
    private final Set<String> allowedOrigins;
    private final Set<String> allowedMethods;
    private final Set<String> allowedHeaders;
    private final Set<String> exposedHeaders;
    private final boolean allowCredentials;
    private final long maxAge;

    /**
     * Create CORS config with common defaults.
     */
    public CorsConfig() {
        this(true, "*", "GET,POST,PUT,DELETE,PATCH,OPTIONS", "*", null, true, 3600);
    }

    /**
     * Create CORS config with all options.
     */
    public CorsConfig(boolean enabled, String allowedOrigins, String allowedMethods,
                      String allowedHeaders, String exposedHeaders, boolean allowCredentials, long maxAge) {
        this.enabled = enabled;
        this.allowedOrigins = parseSet(allowedOrigins);
        this.allowedMethods = parseSet(allowedMethods);
        this.allowedHeaders = parseSet(allowedHeaders);
        this.exposedHeaders = exposedHeaders != null ? parseSet(exposedHeaders) : new HashSet<>();
        this.allowCredentials = allowCredentials;
        this.maxAge = maxAge;
    }

    private Set<String> parseSet(String csv) {
        if (csv == null || csv.isEmpty() || "*".equals(csv.trim())) {
            return Set.of("*");
        }
        Set<String> set = new HashSet<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim().toUpperCase();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return set;
    }

    // Getters
    public boolean isEnabled() { return enabled; }
    public Set<String> getAllowedOrigins() { return allowedOrigins; }
    public Set<String> getAllowedMethods() { return allowedMethods; }
    public Set<String> getAllowedHeaders() { return allowedHeaders; }
    public Set<String> getExposedHeaders() { return exposedHeaders; }
    public boolean isAllowCredentials() { return allowCredentials; }
    public long getMaxAge() { return maxAge; }

    /**
     * Check if origin is allowed.
     */
    public boolean isOriginAllowed(String origin) {
        if (!enabled) return false;
        if (allowedOrigins.contains("*")) return true;
        return allowedOrigins.contains(origin);
    }

    /**
     * Check if method is allowed.
     */
    public boolean isMethodAllowed(String method) {
        if (!enabled) return false;
        if (allowedMethods.contains("*")) return true;
        return allowedMethods.contains(method.toUpperCase());
    }

    /**
     * Get the Access-Control-Allow-Origin header value.
     */
    public String getAllowOriginHeaderValue(String requestOrigin) {
        if (allowedOrigins.contains("*")) {
            return "*";
        }
        if (requestOrigin != null && isOriginAllowed(requestOrigin)) {
            return requestOrigin;
        }
        return allowedOrigins.iterator().next();
    }

    /**
     * Build CORS headers for response.
     *
     * @param requestOrigin The Origin header from the request
     * @param requestMethod The HTTP method of the request
     * @return Map of CORS headers
     */
    public Map<String, String> buildCorsHeaders(String requestOrigin, String requestMethod) {
        Map<String, String> headers = new LinkedHashMap<>();

        if (!enabled) {
            return headers;
        }

        // Access-Control-Allow-Origin
        headers.put("Access-Control-Allow-Origin", getAllowOriginHeaderValue(requestOrigin));

        // Access-Control-Allow-Methods
        headers.put("Access-Control-Allow-Methods", String.join(", ", allowedMethods));

        // Access-Control-Allow-Headers
        if (!allowedHeaders.isEmpty()) {
            headers.put("Access-Control-Allow-Headers", String.join(", ", allowedHeaders));
        }

        // Access-Control-Expose-Headers
        if (!exposedHeaders.isEmpty()) {
            headers.put("Access-Control-Expose-Headers", String.join(", ", exposedHeaders));
        }

        // Access-Control-Allow-Credentials
        if (allowCredentials) {
            headers.put("Access-Control-Allow-Credentials", "true");
        }

        // Access-Control-Max-Age
        if (maxAge > 0) {
            headers.put("Access-Control-Max-Age", String.valueOf(maxAge));
        }

        return headers;
    }
}

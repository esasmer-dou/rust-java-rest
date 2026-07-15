package com.reactor.rust.util;

/**
 * ThreadLocal pools for FastMap instances.
 * Reuses map storage across requests handled by the same thread.
 *
 * Usage:
 *   FastMap params = PooledMaps.getParams();
 *   try {
 *       parseTo(params, input);
 *       String value = params.get("key");
 *   } finally {
 *       params.clear();
 *   }
 */
public final class PooledMaps {

    private PooledMaps() {}

    // Thread-local pools for different map types
    private static final ThreadLocal<FastMap> PARAMS_POOL =
        ThreadLocal.withInitial(FastMap::new);

    private static final ThreadLocal<FastMap> HEADERS_POOL =
        ThreadLocal.withInitial(FastMap::new);

    private static final ThreadLocal<FastMap> COOKIES_POOL =
        ThreadLocal.withInitial(FastMap::new);

    // Pool for the resolved parameters map (path params + query params + headers)
    private static final ThreadLocal<FastMap> RESOLVED_POOL =
        ThreadLocal.withInitial(FastMap::new);

    /**
     * Get a FastMap for general parameter parsing.
     * CALLER MUST call clear() after use.
     */
    public static FastMap getParams() {
        return PARAMS_POOL.get();
    }

    /**
     * Get a FastMap for header parsing.
     * CALLER MUST call clear() after use.
     */
    public static FastMap getHeaders() {
        return HEADERS_POOL.get();
    }

    /**
     * Get a FastMap for cookie parsing.
     * CALLER MUST call clear() after use.
     */
    public static FastMap getCookies() {
        return COOKIES_POOL.get();
    }

    /**
     * Get a FastMap for resolved parameters.
     * CALLER MUST call clear() after use.
     */
    public static FastMap getResolved() {
        return RESOLVED_POOL.get();
    }

    /**
     * Parse key=value pairs into a reusable FastMap.
     * Format: "key1=value1&amp;key2=value2"
     *
     * @param map Target map (must be cleared before calling)
     * @param input Input string to parse
     */
    public static void parseParamsTo(FastMap map, String input) {
        parseParamsTo(map, input, true);
    }

    public static void parseParamsTo(FastMap map, String input, boolean plusAsSpace) {
        if (input == null || input.isEmpty()) {
            return;
        }

        int start = 0;
        int len = input.length();

        for (int i = 0; i < len; i++) {
            if (input.charAt(i) == '&') {
                parsePair(map, input, start, i, plusAsSpace);
                start = i + 1;
            }
        }
        // Don't forget the last pair
        if (start < len) {
            parsePair(map, input, start, len, plusAsSpace);
        }
    }

    /**
     * Parse headers into a reusable FastMap.
     * Format: "Header1: value1\nHeader2: value2\n"
     *
     * @param map Target map (must be cleared before calling)
     * @param input Input string to parse
     */
    public static void parseHeadersTo(FastMap map, String input) {
        if (input == null || input.isEmpty()) {
            return;
        }

        int start = 0;
        int len = input.length();

        for (int i = 0; i < len; i++) {
            if (input.charAt(i) == '\n') {
                parseHeaderLine(map, input, start, i);
                start = i + 1;
            }
        }
        // Don't forget the last line
        if (start < len) {
            parseHeaderLine(map, input, start, len);
        }
    }

    /**
     * Parse cookies from Cookie header value into a FastMap.
     * Format: "cookie1=value1; cookie2=value2"
     *
     * @param map Target map (must be cleared before calling)
     * @param cookieHeader Cookie header value
     */
    public static void parseCookiesTo(FastMap map, String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return;
        }

        int start = 0;
        int len = cookieHeader.length();

        for (int i = 0; i < len; i++) {
            if (cookieHeader.charAt(i) == ';') {
                parsePairTrim(map, cookieHeader, start, i);
                start = i + 1;
            }
        }
        // Don't forget the last pair
        if (start < len) {
            parsePairTrim(map, cookieHeader, start, len);
        }
    }

    private static void parsePair(FastMap map, String input, int start, int end, boolean plusAsSpace) {
        if (start >= end) return;

        // Find equals sign
        int eqIdx = -1;
        for (int i = start; i < end; i++) {
            if (input.charAt(i) == '=') {
                eqIdx = i;
                break;
            }
        }

        if (eqIdx > start) {
            String key = UrlCodec.decodeComponent(input.substring(start, eqIdx), plusAsSpace);
            String value = UrlCodec.decodeComponent(input.substring(eqIdx + 1, end), plusAsSpace);
            map.put(key, value);
        }
    }

    private static void parsePairTrim(FastMap map, String input, int start, int end) {
        if (start >= end) return;

        // Skip leading whitespace
        while (start < end && (input.charAt(start) == ' ' || input.charAt(start) == '\t')) {
            start++;
        }
        if (start >= end) return;

        // Find equals sign
        int eqIdx = -1;
        for (int i = start; i < end; i++) {
            if (input.charAt(i) == '=') {
                eqIdx = i;
                break;
            }
        }

        if (eqIdx > start) {
            String key = UrlCodec.decodeComponent(input.substring(start, eqIdx), false);
            String value = UrlCodec.decodeComponent(input.substring(eqIdx + 1, end), false);
            map.put(key, value);
        }
    }

    private static void parseHeaderLine(FastMap map, String input, int start, int end) {
        if (start >= end) return;

        // Skip leading whitespace
        while (start < end && (input.charAt(start) == ' ' || input.charAt(start) == '\t')) {
            start++;
        }
        if (start >= end) return;

        // Find colon
        int colonIdx = -1;
        for (int i = start; i < end; i++) {
            if (input.charAt(i) == ':') {
                colonIdx = i;
                break;
            }
        }

        if (colonIdx > start) {
            String key = input.substring(start, colonIdx).toLowerCase(java.util.Locale.ROOT);
            // Skip leading whitespace in value
            int valueStart = colonIdx + 1;
            while (valueStart < end && (input.charAt(valueStart) == ' ' || input.charAt(valueStart) == '\t')) {
                valueStart++;
            }
            String value = input.substring(valueStart, end);
            map.put(key, value);
        }
    }
}

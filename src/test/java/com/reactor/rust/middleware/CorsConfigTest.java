package com.reactor.rust.middleware;

import com.reactor.rust.cors.CorsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CorsConfig.
 */
class CorsConfigTest {

    @Test
    @DisplayName("Default CORS config allows all origins")
    void testDefaultConfigAllOrigins() {
        CorsConfig config = new CorsConfig();

        assertTrue(config.isOriginAllowed("http://localhost:3000"));
        assertTrue(config.isOriginAllowed("https://example.com"));
        assertTrue(config.isOriginAllowed("https://any-domain.org"));
    }

    @Test
    @DisplayName("Specific allowed origins (case-insensitive)")
    void testSpecificAllowedOrigins() {
        CorsConfig config = new CorsConfig(
            true,
            "http://localhost:3000, https://example.com",
            "GET, POST",
            "Content-Type, Authorization",
            null,
            true,
            3600
        );

        // Origins are converted to uppercase internally
        assertTrue(config.isOriginAllowed("HTTP://LOCALHOST:3000"));
        assertTrue(config.isOriginAllowed("HTTPS://EXAMPLE.COM"));
        // Different origins are not allowed
        assertFalse(config.isOriginAllowed("https://evil.com"));
    }

    @Test
    @DisplayName("isOriginAllowed returns false for null when specific origins")
    void testNullOrigin() {
        CorsConfig config = new CorsConfig(
            true,
            "http://localhost:3000",
            "GET",
            "*",
            null,
            true,
            3600
        );

        assertFalse(config.isOriginAllowed(null));
    }

    @Test
    @DisplayName("isMethodAllowed checks allowed methods")
    void testIsMethodAllowed() {
        CorsConfig config = new CorsConfig(
            true,
            "*",
            "GET, POST, PUT",
            "*",
            null,
            true,
            3600
        );

        assertTrue(config.isMethodAllowed("GET"));
        assertTrue(config.isMethodAllowed("POST"));
        assertTrue(config.isMethodAllowed("PUT"));
        assertFalse(config.isMethodAllowed("DELETE"));
    }

    @Test
    @DisplayName("Default config allows all methods")
    void testDefaultConfigAllMethods() {
        CorsConfig config = new CorsConfig();

        assertTrue(config.isMethodAllowed("GET"));
        assertTrue(config.isMethodAllowed("POST"));
        assertTrue(config.isMethodAllowed("PUT"));
        assertTrue(config.isMethodAllowed("DELETE"));
        assertTrue(config.isMethodAllowed("PATCH"));
        assertTrue(config.isMethodAllowed("OPTIONS"));
    }

    @Test
    @DisplayName("Disabled CORS config rejects all")
    void testDisabledConfig() {
        CorsConfig config = new CorsConfig(
            false,
            "*",
            "*",
            "*",
            null,
            true,
            3600
        );

        assertFalse(config.isOriginAllowed("http://localhost:3000"));
        assertFalse(config.isMethodAllowed("GET"));
    }

    @Test
    @DisplayName("buildCorsHeaders creates correct headers")
    void testBuildCorsHeaders() {
        CorsConfig config = new CorsConfig(
            true,
            "http://localhost:3000",
            "GET, POST",
            "Content-Type",
            null,
            true,
            3600
        );

        var headers = config.buildCorsHeaders("http://localhost:3000", "GET");

        // Origin is returned in uppercase
        assertTrue(headers.containsKey("Access-Control-Allow-Origin"));
        assertTrue(headers.containsKey("Access-Control-Allow-Methods"));
        assertTrue(headers.containsKey("Access-Control-Allow-Credentials"));
        assertTrue(headers.containsKey("Access-Control-Max-Age"));
    }

    @Test
    @DisplayName("buildCorsHeaders for disabled config returns empty map")
    void testBuildCorsHeadersDisabled() {
        CorsConfig config = new CorsConfig(
            false,
            "*",
            "*",
            "*",
            null,
            true,
            3600
        );

        var headers = config.buildCorsHeaders("http://example.com", "GET");

        assertTrue(headers.isEmpty());
    }

    @Test
    @DisplayName("Getters return correct values")
    void testGetters() {
        CorsConfig config = new CorsConfig(
            true,
            "http://example.com",
            "GET",
            "Authorization",
            "X-Custom",
            false,
            1800
        );

        assertTrue(config.isEnabled());
        // Origins and methods are stored uppercase
        boolean foundOrigin = config.getAllowedOrigins().stream()
            .anyMatch(o -> o.equalsIgnoreCase("http://example.com"));
        assertTrue(foundOrigin);
        assertTrue(config.getAllowedMethods().contains("GET"));
        boolean foundHeader = config.getAllowedHeaders().stream()
            .anyMatch(h -> h.equalsIgnoreCase("Authorization"));
        assertTrue(foundHeader);
        assertTrue(config.getExposedHeaders().contains("X-CUSTOM"));
        assertFalse(config.isAllowCredentials());
        assertEquals(1800, config.getMaxAge());
    }

    @Test
    @DisplayName("Methods are case-insensitive")
    void testMethodCaseInsensitive() {
        CorsConfig config = new CorsConfig(
            true, "*", "GET, POST", "*", null, true, 3600
        );

        assertTrue(config.isMethodAllowed("get"));
        assertTrue(config.isMethodAllowed("GET"));
        assertTrue(config.isMethodAllowed("Get"));
    }

    @Test
    @DisplayName("buildCorsHeaders includes exposed headers when configured")
    void testExposedHeaders() {
        CorsConfig config = new CorsConfig(
            true, "*", "GET", "*", "X-Custom-Header, X-Another", true, 3600
        );

        var headers = config.buildCorsHeaders("http://example.com", "GET");

        assertTrue(headers.containsKey("Access-Control-Expose-Headers"));
    }

    @Test
    @DisplayName("getAllowOriginHeaderValue returns * for wildcard")
    void testGetAllowOriginHeaderValueWildcard() {
        CorsConfig config = new CorsConfig();

        assertEquals("*", config.getAllowOriginHeaderValue("http://any.com"));
    }
}

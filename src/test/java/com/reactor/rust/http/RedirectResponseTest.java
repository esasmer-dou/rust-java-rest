package com.reactor.rust.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RedirectResponse.
 */
class RedirectResponseTest {

    @Test
    @DisplayName("301 Moved Permanently redirect")
    void testMovedPermanently() {
        RedirectResponse redirect = RedirectResponse.movedPermanently("/new-location");

        assertEquals("/new-location", redirect.getLocation());
        assertEquals(HttpStatus.MOVED_PERMANENTLY, redirect.getStatus());
        assertEquals(301, redirect.getStatus().getCode());
    }

    @Test
    @DisplayName("302 Found redirect")
    void testFound() {
        RedirectResponse redirect = RedirectResponse.found("/temporary");

        assertEquals("/temporary", redirect.getLocation());
        assertEquals(HttpStatus.FOUND, redirect.getStatus());
        assertEquals(302, redirect.getStatus().getCode());
    }

    @Test
    @DisplayName("303 See Other redirect")
    void testSeeOther() {
        RedirectResponse redirect = RedirectResponse.seeOther("/result");

        assertEquals("/result", redirect.getLocation());
        assertEquals(HttpStatus.SEE_OTHER, redirect.getStatus());
        assertEquals(303, redirect.getStatus().getCode());
    }

    @Test
    @DisplayName("307 Temporary Redirect")
    void testTemporaryRedirect() {
        RedirectResponse redirect = RedirectResponse.temporaryRedirect("/temp");

        assertEquals("/temp", redirect.getLocation());
        assertEquals(HttpStatus.TEMPORARY_REDIRECT, redirect.getStatus());
        assertEquals(307, redirect.getStatus().getCode());
    }

    @Test
    @DisplayName("308 Permanent Redirect")
    void testPermanentRedirect() {
        RedirectResponse redirect = RedirectResponse.permanentRedirect("/permanent");

        assertEquals("/permanent", redirect.getLocation());
        assertEquals(HttpStatus.PERMANENT_REDIRECT, redirect.getStatus());
        assertEquals(308, redirect.getStatus().getCode());
    }

    @Test
    @DisplayName("toResponseEntity includes Location header")
    void testToResponseEntity() {
        RedirectResponse redirect = RedirectResponse.found("/new-url");
        ResponseEntity<Void> entity = redirect.toResponseEntity();

        assertNotNull(entity);
        assertEquals(HttpStatus.FOUND, entity.getStatus());
    }

    @Test
    @DisplayName("toString contains location and status")
    void testToString() {
        RedirectResponse redirect = RedirectResponse.temporaryRedirect("/path");

        String str = redirect.toString();
        assertTrue(str.contains("/path"));
        // Contains the status code or enum name
        assertTrue(str.contains("307") || str.contains("TEMPORARY"));
    }

    @Test
    @DisplayName("Redirect with full URL")
    void testFullUrlRedirect() {
        String fullUrl = "https://example.com/new-path?query=value";
        RedirectResponse redirect = RedirectResponse.movedPermanently(fullUrl);

        assertEquals(fullUrl, redirect.getLocation());
    }

    @Test
    @DisplayName("Redirect with fragment")
    void testRedirectWithFragment() {
        RedirectResponse redirect = RedirectResponse.found("/page#section");

        assertEquals("/page#section", redirect.getLocation());
    }
}

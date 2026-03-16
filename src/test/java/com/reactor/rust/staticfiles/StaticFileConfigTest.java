package com.reactor.rust.staticfiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StaticFileConfig.
 */
class StaticFileConfigTest {

    @Test
    @DisplayName("Default configuration values")
    void testDefaultConfig() {
        StaticFileConfig config = new StaticFileConfig();

        assertTrue(config.isEnabled());
        assertEquals("/static", config.getUrlPattern());
        assertEquals("static", config.getResourcePath());
        assertEquals(3600, config.getCacheMaxAge());
        assertTrue(config.isEnableGzip());
        assertTrue(config.isEnableEtag());
    }

    @Test
    @DisplayName("Configuration with resource path only")
    void testConfigWithResourcePath() {
        StaticFileConfig config = new StaticFileConfig("public");

        assertTrue(config.isEnabled());
        assertEquals("public", config.getResourcePath());
        assertEquals("/static", config.getUrlPattern());
    }

    @Test
    @DisplayName("Full configuration")
    void testFullConfig() {
        StaticFileConfig config = new StaticFileConfig(
            false,
            "/assets",
            "web",
            7200,
            false,
            false
        );

        assertFalse(config.isEnabled());
        assertEquals("/assets", config.getUrlPattern());
        assertEquals("web", config.getResourcePath());
        assertEquals(7200, config.getCacheMaxAge());
        assertFalse(config.isEnableGzip());
        assertFalse(config.isEnableEtag());
    }

    @Test
    @DisplayName("getMimeType for HTML")
    void testMimeTypeHtml() {
        assertEquals("text/html", StaticFileConfig.getMimeType("index.html"));
        assertEquals("text/html", StaticFileConfig.getMimeType("page.htm"));
    }

    @Test
    @DisplayName("getMimeType for CSS")
    void testMimeTypeCss() {
        assertEquals("text/css", StaticFileConfig.getMimeType("styles.css"));
    }

    @Test
    @DisplayName("getMimeType for JavaScript")
    void testMimeTypeJavaScript() {
        assertEquals("application/javascript", StaticFileConfig.getMimeType("app.js"));
    }

    @Test
    @DisplayName("getMimeType for JSON")
    void testMimeTypeJson() {
        assertEquals("application/json", StaticFileConfig.getMimeType("data.json"));
    }

    @Test
    @DisplayName("getMimeType for XML")
    void testMimeTypeXml() {
        assertEquals("application/xml", StaticFileConfig.getMimeType("config.xml"));
    }

    @Test
    @DisplayName("getMimeType for text/plain")
    void testMimeTypeText() {
        assertEquals("text/plain", StaticFileConfig.getMimeType("readme.txt"));
    }

    @Test
    @DisplayName("getMimeType for CSV")
    void testMimeTypeCsv() {
        assertEquals("text/csv", StaticFileConfig.getMimeType("data.csv"));
    }

    @Test
    @DisplayName("getMimeType for images")
    void testMimeTypeImages() {
        assertEquals("image/png", StaticFileConfig.getMimeType("logo.png"));
        assertEquals("image/jpeg", StaticFileConfig.getMimeType("photo.jpg"));
        assertEquals("image/jpeg", StaticFileConfig.getMimeType("photo.jpeg"));
        assertEquals("image/gif", StaticFileConfig.getMimeType("animation.gif"));
        assertEquals("image/svg+xml", StaticFileConfig.getMimeType("icon.svg"));
        assertEquals("image/x-icon", StaticFileConfig.getMimeType("favicon.ico"));
        assertEquals("image/webp", StaticFileConfig.getMimeType("image.webp"));
    }

    @Test
    @DisplayName("getMimeType for fonts")
    void testMimeTypeFonts() {
        assertEquals("font/woff", StaticFileConfig.getMimeType("font.woff"));
        assertEquals("font/woff2", StaticFileConfig.getMimeType("font.woff2"));
        assertEquals("font/ttf", StaticFileConfig.getMimeType("font.ttf"));
        assertEquals("font/otf", StaticFileConfig.getMimeType("font.otf"));
        assertEquals("application/vnd.ms-fontobject", StaticFileConfig.getMimeType("font.eot"));
    }

    @Test
    @DisplayName("getMimeType for media")
    void testMimeTypeMedia() {
        assertEquals("audio/mpeg", StaticFileConfig.getMimeType("audio.mp3"));
        assertEquals("video/mp4", StaticFileConfig.getMimeType("video.mp4"));
        assertEquals("video/webm", StaticFileConfig.getMimeType("video.webm"));
        assertEquals("audio/ogg", StaticFileConfig.getMimeType("audio.ogg"));
    }

    @Test
    @DisplayName("getMimeType for documents")
    void testMimeTypeDocuments() {
        assertEquals("application/pdf", StaticFileConfig.getMimeType("document.pdf"));
        assertEquals("application/zip", StaticFileConfig.getMimeType("archive.zip"));
        assertEquals("application/gzip", StaticFileConfig.getMimeType("archive.gz"));
    }

    @Test
    @DisplayName("getMimeType for unknown extension")
    void testMimeTypeUnknown() {
        assertEquals("application/octet-stream", StaticFileConfig.getMimeType("file.xyz"));
        assertEquals("application/octet-stream", StaticFileConfig.getMimeType("file.unknown"));
    }

    @Test
    @DisplayName("getMimeType for file without extension")
    void testMimeTypeNoExtension() {
        assertEquals("application/octet-stream", StaticFileConfig.getMimeType("README"));
        assertEquals("application/octet-stream", StaticFileConfig.getMimeType("Makefile"));
    }

    @Test
    @DisplayName("getMimeType for null filename")
    void testMimeTypeNull() {
        assertEquals("application/octet-stream", StaticFileConfig.getMimeType(null));
    }

    @Test
    @DisplayName("getMimeType is case-insensitive")
    void testMimeTypeCaseInsensitive() {
        assertEquals("image/png", StaticFileConfig.getMimeType("logo.PNG"));
        assertEquals("text/html", StaticFileConfig.getMimeType("index.HTML"));
        assertEquals("application/json", StaticFileConfig.getMimeType("data.JSON"));
    }

    @Test
    @DisplayName("shouldGzip for text types")
    void testShouldGzipTextTypes() {
        assertTrue(StaticFileConfig.shouldGzip("text/html"));
        assertTrue(StaticFileConfig.shouldGzip("text/css"));
        assertTrue(StaticFileConfig.shouldGzip("text/plain"));
        assertTrue(StaticFileConfig.shouldGzip("text/xml"));
    }

    @Test
    @DisplayName("shouldGzip for application types")
    void testShouldGzipApplicationTypes() {
        assertTrue(StaticFileConfig.shouldGzip("application/javascript"));
        assertTrue(StaticFileConfig.shouldGzip("application/json"));
        assertTrue(StaticFileConfig.shouldGzip("application/xml"));
    }

    @Test
    @DisplayName("shouldGzip for SVG")
    void testShouldGzipSvg() {
        assertTrue(StaticFileConfig.shouldGzip("image/svg+xml"));
    }

    @Test
    @DisplayName("shouldGzip returns false for binary types")
    void testShouldNotGzipBinaryTypes() {
        assertFalse(StaticFileConfig.shouldGzip("image/png"));
        assertFalse(StaticFileConfig.shouldGzip("image/jpeg"));
        assertFalse(StaticFileConfig.shouldGzip("video/mp4"));
        assertFalse(StaticFileConfig.shouldGzip("application/octet-stream"));
        assertFalse(StaticFileConfig.shouldGzip("application/pdf"));
    }
}

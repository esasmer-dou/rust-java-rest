package com.reactor.rust.staticfiles;

import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.http.FileResponse;
import com.reactor.rust.http.HttpStatus;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.logging.FrameworkLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Handler for serving static files.
 * Supports ETags, cache control, and MIME type detection.
 */
public class StaticFileHandler {

    private StaticFileConfig config;

    public StaticFileHandler() {
        this.config = null; // Lazy init from BeanContainer
    }

    public StaticFileHandler(StaticFileConfig config) {
        this.config = config;
    }

    private StaticFileConfig getConfig() {
        if (config == null) {
            config = BeanContainer.getInstance().getBeansOfType(StaticFileConfig.class)
                    .stream()
                    .findFirst()
                    .orElse(new StaticFileConfig());
        }
        return config;
    }

    /**
     * Serve a static file.
     *
     * @param requestPath The request path (e.g., /static/css/style.css)
     * @param acceptEncoding Accept-Encoding header value
     * @return ResponseEntity with file content or 404
     */
    public ResponseEntity<?> serveFile(String requestPath, String acceptEncoding) {
        StaticFileConfig cfg = getConfig();

        if (!cfg.isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND, null);
        }

        // Extract file path from URL
        String filePath = extractFilePath(requestPath, cfg.getUrlPattern());
        if (filePath == null || filePath.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND, null);
        }

        // Security: prevent path traversal
        if (filePath.contains("..") || filePath.contains("//")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN, null);
        }

        // Try to load file
        try {
            String contentType = StaticFileConfig.getMimeType(filePath);
            Map<String, String> headers = new HashMap<>();
            byte[] content;

            // Try filesystem first, then classpath
            Path fsPath = Paths.get(cfg.getResourcePath(), filePath);
            if (Files.exists(fsPath) && Files.isRegularFile(fsPath)) {
                // Add last modified for caching
                long lastModified = Files.getLastModifiedTime(fsPath).toMillis();
                headers.put("Last-Modified", formatHttpDate(lastModified));

                if (cfg.isEnableEtag()) {
                    String etag = generateFileETag(Files.size(fsPath), lastModified);
                    headers.put("ETag", "\"" + etag + "\"");
                }

                if (cfg.getCacheMaxAge() > 0) {
                    headers.put("Cache-Control", "public, max-age=" + cfg.getCacheMaxAge());
                }

                FileResponse response = FileResponse.of(fsPath, contentType);
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    response.header(header.getKey(), header.getValue());
                }

                return ResponseEntity.status(HttpStatus.OK, response);
            } else {
                // Try classpath
                String resourcePath = cfg.getResourcePath() + "/" + filePath;
                try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND, null);
                    }
                    content = is.readAllBytes();

                    // Generate ETag based on content hash
                    if (cfg.isEnableEtag()) {
                        String etag = generateETag(content, System.currentTimeMillis());
                        headers.put("ETag", "\"" + etag + "\"");
                    }
                }
            }

            // Classpath resources have no filesystem path; keep this path for small packaged assets.
            boolean useGzip = cfg.isEnableGzip() &&
                    StaticFileConfig.shouldGzip(contentType) &&
                    acceptEncoding != null &&
                    acceptEncoding.contains("gzip");

            byte[] responseBody = content;
            if (useGzip) {
                responseBody = gzip(content);
                headers.put("Content-Encoding", "gzip");
            }

            // Add cache headers
            if (cfg.getCacheMaxAge() > 0) {
                headers.put("Cache-Control", "public, max-age=" + cfg.getCacheMaxAge());
            }

            headers.put("Content-Type", contentType);

            StaticFileResponse response = new StaticFileResponse(responseBody, contentType);

            ResponseEntity<StaticFileResponse> entity = ResponseEntity.status(HttpStatus.OK, response);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                entity.header(header.getKey(), header.getValue());
            }

            return entity;

        } catch (IOException e) {
            FrameworkLogger.warn("[StaticFiles] Error serving file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR, null);
        }
    }

    /**
     * Check if request path matches static file pattern.
     */
    public boolean isStaticFileRequest(String requestPath) {
        StaticFileConfig cfg = getConfig();
        if (!cfg.isEnabled()) {
            return false;
        }

        String pattern = cfg.getUrlPattern();
        if (!pattern.startsWith("/")) {
            pattern = "/" + pattern;
        }

        return requestPath != null && requestPath.startsWith(pattern);
    }

    /**
     * Extract file path from URL path.
     */
    private String extractFilePath(String requestPath, String urlPattern) {
        if (requestPath == null || urlPattern == null) {
            return null;
        }

        // Remove URL pattern prefix
        String pattern = urlPattern;
        if (!pattern.startsWith("/")) {
            pattern = "/" + pattern;
        }

        if (requestPath.startsWith(pattern)) {
            String filePath = requestPath.substring(pattern.length());
            if (filePath.startsWith("/")) {
                filePath = filePath.substring(1);
            }
            return filePath;
        }

        return null;
    }

    /**
     * Generate ETag from content hash.
     */
    private String generateETag(byte[] content, long lastModified) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(content);
            md.update(Long.toString(lastModified).getBytes());
            byte[] digest = md.digest();
            return bytesToHex(digest).substring(0, 16);
        } catch (Exception e) {
            return Long.toHexString(lastModified);
        }
    }

    private String generateFileETag(long size, long lastModified) {
        return Long.toHexString(size) + "-" + Long.toHexString(lastModified);
    }

    /**
     * Convert bytes to hex string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Format date for HTTP headers.
     */
    private String formatHttpDate(long timestamp) {
        return new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                .format(new java.util.Date(timestamp));
    }

    /**
     * Gzip compress bytes.
     */
    private byte[] gzip(byte[] data) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(baos)) {
            gzip.write(data);
        }
        return baos.toByteArray();
    }

    /**
     * Static file response record.
     */
    public record StaticFileResponse(byte[] content, String contentType) {}
}

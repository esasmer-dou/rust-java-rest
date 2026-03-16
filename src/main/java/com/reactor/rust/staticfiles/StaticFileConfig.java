package com.reactor.rust.staticfiles;

/**
 * Configuration for static file serving.
 */
public class StaticFileConfig {

    private final boolean enabled;
    private final String urlPattern;
    private final String resourcePath;
    private final int cacheMaxAge;
    private final boolean enableGzip;
    private final boolean enableEtag;

    /**
     * Create with defaults:
     * - URL pattern: /static/**
     * - Resource path: static
     * - Cache max age: 3600 seconds
     * - Gzip: enabled
     * - ETag: enabled
     */
    public StaticFileConfig() {
        this(true, "/static", "static", 3600, true, true);
    }

    /**
     * Create with resource path only.
     */
    public StaticFileConfig(String resourcePath) {
        this(true, "/static", resourcePath, 3600, true, true);
    }

    /**
     * Create with all options.
     *
     * @param enabled Enable static file serving
     * @param urlPattern URL pattern to match (e.g., "/static")
     * @param resourcePath Path to static resources (classpath or filesystem)
     * @param cacheMaxAge Cache-Control max-age in seconds
     * @param enableGzip Enable gzip compression
     * @param enableEtag Enable ETag generation
     */
    public StaticFileConfig(boolean enabled, String urlPattern, String resourcePath,
                            int cacheMaxAge, boolean enableGzip, boolean enableEtag) {
        this.enabled = enabled;
        this.urlPattern = urlPattern;
        this.resourcePath = resourcePath;
        this.cacheMaxAge = cacheMaxAge;
        this.enableGzip = enableGzip;
        this.enableEtag = enableEtag;
    }

    public boolean isEnabled() { return enabled; }
    public String getUrlPattern() { return urlPattern; }
    public String getResourcePath() { return resourcePath; }
    public int getCacheMaxAge() { return cacheMaxAge; }
    public boolean isEnableGzip() { return enableGzip; }
    public boolean isEnableEtag() { return enableEtag; }

    /**
     * Get MIME type for file extension.
     */
    public static String getMimeType(String filename) {
        if (filename == null) return "application/octet-stream";

        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) return "application/octet-stream";

        String ext = filename.substring(dotIndex + 1).toLowerCase();
        return switch (ext) {
            // Text
            case "html", "htm" -> "text/html";
            case "css" -> "text/css";
            case "js" -> "application/javascript";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "txt" -> "text/plain";
            case "csv" -> "text/csv";

            // Images
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            case "webp" -> "image/webp";

            // Fonts
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "eot" -> "application/vnd.ms-fontobject";

            // Media
            case "mp3" -> "audio/mpeg";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "ogg" -> "audio/ogg";

            // Documents
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            case "gz" -> "application/gzip";

            default -> "application/octet-stream";
        };
    }

    /**
     * Check if file should be gzipped.
     */
    public static boolean shouldGzip(String mimeType) {
        return mimeType.startsWith("text/") ||
               mimeType.equals("application/javascript") ||
               mimeType.equals("application/json") ||
               mimeType.equals("application/xml") ||
               mimeType.equals("image/svg+xml");
    }
}

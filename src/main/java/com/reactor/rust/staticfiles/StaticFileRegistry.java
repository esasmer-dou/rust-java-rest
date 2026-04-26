package com.reactor.rust.staticfiles;

import com.reactor.rust.annotations.StaticFiles;
import com.reactor.rust.logging.FrameworkLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for static file configurations.
 * Manages static file locations and serves files.
 */
public class StaticFileRegistry {

    private static final StaticFileRegistry INSTANCE = new StaticFileRegistry();

    // MIME types cache
    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        // Common MIME types
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("htm", "text/html");
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("js", "application/javascript");
        MIME_TYPES.put("json", "application/json");
        MIME_TYPES.put("xml", "application/xml");
        MIME_TYPES.put("txt", "text/plain");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("jpeg", "image/jpeg");
        MIME_TYPES.put("gif", "image/gif");
        MIME_TYPES.put("svg", "image/svg+xml");
        MIME_TYPES.put("ico", "image/x-icon");
        MIME_TYPES.put("webp", "image/webp");
        MIME_TYPES.put("woff", "font/woff");
        MIME_TYPES.put("woff2", "font/woff2");
        MIME_TYPES.put("ttf", "font/ttf");
        MIME_TYPES.put("eot", "application/vnd.ms-fontobject");
        MIME_TYPES.put("otf", "font/otf");
        MIME_TYPES.put("mp4", "video/mp4");
        MIME_TYPES.put("webm", "video/webm");
        MIME_TYPES.put("mp3", "audio/mpeg");
        MIME_TYPES.put("wav", "audio/wav");
        MIME_TYPES.put("pdf", "application/pdf");
        MIME_TYPES.put("zip", "application/zip");
        MIME_TYPES.put("gz", "application/gzip");
        MIME_TYPES.put("tar", "application/x-tar");
    }

    // Static file configurations: urlPath -> config
    private final Map<String, StaticFileConfig> configs = new ConcurrentHashMap<>();

    // File cache: path -> bytes (optional, for small files)
    private final Map<String, byte[]> fileCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 1024 * 1024; // 1MB max cached file

    public static StaticFileRegistry getInstance() {
        return INSTANCE;
    }

    private StaticFileRegistry() {}

    /**
     * Register a static file configuration.
     */
    public void register(StaticFiles annotation) {
        String urlPath = annotation.path();
        if (!urlPath.startsWith("/")) {
            urlPath = "/" + urlPath;
        }
        if (!urlPath.endsWith("/")) {
            urlPath = urlPath + "/";
        }

        StaticFileConfig config = new StaticFileConfig(
            urlPath,
            annotation.location(),
            annotation.directoryListing(),
            annotation.cacheMaxAge(),
            annotation.indexFile()
        );

        configs.put(urlPath, config);
        FrameworkLogger.info("[StaticFiles] Registered: " + urlPath + " -> " + annotation.location());
    }

    /**
     * Try to serve a static file for the given request path.
     * @param requestPath The request path (e.g., "/static/css/style.css")
     * @return StaticFileResponse if found, null if no static file matches
     */
    public StaticFileResponse serveFile(String requestPath) {
        // Find matching config
        StaticFileConfig config = null;
        String relativePath = null;

        for (Map.Entry<String, StaticFileConfig> entry : configs.entrySet()) {
            String prefix = entry.getKey();
            if (requestPath.startsWith(prefix) || requestPath.equals(prefix.substring(0, prefix.length() - 1))) {
                config = entry.getValue();
                relativePath = requestPath.substring(prefix.length());
                break;
            }
        }

        if (config == null) {
            return null;
        }

        // Handle empty path -> index file
        if (relativePath == null || relativePath.isEmpty() || relativePath.equals("/")) {
            if (!config.indexFile.isEmpty()) {
                relativePath = config.indexFile;
            } else {
                return null;
            }
        }

        // Remove leading slash
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }

        // Build resource path
        String resourcePath = config.location + "/" + relativePath;

        // Try to load file
        byte[] content = loadFile(resourcePath);
        if (content == null) {
            return null;
        }

        String contentType = getMimeType(relativePath);
        String cacheControl = config.cacheMaxAge > 0
            ? "public, max-age=" + config.cacheMaxAge
            : "no-cache";

        return new StaticFileResponse(content, contentType, cacheControl);
    }

    /**
     * Check if a path matches a static file route.
     */
    public boolean isStaticPath(String requestPath) {
        for (String prefix : configs.keySet()) {
            if (requestPath.startsWith(prefix) ||
                requestPath.equals(prefix.substring(0, prefix.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Load file from classpath or filesystem.
     */
    private byte[] loadFile(String resourcePath) {
        // Check cache first
        byte[] cached = fileCache.get(resourcePath);
        if (cached != null) {
            return cached;
        }

        // Try classpath
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                byte[] content = is.readAllBytes();

                // Cache small files
                if (content.length <= MAX_CACHE_SIZE) {
                    fileCache.put(resourcePath, content);
                }

                return content;
            }
        } catch (IOException e) {
            // Ignore
        }

        // Try filesystem (for development)
        try {
            Path filePath = Paths.get("src/main/resources/" + resourcePath);
            if (Files.exists(filePath)) {
                byte[] content = Files.readAllBytes(filePath);

                // Cache small files
                if (content.length <= MAX_CACHE_SIZE) {
                    fileCache.put(resourcePath, content);
                }

                return content;
            }
        } catch (IOException e) {
            // Ignore
        }

        return null;
    }

    /**
     * Get MIME type for file extension.
     */
    private String getMimeType(String path) {
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx > 0 && dotIdx < path.length() - 1) {
            String ext = path.substring(dotIdx + 1).toLowerCase();
            String mimeType = MIME_TYPES.get(ext);
            if (mimeType != null) {
                return mimeType;
            }
        }
        return "application/octet-stream";
    }

    /**
     * Clear file cache (useful for development).
     */
    public void clearCache() {
        fileCache.clear();
    }

    /**
     * Static file configuration.
     */
    public static class StaticFileConfig {
        public final String urlPath;
        public final String location;
        public final boolean directoryListing;
        public final int cacheMaxAge;
        public final String indexFile;

        public StaticFileConfig(String urlPath, String location,
                boolean directoryListing, int cacheMaxAge, String indexFile) {
            this.urlPath = urlPath;
            this.location = location;
            this.directoryListing = directoryListing;
            this.cacheMaxAge = cacheMaxAge;
            this.indexFile = indexFile;
        }
    }

    /**
     * Response for static file.
     */
    public static class StaticFileResponse {
        public final byte[] content;
        public final String contentType;
        public final String cacheControl;

        public StaticFileResponse(byte[] content, String contentType, String cacheControl) {
            this.content = content;
            this.contentType = contentType;
            this.cacheControl = cacheControl;
        }
    }
}

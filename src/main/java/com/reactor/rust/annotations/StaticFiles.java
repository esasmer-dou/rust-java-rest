package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to configure static file serving.
 * Place on any @Component class to enable static file serving.
 *
 * Example:
 * <pre>
 * {@code
 * @Component
 * @StaticFiles(path = "/static", location = "static")
 * public class StaticFileConfig {}
 * }
 * </pre>
 *
 * This will serve files from classpath:/static/ at /static/*
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StaticFiles {
    /**
     * URL path prefix for static files (e.g., "/static")
     */
    String path();

    /**
     * Classpath location of static files (e.g., "static" or "public")
     * Default: "static"
     */
    String location() default "static";

    /**
     * Enable directory listing (default: false)
     */
    boolean directoryListing() default false;

    /**
     * Cache max-age in seconds (default: 3600 = 1 hour)
     * Set to 0 to disable caching
     */
    int cacheMaxAge() default 3600;

    /**
     * Index file to serve for directory requests (default: "index.html")
     * Set to empty string to disable
     */
    String indexFile() default "index.html";
}

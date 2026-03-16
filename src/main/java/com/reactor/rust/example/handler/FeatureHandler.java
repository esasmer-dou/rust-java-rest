package com.reactor.rust.example.handler;

import com.reactor.rust.annotations.*;
import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.http.*;

/**
 * Feature Handler - Demonstrates new HTTP features.
 *
 * Features:
 * - Redirect responses (301, 302, 307, 308)
 * - ETag generation and conditional requests
 * - Response compression
 * - HTTP status codes
 */
@Component
@RequestMapping("/features")
public class FeatureHandler {

    /**
     * GET /features/redirect/301
     * Permanent redirect (301) - cached by browsers
     */
    @GetMapping("/redirect/301")
    public ResponseEntity<Void> redirect301() {
        RedirectResponse redirect = RedirectResponse.movedPermanently("/features/info");
        return redirect.toResponseEntity();
    }

    /**
     * GET /features/redirect/302
     * Temporary redirect (302) - default for temporary redirects
     */
    @GetMapping("/redirect/302")
    public ResponseEntity<Void> redirect302() {
        RedirectResponse redirect = RedirectResponse.found("/features/info");
        return redirect.toResponseEntity();
    }

    /**
     * GET /features/redirect/307
     * Temporary redirect preserving method (307)
     */
    @GetMapping("/redirect/307")
    public ResponseEntity<Void> redirect307() {
        RedirectResponse redirect = RedirectResponse.temporaryRedirect("/features/info");
        return redirect.toResponseEntity();
    }

    /**
     * GET /features/redirect/308
     * Permanent redirect preserving method (308)
     */
    @GetMapping("/redirect/308")
    public ResponseEntity<Void> redirect308() {
        RedirectResponse redirect = RedirectResponse.permanentRedirect("/features/info");
        return redirect.toResponseEntity();
    }

    /**
     * GET /features/info
     * Returns feature information
     */
    @GetMapping("/info")
    public ResponseEntity<FeatureInfo> getInfo() {
        return ResponseEntity.ok(new FeatureInfo(
            "Rust-Java REST Framework",
            "2.0.0",
            "HTTP features demo"
        ));
    }

    /**
     * GET /features/etag
     * Demonstrates ETag generation
     */
    @GetMapping("/etag")
    public ResponseEntity<EtagResponse> getWithEtag(@HeaderParam("If-None-Match") String ifNoneMatch) {
        String content = "This is some content that can be cached";
        ETag etag = ETag.fromContent(content.getBytes());

        // Check if client has current version
        if (etag.matchesIfNoneMatch(ifNoneMatch)) {
            // Return 304 Not Modified
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED, null);
        }

        ResponseEntity<EtagResponse> response = ResponseEntity.ok(
            new EtagResponse(content, etag.toHeader())
        );
        response.header("ETag", etag.toHeader());
        response.header("Cache-Control", "public, max-age=3600");
        return response;
    }

    /**
     * POST /features/compress
     * Demonstrates compression utilities
     */
    @PostMapping("/compress")
    public ResponseEntity<CompressResponse> compressData(
            @RequestBody CompressRequest request,
            @HeaderParam("Accept-Encoding") String acceptEncoding
    ) throws Exception {
        byte[] data = request.data().getBytes();
        byte[] compressed = null;
        String encoding = null;

        if (CompressionUtils.supportsGzip(acceptEncoding)) {
            compressed = CompressionUtils.gzip(data);
            encoding = "gzip";
        } else if (CompressionUtils.supportsDeflate(acceptEncoding)) {
            compressed = CompressionUtils.deflate(data);
            encoding = "deflate";
        }

        double ratio = compressed != null
            ? CompressionUtils.compressionRatio(data.length, compressed.length)
            : 1.0;

        return ResponseEntity.ok(new CompressResponse(
            data.length,
            compressed != null ? compressed.length : data.length,
            encoding,
            ratio
        ));
    }

    /**
     * GET /features/status/{code}
     * Returns specified HTTP status code
     */
    @GetMapping("/status/{code}")
    public ResponseEntity<StatusResponse> getStatus(@PathVariable("code") int code) {
        HttpStatus status;
        try {
            status = HttpStatus.valueOf(code);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST,
                new StatusResponse(400, "Invalid status code: " + code));
        }

        return ResponseEntity.status(status,
            new StatusResponse(status.getCode(), status.getReason()));
    }

    /**
     * GET /features/cors-test
     * Test CORS configuration
     */
    @GetMapping("/cors-test")
    public ResponseEntity<CorsTestResponse> corsTest(
            @HeaderParam("Origin") String origin
    ) {
        return ResponseEntity.ok(new CorsTestResponse(
            "CORS test successful",
            origin != null ? origin : "no-origin"
        ));
    }

    // DTOs
    public record FeatureInfo(String name, String version, String description) {}
    public record EtagResponse(String content, String etag) {}
    public record CompressRequest(String data) {}
    public record CompressResponse(int originalSize, int compressedSize, String encoding, double ratio) {}
    public record StatusResponse(int code, String message) {}
    public record CorsTestResponse(String message, String detectedOrigin) {}
}

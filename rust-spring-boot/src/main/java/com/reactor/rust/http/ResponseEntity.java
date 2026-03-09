package com.reactor.rust.http;

import com.reactor.rust.json.DslJsonService;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Response entity wrapper that includes body, status, and headers.
 *
 * Example:
 * <pre>
 * {@code
 * return ResponseEntity.ok(new OrderResponse(1, "OK"));
 * return ResponseEntity.created(new OrderResponse(1, "Created")).header("X-Request-ID", "123");
 * return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("Not found"));
 * }
 * </pre>
 */
public class ResponseEntity<T> {

    private final T body;
    private final HttpStatus status;
    private final Map<String, String> headers;

    public ResponseEntity(T body, HttpStatus status) {
        this.body = body;
        this.status = status;
        this.headers = new HashMap<>();
    }

    public ResponseEntity(T body, HttpStatus status, Map<String, String> headers) {
        this.body = body;
        this.status = status;
        this.headers = headers != null ? headers : new HashMap<>();
    }

    public T getBody() {
        return body;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public ResponseEntity<T> header(String name, String value) {
        this.headers.put(name, value);
        return this;
    }

    // Static factory methods

    public static <T> ResponseEntity<T> ok(T body) {
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    public static <T> ResponseEntity<T> created(T body) {
        return new ResponseEntity<>(body, HttpStatus.CREATED);
    }

    public static <T> ResponseEntity<T> accepted(T body) {
        return new ResponseEntity<>(body, HttpStatus.ACCEPTED);
    }

    public static <T> ResponseEntity<T> noContent() {
        return new ResponseEntity<>(null, HttpStatus.NO_CONTENT);
    }

    public static <T> ResponseEntity<T> badRequest(T body) {
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    public static <T> ResponseEntity<T> notFound(T body) {
        return new ResponseEntity<>(body, HttpStatus.NOT_FOUND);
    }

    public static <T> ResponseEntity<T> internalServerError(T body) {
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public static <T> ResponseEntity<T> status(HttpStatus status) {
        return new ResponseEntity<>(null, status);
    }

    /**
     * Set the body of this response and return a new ResponseEntity.
     */
    public <B> ResponseEntity<B> body(B body) {
        return new ResponseEntity<>(body, this.status, this.headers);
    }

    public static <T> ResponseEntity<T> status(HttpStatus status, T body) {
        return new ResponseEntity<>(body, status);
    }

    /**
     * Serialize body to ByteBuffer for Rust response.
     */
    public int writeToBuffer(ByteBuffer out, int offset) {
        if (body == null) {
            return 0;
        }
        return DslJsonService.writeToBuffer(body, out, offset);
    }

    /**
     * Serialize headers to string for Rust response.
     */
    public String serializeHeaders() {
        if (headers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }
}

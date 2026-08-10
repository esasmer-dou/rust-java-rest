package com.reactor.rust.test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Small JDK-only HTTP client for framework integration tests. */
public final class ReactorTestClient {
    private final URI baseUri;
    private final HttpClient client;

    ReactorTestClient(int port) {
        this.baseUri = URI.create("http://127.0.0.1:" + port);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public URI baseUri() {
        return baseUri;
    }

    public Response get(String path) {
        return exchange("GET", path, null, Map.of());
    }

    public Response delete(String path) {
        return exchange("DELETE", path, null, Map.of());
    }

    public Response postJson(String path, String json) {
        return exchange("POST", path, json, Map.of("Content-Type", "application/json; charset=utf-8"));
    }

    public Response putJson(String path, String json) {
        return exchange("PUT", path, json, Map.of("Content-Type", "application/json; charset=utf-8"));
    }

    public Response patchJson(String path, String json) {
        return exchange("PATCH", path, json, Map.of("Content-Type", "application/json; charset=utf-8"));
    }

    public Response exchange(String method, String path, String body, Map<String, String> headers) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest.Builder request = HttpRequest.newBuilder(resolve(path))
                .timeout(Duration.ofSeconds(5))
                .method(method, publisher);
        headers.forEach(request::header);
        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), response.headers().map(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test HTTP request was interrupted", interrupted);
        } catch (IOException error) {
            throw new IllegalStateException("Test HTTP request failed: " + method + " " + path, error);
        }
    }

    private URI resolve(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        return baseUri.resolve(normalized);
    }

    public record Response(int status, Map<String, java.util.List<String>> headers, byte[] body) {
        public String bodyUtf8() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            for (Map.Entry<String, java.util.List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().getFirst();
                }
            }
            return null;
        }
    }
}

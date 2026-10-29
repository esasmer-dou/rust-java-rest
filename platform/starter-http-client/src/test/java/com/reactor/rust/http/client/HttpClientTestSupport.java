package com.reactor.rust.http.client;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

final class HttpClientTestSupport {
    private HttpClientTestSupport() {}

    static ReactorHttpClientRuntime runtime(Function<HttpRequest, CompletableFuture<HttpResponse<byte[]>>> send,
                                             List<OutboundHeaderProvider> providers) {
        return new ReactorHttpClientRuntime(Executors.newSingleThreadExecutor(), new StubClient(send),
                new Semaphore(4), 1, 1000L, 1L, 1024, 1024, 32, 1000L, providers);
    }

    static CompletableFuture<HttpResponse<byte[]>> response(HttpRequest request, int status, String body) {
        return CompletableFuture.completedFuture(new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return request; }
            public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
            public byte[] body() { return body.getBytes(StandardCharsets.UTF_8); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return request.uri(); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        });
    }

    private static final class StubClient extends HttpClient {
        private final Function<HttpRequest, CompletableFuture<HttpResponse<byte[]>>> send;
        StubClient(Function<HttpRequest, CompletableFuture<HttpResponse<byte[]>>> send) { this.send = send; }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) send.apply(request);
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                               HttpResponse.PushPromiseHandler<T> push) {
            return sendAsync(request, handler);
        }
    }
}

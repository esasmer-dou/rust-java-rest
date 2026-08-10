package com.reactor.rust.http.client;

import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.json.DslJsonService;
import com.reactor.rust.metrics.Metrics;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared bounded runtime used by generated outbound HTTP clients. */
public final class ReactorHttpClientRuntime implements AutoCloseable {
    private final ExecutorService executor;
    private final HttpClient client;
    private final Semaphore inFlight;
    private final int defaultRetries;
    private final long defaultTimeoutMs;
    private final long retryBackoffMs;
    private final int maxRequestBytes;
    private final int maxResponseBytes;
    private final int maxHeaders;
    private final long shutdownTimeoutMs;
    private final List<OutboundHeaderProvider> headerProviders;

    private ReactorHttpClientRuntime(
            ExecutorService executor,
            HttpClient client,
            Semaphore inFlight,
            int defaultRetries,
            long defaultTimeoutMs,
            long retryBackoffMs,
            int maxRequestBytes,
            int maxResponseBytes,
            int maxHeaders,
            long shutdownTimeoutMs,
            List<OutboundHeaderProvider> headerProviders) {
        this.executor = executor;
        this.client = client;
        this.inFlight = inFlight;
        this.defaultRetries = defaultRetries;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.retryBackoffMs = retryBackoffMs;
        this.maxRequestBytes = maxRequestBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.maxHeaders = maxHeaders;
        this.shutdownTimeoutMs = shutdownTimeoutMs;
        this.headerProviders = headerProviders;
    }

    public static ReactorHttpClientRuntime fromProperties() {
        int threads = positive("reactor.http-client.threads", 2);
        int queueCapacity = positive("reactor.http-client.queue-capacity", 256);
        int maxInFlight = positive("reactor.http-client.max-inflight", 128);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ClientThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(PropertiesLoader.getBoolean(
                "reactor.http-client.allow-core-thread-timeout", true));
        long connectTimeout = positiveLong("reactor.http-client.connect-timeout-ms", 1_000L);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return new ReactorHttpClientRuntime(
                executor,
                client,
                new Semaphore(maxInFlight),
                bounded("reactor.http-client.retries", 1, 0, 3),
                positiveLong("reactor.http-client.request-timeout-ms", 2_000L),
                Math.max(0L, PropertiesLoader.getLong("reactor.http-client.retry-backoff-ms", 25L)),
                positive("reactor.http-client.max-request-bytes", 8 * 1024 * 1024),
                positive("reactor.http-client.max-response-bytes", 8 * 1024 * 1024),
                positive("reactor.http-client.max-headers", 32),
                positiveLong("reactor.http-client.shutdown-timeout-ms", 5_000L),
                loadHeaderProviders());
    }

    public Client client(String baseUrlProperty) {
        String baseUrl = PropertiesLoader.require(baseUrlProperty);
        URI baseUri = URI.create(baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl);
        String scheme = baseUri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || baseUri.getHost() == null
                || baseUri.getRawQuery() != null
                || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("Property " + baseUrlProperty + " must be an absolute HTTP(S) URL");
        }
        return new Client(this, baseUri);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static List<OutboundHeaderProvider> loadHeaderProviders() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = ReactorHttpClientRuntime.class.getClassLoader();
        return ServiceLoader.load(OutboundHeaderProvider.class, loader).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    private static int positive(String key, int fallback) {
        int value = PropertiesLoader.getInt(key, fallback);
        if (value <= 0) throw new IllegalArgumentException(key + " must be > 0");
        return value;
    }

    private static long positiveLong(String key, long fallback) {
        long value = PropertiesLoader.getLong(key, fallback);
        if (value <= 0L) throw new IllegalArgumentException(key + " must be > 0");
        return value;
    }

    private static int bounded(String key, int fallback, int minimum, int maximum) {
        int value = PropertiesLoader.getInt(key, fallback);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    public static final class Client {
        private final ReactorHttpClientRuntime runtime;
        private final URI baseUri;

        private Client(ReactorHttpClientRuntime runtime, URI baseUri) {
            this.runtime = runtime;
            this.baseUri = baseUri;
        }

        public Request request(
                String method,
                String path,
                String contentType,
                String accept,
                long timeoutMs,
                int retries,
                boolean idempotent) {
            return new Request(this, method, path, contentType, accept, timeoutMs, retries, idempotent);
        }
    }

    public static final class Request {
        private final Client owner;
        private final String method;
        private String path;
        private final String contentType;
        private final String accept;
        private final long timeoutMs;
        private final int retries;
        private final boolean idempotent;
        private ArrayList<String> query;
        private ArrayList<String> headers;
        private Object body;

        private Request(
                Client owner,
                String method,
                String path,
                String contentType,
                String accept,
                long timeoutMs,
                int retries,
                boolean idempotent) {
            this.owner = owner;
            this.method = Objects.requireNonNull(method, "method").toUpperCase(java.util.Locale.ROOT);
            this.path = normalizePath(path);
            this.contentType = contentType == null ? "application/json; charset=utf-8" : contentType;
            this.accept = accept == null || accept.isBlank() ? "application/json" : accept;
            this.timeoutMs = timeoutMs;
            this.retries = retries;
            this.idempotent = idempotent || "GET".equals(this.method) || "PUT".equals(this.method)
                    || "DELETE".equals(this.method);
        }

        public Request path(String name, Object value) {
            if (value == null) throw new IllegalArgumentException("Path variable " + name + " is null");
            String marker = "{" + name + '}';
            if (!path.contains(marker)) throw new IllegalArgumentException("Path template does not contain " + marker);
            path = path.replace(marker, encode(value));
            return this;
        }

        public Request query(String name, Object value) {
            if (value == null) return this;
            if (value instanceof Iterable<?> values) {
                for (Object item : values) addQuery(name, item);
                return this;
            }
            addQuery(name, value);
            return this;
        }

        private void addQuery(String name, Object value) {
            if (value == null) return;
            if (query == null) query = new ArrayList<>(4);
            query.add(encode(name));
            query.add(encode(value));
        }

        public Request header(String name, Object value) {
            if (value == null) return this;
            if (headers == null) headers = new ArrayList<>(8);
            if (headers.size() / 2 >= owner.runtime.maxHeaders) {
                throw new IllegalArgumentException("Outbound request header count exceeds " + owner.runtime.maxHeaders);
            }
            headers.add(name);
            headers.add(String.valueOf(value));
            return this;
        }

        public Request body(Object body) {
            this.body = body;
            return this;
        }

        public <T> CompletionStage<T> execute(Class<T> responseType) {
            return executeInternal(bytes -> decode(bytes, responseType), false)
                    .thenApply(HttpClientResponse::body);
        }

        public <T> CompletionStage<HttpClientResponse<T>> executeResponse(Class<T> responseType) {
            return executeInternal(bytes -> decode(bytes, responseType), true);
        }

        public <T> CompletionStage<List<T>> executeList(Class<T> elementType) {
            return executeInternal(bytes -> decodeList(bytes, elementType), false)
                    .thenApply(HttpClientResponse::body);
        }

        public <T> CompletionStage<HttpClientResponse<List<T>>> executeListResponse(Class<T> elementType) {
            return executeInternal(bytes -> decodeList(bytes, elementType), true);
        }

        private <T> CompletableFuture<HttpClientResponse<T>> executeInternal(
                ResponseDecoder<T> decoder,
                boolean exposeErrorResponse) {
            ReactorHttpClientRuntime runtime = owner.runtime;
            if (!runtime.inFlight.tryAcquire()) {
                Metrics.getInstance().increment("reactor_http_client_rejected_total");
                return CompletableFuture.failedFuture(new HttpClientException("Outbound HTTP bulkhead is full", 503));
            }
            CompletableFuture<HttpClientResponse<T>> result;
            try {
                HttpRequest request = build();
                int configuredRetries = retries < 0
                        ? (idempotent ? runtime.defaultRetries : 0)
                        : retries;
                if (configuredRetries < 0 || configuredRetries > 3) {
                    throw new IllegalArgumentException("HTTP retries must be between 0 and 3");
                }
                if (configuredRetries > 0 && !idempotent) {
                    throw new IllegalArgumentException("Retries require an idempotent HTTP exchange");
                }
                result = attempt(request, decoder, exposeErrorResponse, configuredRetries, 0);
            } catch (Throwable failure) {
                runtime.inFlight.release();
                return CompletableFuture.failedFuture(failure);
            }
            return result.whenComplete((ignored, failure) -> runtime.inFlight.release());
        }

        private HttpRequest build() {
            if (path.indexOf('{') >= 0 || path.indexOf('}') >= 0) {
                throw new IllegalArgumentException("Not all outbound path variables were resolved: " + path);
            }
            StringBuilder target = new StringBuilder(owner.baseUri.toString().length() + path.length() + 32)
                    .append(owner.baseUri).append(path);
            if (query != null && !query.isEmpty()) {
                target.append('?');
                for (int index = 0; index < query.size(); index += 2) {
                    if (index > 0) target.append('&');
                    target.append(query.get(index)).append('=').append(query.get(index + 1));
                }
            }
            byte[] bytes = encodeBody(body);
            if (bytes != null && bytes.length > owner.runtime.maxRequestBytes) {
                throw new HttpClientException(
                        "Outbound HTTP request exceeds " + owner.runtime.maxRequestBytes + " bytes", 413);
            }
            HttpRequest.BodyPublisher publisher = bytes == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(bytes);
            long effectiveTimeout = timeoutMs > 0L ? timeoutMs : owner.runtime.defaultTimeoutMs;
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target.toString()))
                    .timeout(Duration.ofMillis(effectiveTimeout))
                    .method(method, publisher)
                    .header("Accept", accept);
            if (bytes != null) builder.header("Content-Type", contentType);
            if (headers != null) {
                for (int index = 0; index < headers.size(); index += 2) {
                    builder.header(headers.get(index), headers.get(index + 1));
                }
            }
            for (OutboundHeaderProvider provider : owner.runtime.headerProviders) {
                provider.contribute(builder::header);
            }
            return builder.build();
        }

        private <T> CompletableFuture<HttpClientResponse<T>> attempt(
                HttpRequest request,
                ResponseDecoder<T> decoder,
                boolean exposeErrorResponse,
                int maximumRetries,
                int attempt) {
            Metrics.getInstance().increment("reactor_http_client_requests_total");
            CompletableFuture<HttpResponse<byte[]>> response;
            try {
                response = owner.runtime.client.sendAsync(request, info -> new BoundedBodySubscriber(
                        owner.runtime.maxResponseBytes,
                        info.headers().firstValueAsLong("Content-Length").orElse(-1L)));
            } catch (RuntimeException failure) {
                return retryOrFail(request, decoder, exposeErrorResponse,
                        maximumRetries, attempt, failure);
            }
            return response.handle((value, failure) -> new Attempt(value, failure))
                    .thenCompose(outcome -> {
                        Throwable failure = unwrap(outcome.failure());
                        if (failure != null) {
                            return retryOrFail(request, decoder, exposeErrorResponse,
                                    maximumRetries, attempt, failure);
                        }
                        int status = outcome.response().statusCode();
                        if (transientStatus(status) && attempt < maximumRetries) {
                            return delayedRetry(request, decoder, exposeErrorResponse,
                                    maximumRetries, attempt + 1);
                        }
                        if (!exposeErrorResponse && (status < 200 || status >= 300)) {
                            return CompletableFuture.failedFuture(
                                    new HttpClientException("Outbound HTTP response status " + status, status));
                        }
                        T decoded = decoder.decode(outcome.response().body());
                        return CompletableFuture.completedFuture(new HttpClientResponse<>(
                                status, outcome.response().headers(), decoded));
                    });
        }

        private <T> CompletableFuture<HttpClientResponse<T>> retryOrFail(
                HttpRequest request,
                ResponseDecoder<T> decoder,
                boolean exposeErrorResponse,
                int maximumRetries,
                int attempt,
                Throwable failure) {
            if (attempt < maximumRetries && retryable(failure)) {
                return delayedRetry(request, decoder, exposeErrorResponse,
                        maximumRetries, attempt + 1);
            }
            Metrics.getInstance().increment("reactor_http_client_failures_total");
            HttpClientException clientFailure = find(failure, HttpClientException.class);
            if (clientFailure != null) return CompletableFuture.failedFuture(clientFailure);
            return CompletableFuture.failedFuture(new HttpClientException("Outbound HTTP request failed", failure));
        }

        private <T> CompletableFuture<HttpClientResponse<T>> delayedRetry(
                HttpRequest request,
                ResponseDecoder<T> decoder,
                boolean exposeErrorResponse,
                int maximumRetries,
                int nextAttempt) {
            Metrics.getInstance().increment("reactor_http_client_retries_total");
            long delay = owner.runtime.retryBackoffMs * Math.max(1, nextAttempt);
            if (delay == 0L) return attempt(
                    request, decoder, exposeErrorResponse, maximumRetries, nextAttempt);
            CompletableFuture<Void> waiting = new CompletableFuture<>();
            try {
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, owner.runtime.executor)
                        .execute(() -> waiting.complete(null));
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return waiting.thenCompose(ignored -> attempt(
                    request, decoder, exposeErrorResponse, maximumRetries, nextAttempt));
        }

        @SuppressWarnings("unchecked")
        private static <T> T decode(byte[] bytes, Class<T> responseType) {
            if (responseType == Void.class || responseType == void.class) return null;
            if (responseType == byte[].class) return (T) bytes;
            if (responseType == String.class) return (T) new String(bytes, StandardCharsets.UTF_8);
            try {
                return DslJsonService.parse(bytes, responseType);
            } catch (RuntimeException invalidBody) {
                throw new HttpClientException("Outbound HTTP response body is invalid", 502, invalidBody);
            }
        }

        private static <T> List<T> decodeList(byte[] bytes, Class<T> elementType) {
            try {
                return DslJsonService.parseList(bytes, elementType);
            } catch (RuntimeException invalidBody) {
                throw new HttpClientException("Outbound HTTP response body is invalid", 502, invalidBody);
            }
        }

        private static boolean transientStatus(int status) {
            return status == 502 || status == 503 || status == 504;
        }

        private static boolean retryable(Throwable failure) {
            if (find(failure, HttpClientException.class) != null) return false;
            Throwable current = failure;
            while (current != null) {
                if (current instanceof HttpTimeoutException
                        || current instanceof ConnectException
                        || current instanceof IOException) return true;
                current = current.getCause();
            }
            return false;
        }

        private static <T extends Throwable> T find(Throwable failure, Class<T> type) {
            Throwable current = failure;
            while (current != null) {
                if (type.isInstance(current)) return type.cast(current);
                current = current.getCause();
            }
            return null;
        }

        private static Throwable unwrap(Throwable failure) {
            if (failure instanceof java.util.concurrent.CompletionException completion
                    && completion.getCause() != null) return completion.getCause();
            return failure;
        }

        private static String normalizePath(String path) {
            if (path == null || path.isBlank()) return "/";
            return path.startsWith("/") ? path : "/" + path;
        }

        private static String encode(Object value) {
            return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8).replace("+", "%20");
        }

        private static byte[] encodeBody(Object value) {
            if (value == null) return null;
            if (value instanceof byte[] raw) return raw;
            if (value instanceof String text) return text.getBytes(StandardCharsets.UTF_8);
            return DslJsonService.toBytes(value);
        }

        private record Attempt(HttpResponse<byte[]> response, Throwable failure) {}

        @FunctionalInterface
        private interface ResponseDecoder<T> {
            T decode(byte[] bytes);
        }
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final int maximum;
        private final boolean rejected;
        private Flow.Subscription subscription;
        private byte[] bytes;
        private int size;

        private BoundedBodySubscriber(int maximum, long declaredLength) {
            this.maximum = maximum;
            this.rejected = declaredLength > maximum;
            int initial = declaredLength >= 0L && declaredLength <= maximum
                    ? (int) declaredLength
                    : Math.min(512, maximum);
            this.bytes = new byte[Math.max(0, initial)];
            if (rejected) {
                body.completeExceptionally(new HttpClientException(
                        "Outbound HTTP response exceeds " + maximum + " bytes", 502));
            }
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            if (rejected) {
                subscription.cancel();
                return;
            }
            subscription.request(1L);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                int incoming = 0;
                for (ByteBuffer buffer : buffers) incoming = Math.addExact(incoming, buffer.remaining());
                int required = Math.addExact(size, incoming);
                if (required > maximum) {
                    subscription.cancel();
                    body.completeExceptionally(new HttpClientException(
                            "Outbound HTTP response exceeds " + maximum + " bytes", 502));
                    return;
                }
                ensureCapacity(required);
                for (ByteBuffer buffer : buffers) {
                    int length = buffer.remaining();
                    buffer.get(bytes, size, length);
                    size += length;
                }
                subscription.request(1L);
            } catch (Throwable failure) {
                subscription.cancel();
                body.completeExceptionally(failure);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(size == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, size));
        }

        private void ensureCapacity(int required) {
            if (required <= bytes.length) return;
            long doubled = Math.max(1L, (long) bytes.length * 2L);
            int capacity = (int) Math.min(maximum, Math.max(required, Math.min(maximum, doubled)));
            bytes = java.util.Arrays.copyOf(bytes, capacity);
        }
    }

    private static final class ClientThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "reactor-http-client-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}

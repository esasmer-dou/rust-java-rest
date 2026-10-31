package com.reactor.rust.http.client;

import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.metrics.Metrics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

/** HTTP(S), bounded pull-completion bridge. No Java callback exists on the Rust I/O path. */
final class NativeHttpTransport implements HttpExchangeTransport {
    private static final Semaphore CLIENTS = new Semaphore(8);
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final byte[] EMPTY = new byte[0];

    private static final int CLOSING = 1 << 31;
    private static final int COUNT_MASK = Integer.MAX_VALUE;
    private static final int SLOT_BITS = 12;
    private static final int SLOT_MASK = (1 << SLOT_BITS) - 1;
    private static final int CALL_CANCELLED = 1;
    private static final int CALL_FINISHED = 2;
    private static final int CALL_RECORDED = 4;
    private final int threads = explicitSetting("threads", 1, 1, 16);
    private final int completionThreads = setting("native.completion-threads", 1, 1, 8);
    private final int maxInflight = explicitSetting("max-inflight", 8, 1, 4096);
    private final int maxConnections = setting("native.max-connections", 4, 1, 1024);
    private final int maxOrigins = setting("native.max-origins", 8, 1, 256);
    private final int maxIdle = setting("native.max-idle-per-origin", 1, 1, 32);
    private final int maxRequest = explicitSetting("max-request-bytes", 16 * 1024, 1, 64 * 1024 * 1024);
    private final int maxResponse = explicitSetting("max-response-bytes", 64 * 1024, 1, 64 * 1024 * 1024);
    private final int maxHeaders = explicitSetting("max-headers", 16, 1, 256);
    private final int maxHeaderBytes = setting("native.max-header-bytes", 8192, 8192, 1024 * 1024);
    private final long byteBudget = duration("native.byte-budget-bytes", 1024 * 1024, 3L * 1024 * 1024);
    private final long connectTimeout = duration("connect-timeout-ms", 1000, 3_600_000);
    private final long idleTimeout = duration("native.idle-timeout-ms", 30_000, 3_600_000);
    private final long shutdownTimeout = duration("shutdown-timeout-ms", 5000, 3_600_000);
    // Conservative reservation covers Java + JNI copies, body growth, and encoded completion.
    private final long callBytes = 4L * (maxRequest + (long) maxResponse + 4L * maxHeaderBytes + 8192);
    private final AtomicReferenceArray<Call<?, ?>> slots = new AtomicReferenceArray<>(maxInflight);
    private final AtomicInteger admissions = new AtomicInteger();
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicLong reservedBytes = new AtomicLong();
    private final AtomicLong generations = new AtomicLong();
    private final ArrayList<Thread> workers = new ArrayList<>(completionThreads);
    private final AtomicInteger runningWorkers = new AtomicInteger();
    private final AtomicInteger parkedWorkers = new AtomicInteger();
    private final AtomicLong idleParks = new AtomicLong();
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile long handle;
    private boolean clientPermit;

    int maxRequestBytes() { return maxRequest; }
    int maxResponseBytes() { return maxResponse; }
    int maxHeaderCount() { return maxHeaders; }
    long idleParkCount() { return idleParks.get(); }

    private boolean closed() { return admissions.get() < 0; }

    private boolean enter() {
        int state;
        do {
            state = admissions.get();
            if (state < 0 || state >= maxInflight) return false;
        } while (!admissions.compareAndSet(state, state + 1));
        long used;
        do {
            used = reservedBytes.get();
            if (used > byteBudget - callBytes) {
                admissions.decrementAndGet();
                return false;
            }
        } while (!reservedBytes.compareAndSet(used, used + callBytes));
        return true;
    }

    void start() {
        if (callBytes > byteBudget) {
            throw new IllegalArgumentException("reactor.http-client.native.byte-budget-bytes must cover one "
                    + "worst-case call reservation of " + callBytes + " bytes");
        }
        NativeOutboundHttp.loadAndProbe();
        if (!CLIENTS.tryAcquire()) throw new IllegalStateException("At most 8 native outbound runtimes may be live");
        clientPermit = true;
        try {
            handle = NativeOutboundHttp.create(threads, maxInflight, maxConnections, maxOrigins, maxIdle,
                    maxRequest, maxResponse, maxHeaders, maxHeaderBytes, byteBudget, connectTimeout, idleTimeout);
            if (handle == 0) throw new IllegalStateException("Native outbound runtime creation returned zero");
            NativeOutboundHttp.configureTls(handle,
                    PropertiesLoader.get("reactor.http-client.native.tls.ca-pem-path", ""));
            long sequence = SEQUENCE.incrementAndGet();
            for (int i = 0; i < completionThreads; i++) {
                int workerIndex = i;
                Thread worker = new Thread(null, () -> completeLoop(workerIndex),
                        "reactor-http-native-completion-" + sequence + "-" + i, 256L * 1024, false);
                worker.setDaemon(true);
                runningWorkers.incrementAndGet();
                try {
                    worker.start();
                    workers.add(worker);
                } catch (RuntimeException | Error failure) {
                    runningWorkers.decrementAndGet();
                    throw failure;
                }
            }
        } catch (RuntimeException | Error failure) {
            close();
            if (runningWorkers.get() == 0) releaseRuntime();
            if (failure instanceof UnsatisfiedLinkError) {
                throw new IllegalStateException("rust-native outbound HTTP requires --features outbound-http "
                        + "and API 1 in rust.lib.path; no JDK fallback", failure);
            }
            throw failure;
        }
    }

    @Override
    public void register(URI baseUri) {
        if (!("http".equalsIgnoreCase(baseUri.getScheme()) || "https".equalsIgnoreCase(baseUri.getScheme()))) {
            throw new IllegalArgumentException("Native outbound base URL must use http or https");
        }
        if (baseUri.getRawUserInfo() != null) throw new IllegalArgumentException("Outbound base URL must not contain userinfo");
        if (closed()) throw new IllegalStateException("Native outbound runtime is closed");
        NativeOutboundHttp.register(handle, origin(baseUri));
    }

    private static String origin(URI uri) {
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        return scheme + "://" + host + ":" + (uri.getPort() < 0 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort());
    }

    @Override
    public void contributeHeaders(HttpRequest.Builder builder, List<OutboundHeaderProvider> providers) {
        var headers = builder.build().headers().map();
        int[] totals = new int[2];
        headers.forEach((name, values) -> values.forEach(value -> {
            totals[0]++;
            totals[1] += headerBytes(name, value);
        }));
        java.util.function.BiConsumer<String, String> bounded = (name, value) -> {
            int bytes = headerBytes(name, value);
            if (++totals[0] > maxHeaders || totals[1] > maxHeaderBytes - bytes) {
                throw new HttpClientException("Outbound native header count/bytes exceeded", 413);
            }
            totals[1] += bytes;
            builder.header(name, value);
        };
        for (OutboundHeaderProvider provider : providers) provider.contribute(bounded);
    }

    private int headerBytes(String name, String value) {
        if (name.length() > maxHeaderBytes || value.length() > maxHeaderBytes) {
            throw new HttpClientException("Outbound native header bytes exceeded", 413);
        }
        return name.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length + 4;
    }

    @Override
    public <T> CompletableFuture<HttpClientResponse<T>> execute(ReactorHttpClientRuntime.Request request,
            ReactorHttpClientRuntime.ResponseDecoder<T> decoder, boolean exposeErrors) {
        if (!enter()) {
            metric("reactor_http_client_rejected_total");
            if (closed() && (admissions.get() & COUNT_MASK) == 0) releaseRuntime();
            return CompletableFuture.failedFuture(new HttpClientException("Native outbound capacity is full or runtime is closed", 503));
        }
        return executeAdmitted(request, new Call<>(decoder, exposeErrors, HttpClientResponse::new));
    }

    <T> CompletableFuture<HttpClientResponse<T>> executeProtected(ReactorHttpClientRuntime.Request request,
            ReactorHttpClientRuntime.ResponseDecoder<T> decoder, boolean exposeErrors, CircuitBreakerState breaker, long lease) {
        if (!enter()) {
            metric("reactor_http_client_rejected_total");
            breaker.recordFailure(lease);
            if (closed() && (admissions.get() & COUNT_MASK) == 0) releaseRuntime();
            return CompletableFuture.failedFuture(new HttpClientException("Native outbound capacity is full or runtime is closed", 503));
        }
        return executeAdmitted(request, new CircuitCall<>(decoder, exposeErrors, HttpClientResponse::new, breaker, lease));
    }

    <T> CompletableFuture<T> executeProtectedBody(ReactorHttpClientRuntime.Request request,
            ReactorHttpClientRuntime.ResponseDecoder<T> decoder, CircuitBreakerState breaker, long lease) {
        if (!enter()) {
            metric("reactor_http_client_rejected_total");
            breaker.recordFailure(lease);
            if (closed() && (admissions.get() & COUNT_MASK) == 0) releaseRuntime();
            return CompletableFuture.failedFuture(new HttpClientException("Native outbound capacity is full or runtime is closed", 503));
        }
        return executeAdmitted(request, new CircuitCall<>(decoder, false, (status, headers, body) -> body, breaker, lease, false));
    }

    private <T, R> CompletableFuture<R> executeAdmitted(ReactorHttpClientRuntime.Request request, Call<T, R> call) {
        int start = Math.floorMod(cursor.getAndIncrement(), maxInflight);
        boolean installed = false;
        for (int offset = 0; offset < maxInflight; offset++) {
            int slot = (start + offset) % maxInflight;
            call.slot = slot;
            if (slots.compareAndSet(slot, null, call)) { installed = true; break; }
        }
        if (!installed) {
            call.slot = -1;
            call.finish(null, new HttpClientException("Native outbound slot capacity is full", 503));
            return call.result;
        }
        try {
            call.retries = request.nativeRetries();
            call.backoff = request.nativeRetryBackoffMs();
            RequestMetadata fixed = request.nativeMetadata();
            if (fixed != null) {
                call.body = EMPTY;
                call.request = fixed.request;
                call.uri = fixed.uri;
                call.headers = fixed.headers;
            } else {
                call.body = request.nativeBody();
                call.request = request.build(call.body);
                if (call.body == null) call.body = EMPTY;
                call.uri = checkedUri(call.request);
                call.headers = flattenedHeaders(call.request);
            }
            call.prepared = true;
            submit(call, 0);
        } catch (RuntimeException | Error failure) {
            call.finish(null, failure);
        }
        return call.result;
    }

    // Private immutable metadata; JNI reads, but never mutates, the shared header array.
    static final class RequestMetadata {
        final HttpRequest request;
        final String uri;
        final String[] headers;
        private final String path;

        RequestMetadata(HttpRequest request, String uri, String[] headers, String path) {
            this.request = request;
            this.uri = uri;
            this.headers = headers;
            this.path = path;
        }

        String path() { return path; }
    }

    RequestMetadata prepare(HttpRequest request, String path) {
        return new RequestMetadata(request, checkedUri(request), flattenedHeaders(request), path);
    }

    private String checkedUri(HttpRequest request) {
        String uri = request.uri().toASCIIString();
        if (uri.getBytes(StandardCharsets.UTF_8).length > maxHeaderBytes) {
            throw new HttpClientException("Outbound URI exceeds native header byte limit", 413);
        }
        return uri;
    }

    private String[] flattenedHeaders(HttpRequest request) {
        List<String> headers = new ArrayList<>();
        int bytes = 0;
        for (var entry : request.headers().map().entrySet()) {
            for (String value : entry.getValue()) {
                headers.add(entry.getKey());
                headers.add(value);
                bytes = Math.addExact(bytes, entry.getKey().getBytes(StandardCharsets.UTF_8).length
                        + value.getBytes(StandardCharsets.UTF_8).length + 4);
            }
        }
        if (headers.size() / 2 > maxHeaders || bytes > maxHeaderBytes) {
            throw new HttpClientException("Outbound native header count/bytes exceeded", 413);
        }
        return headers.toArray(String[]::new);
    }

    private void submit(Call<?, ?> call, long delay) {
        if (closed()) { call.finish(null, new HttpClientException("Native outbound runtime is closed", 503)); return; }
        if (call.cancelled()) { call.finish(null, new CancellationException()); return; }
        long generation = generations.incrementAndGet();
        if (generation <= 0 || generation > Long.MAX_VALUE >>> SLOT_BITS) {
            throw new IllegalStateException("Native outbound generation space exhausted; restart runtime");
        }
        long id = (generation << SLOT_BITS) | call.slot;
        call.activeId.set(id);
        try {
            metric("reactor_http_client_requests_total");
            NativeOutboundHttp.send(handle, id, call.request.method(), call.uri, call.headers, call.body,
                    call.request.timeout().orElseThrow().toMillis(), delay);
            wakeCompletions();
            // Cancellation before native registration is harmless: recheck after registration.
            if (closed() || call.cancelled()) NativeOutboundHttp.cancel(handle, id);
        } catch (RuntimeException | Error failure) {
            call.activeId.compareAndSet(id, 0);
            if (failure instanceof NativeHttpAdmissionException) metric("reactor_http_client_rejected_total");
            throw failure;
        }
    }

    private void wakeCompletions() {
        int parked = parkedWorkers.get();
        if (parked == 0) return;
        for (int i = 0; i < workers.size(); i++) {
            if ((parked & (1 << i)) != 0) LockSupport.unpark(workers.get(i));
        }
    }

    private void awaitWork(int bit) {
        int before;
        do { before = parkedWorkers.get(); } while (!parkedWorkers.compareAndSet(before, before | bit));
        try {
            // Publication precedes the recheck: submit/close cannot lose an unpark before park.
            if (admissions.get() == 0) {
                idleParks.incrementAndGet();
                LockSupport.park(this);
            }
            if (Thread.interrupted()) throw new IllegalStateException("Native HTTP completion worker interrupted");
        } finally {
            do { before = parkedWorkers.get(); } while (!parkedWorkers.compareAndSet(before, before & ~bit));
        }
    }

    private void completeLoop(int workerIndex) {
        Object[] metadata = null;
        try {
            metadata = new Object[1];
            boolean recentCompletion = false;
            while (true) {
                int state = admissions.get();
                if (state < 0 && (state & COUNT_MASK) == 0) break;
                if (state == 0 && !recentCompletion) { awaitWork(1 << workerIndex); continue; }
                // One interruptible native wait absorbs short gaps after activity.
                // Replies wake it immediately; timeout leads to indefinite idle park.
                recentCompletion = false;
                for (int slot = 0; slot < slots.length(); slot++) {
                    Call<?, ?> call = slots.get(slot);
                    if (call != null && call.cancelled()) {
                        long id = call.activeId.get();
                        if (id != 0) NativeOutboundHttp.cancel(handle, id);
                    }
                }
                byte[] body = NativeOutboundHttp.pollBody(handle, metadata, 50);
                if (body == null) continue;
                recentCompletion = true;
                Object header = metadata[0];
                metadata[0] = null;
                if (!(header instanceof byte[] event)) throw new IllegalStateException("Missing native completion metadata");
                ByteBuffer bytes = ByteBuffer.wrap(event);
                long id = bytes.getLong();
                int slot = (int) (id & SLOT_MASK);
                if (slot >= slots.length()) throw new IllegalStateException("Invalid native completion slot");
                Call<?, ?> call = slots.get(slot);
                // A late generation can never complete or cancel the next occupant of a slot.
                if (call == null || !call.activeId.compareAndSet(id, 0)) continue;
                try {
                    call.accept(bytes, body);
                } catch (RuntimeException | Error failure) {
                    call.finish(null, failure);
                }
            }
        } catch (RuntimeException | Error failure) {
            admissions.getAndUpdate(state -> state | CLOSING);
            NativeOutboundHttp.stop(handle);
            wakeCompletions();
            // Failure completion still runs on this dedicated worker, never on Tokio.
            for (int slot = 0; slot < slots.length(); slot++) {
                Call<?, ?> call = slots.get(slot);
                if (call != null) {
                    call.mark(CALL_CANCELLED);
                    if (call.prepared) call.finish(null, failure);
                }
            }
        } finally {
            if (metadata != null) metadata[0] = null;
            if (runningWorkers.decrementAndGet() == 0) releaseRuntime();
        }
    }

    @FunctionalInterface
    private interface ResultProjection<T, R> {
        R create(int status, HttpHeaders headers, T body);
    }

    private class Call<T, R> extends AtomicInteger {
        private static final long serialVersionUID = 1L;
        final ReactorHttpClientRuntime.ResponseDecoder<T> decoder;
        final boolean exposeErrors;
        final ResultProjection<T, R> projection;
        final boolean materializeHeaders;
        final ExchangeFuture<R> result = new ExchangeFuture<>(this::cancel);
        HttpRequest request;
        String uri;
        String[] headers;
        byte[] body;
        int retries;
        int attempt;
        long backoff;
        final AtomicLong activeId = new AtomicLong();
        int slot = -1;
        volatile boolean prepared;

        Call(ReactorHttpClientRuntime.ResponseDecoder<T> decoder, boolean exposeErrors, ResultProjection<T, R> projection) {
            this(decoder, exposeErrors, projection, true);
        }

        Call(ReactorHttpClientRuntime.ResponseDecoder<T> decoder, boolean exposeErrors,
                ResultProjection<T, R> projection, boolean materializeHeaders) {
            this.decoder = decoder;
            this.exposeErrors = exposeErrors;
            this.projection = projection;
            this.materializeHeaders = materializeHeaders;
        }

        // Separate outcome ownership from resource release without allocating flag objects.
        final boolean mark(int bit) {
            int before;
            do {
                before = get();
                if ((before & bit) != 0) return false;
            } while (!compareAndSet(before, before | bit));
            return true;
        }

        final boolean cancelled() { return (get() & CALL_CANCELLED) != 0; }

        boolean cancel() {
            mark(CALL_CANCELLED);
            long id = activeId.get();
            if (id != 0) NativeOutboundHttp.cancel(handle, id);
            return true;
        }

        void accept(ByteBuffer event, byte[] body) {
            int error = event.getInt();
            int status = event.getInt();
            Throwable failure = switch (error) {
                case 0 -> null;
                case 1 -> new IOException("Native outbound HTTP I/O failed");
                case 2 -> new HttpTimeoutException("Native outbound HTTP deadline exceeded");
                case 3 -> new HttpClientException("Native outbound response header/body limit exceeded", 502);
                case 4 -> new HttpClientException("Native outbound connection or byte capacity is full", 503);
                case 5 -> new CancellationException("Native outbound request cancelled");
                default -> new HttpClientException("Native outbound runtime stopped", 503);
            };
            if (cancelled()) { finish(null, new CancellationException()); return; }
            if (!closed() && attempt < retries && (failure == null
                    ? ReactorHttpClientRuntime.Request.transientStatus(status)
                    : ReactorHttpClientRuntime.Request.retryable(failure))) {
                attempt++;
                metric("reactor_http_client_retries_total");
                submit(this, Math.multiplyExact(backoff, attempt));
                return;
            }
            if (failure != null) { finish(null, failure); return; }
            if (!exposeErrors && (status < 200 || status >= 300)) {
                finish(null, new HttpClientException.ResponseStatus(status));
                return;
            }
            int count = event.getInt();
            if (count < 0 || count > maxHeaders) throw new IllegalStateException("Invalid native header count");
            var headers = materializeHeaders ? new HashMap<String, List<String>>() : null;
            for (int i = 0; i < count; i++) {
                if (headers == null) {
                    // Rust validated HTTP names/values and count/byte limits. Keep
                    // validating the JNI frame without materializing unused headers.
                    skipHeaderField(event);
                    skipHeaderField(event);
                } else {
                    String name = text(event, StandardCharsets.US_ASCII);
                    String value = text(event, StandardCharsets.ISO_8859_1);
                    headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
                }
            }
            int length = event.getInt();
            if (length < 0 || length > maxResponse || length != body.length || event.hasRemaining()) {
                throw new IllegalStateException("Invalid native response body size");
            }
            T value = decoder.decode(body);
            finish(status, projection.create(status,
                    headers == null ? null : HttpHeaders.of(headers, (name, item) -> true), value), null);
        }

        void finish(R response, Throwable failure) { finish(0, response, failure); }

        void finish(int status, R response, Throwable failure) {
            int previous;
            int flags = finishFlags();
            do {
                previous = get();
                if ((previous & CALL_FINISHED) != 0) return;
            } while (!compareAndSet(previous, previous | flags));
            try {
                completeResult(status, response, failure, previous);
            } finally {
                result.cancellation.set(null);
                if (slot >= 0) slots.compareAndSet(slot, this, null);
                reservedBytes.addAndGet(-callBytes);
                admissions.decrementAndGet();
                if (closed() && runningWorkers.get() == 0) releaseRuntime();
            }
        }

        int finishFlags() { return CALL_FINISHED; }

        void completeResult(int status, R response, Throwable failure, int previous) {
            if (failure == null) result.complete(response);
            else {
                metric(failure instanceof CancellationException
                        ? "reactor_http_client_cancelled_total" : "reactor_http_client_failures_total");
                if (failure instanceof IOException) {
                    result.completeExceptionally(new HttpClientException("Outbound HTTP request failed", failure));
                } else result.completeExceptionally(failure);
            }
        }
    }

    /** Reuse the native call's completion boundary; do not allocate another future/continuation. */
    private final class CircuitCall<T, R> extends Call<T, R> {
        private static final long serialVersionUID = 1L;
        private final CircuitBreakerState breaker;
        private final long lease;

        CircuitCall(ReactorHttpClientRuntime.ResponseDecoder<T> decoder, boolean exposeErrors,
                ResultProjection<T, R> projection, CircuitBreakerState breaker, long lease) {
            this(decoder, exposeErrors, projection, breaker, lease, true);
        }

        CircuitCall(ReactorHttpClientRuntime.ResponseDecoder<T> decoder, boolean exposeErrors,
                ResultProjection<T, R> projection, CircuitBreakerState breaker, long lease, boolean materializeHeaders) {
            super(decoder, exposeErrors, projection, materializeHeaders);
            this.breaker = breaker;
            this.lease = lease;
        }

        @Override boolean cancel() {
            if (!mark(CALL_RECORDED)) return false;
            breaker.recordFailure(lease);
            return super.cancel();
        }

        @Override int finishFlags() { return CALL_FINISHED | CALL_RECORDED; }

        @Override void completeResult(int responseStatus, R response, Throwable failure, int previous) {
            // Completion claims both flags in one CAS. A cancellation that already
            // recorded its outcome still wins; generation/probe recording stays once-only.
            if ((previous & CALL_RECORDED) != 0) {
                metric("reactor_http_client_cancelled_total");
                result.cancelFromTransport();
                return;
            }
            boolean failed = failure != null;
            if (failure instanceof HttpClientException.ResponseStatus status) {
                failed = breaker.record5xxAsFailure() && status.status() >= 500 && status.status() <= 599;
            } else if (failure == null) {
                failed = breaker.record5xxAsFailure() && responseStatus >= 500 && responseStatus <= 599;
            }
            if (failed) breaker.recordFailure(lease); else breaker.recordSuccess(lease);
            super.completeResult(responseStatus, response, failure, previous);
        }
    }

    static void skipHeaderField(ByteBuffer bytes) {
        int length = bytes.getInt();
        if (length < 0 || length > bytes.remaining()) throw new IllegalStateException("Invalid native header size");
        bytes.position(bytes.position() + length);
    }

    private static String text(ByteBuffer bytes, java.nio.charset.Charset charset) {
        int length = bytes.getInt();
        if (length < 0 || length > bytes.remaining()) throw new IllegalStateException("Invalid native header size");
        String value = new String(bytes.array(), bytes.position(), length, charset);
        bytes.position(bytes.position() + length);
        return value;
    }

    private static final class ExchangeFuture<T> extends CompletableFuture<T> implements CancellableHttpExchange {
        private final AtomicReference<java.util.function.BooleanSupplier> cancellation;

        ExchangeFuture(java.util.function.BooleanSupplier cancellation) { this.cancellation = new AtomicReference<>(cancellation); }

        private ExchangeFuture(AtomicReference<java.util.function.BooleanSupplier> cancellation) { this.cancellation = cancellation; }

        @Override
        public <U> CompletableFuture<U> newIncompleteFuture() { return new ExchangeFuture<>(cancellation); }

        @Override
        public boolean cancel(boolean interrupt) {
            if (isDone()) return false;
            java.util.function.BooleanSupplier action = cancellation.get();
            if (action != null && !action.getAsBoolean()) return false;
            return super.cancel(interrupt);
        }

        void cancelFromTransport() { super.cancel(false); }
    }

    @Override
    public void close() {
        admissions.getAndUpdate(state -> state | CLOSING);
        if (handle != 0) NativeOutboundHttp.stop(handle);
        wakeCompletions();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownTimeout);
        for (Thread worker : workers) {
            if (worker == Thread.currentThread()) continue;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // A blocked user continuation retains this client slot until its worker really exits.
        if (runningWorkers.get() == 0) releaseRuntime();
    }

    private void releaseRuntime() {
        if ((admissions.get() & COUNT_MASK) != 0 || runningWorkers.get() != 0) return;
        if (!released.compareAndSet(false, true)) return;
        if (handle != 0) NativeOutboundHttp.destroy(handle);
        if (clientPermit) CLIENTS.release();
    }

    private static void metric(String name) { Metrics.getInstance().increment(name); }

    private static int setting(String suffix, int fallback, int min, int max) {
        int value = PropertiesLoader.getInt("reactor.http-client." + suffix, fallback);
        if (value < min || value > max) throw new IllegalArgumentException("reactor.http-client." + suffix
                + " must be between " + min + " and " + max);
        return value;
    }

    private static int explicitSetting(String suffix, int fallback, int min, int max) {
        String key = "reactor.http-client." + suffix;
        String source = PropertiesLoader.effective(java.util.Set.of(key), java.util.Set.of(key)).getFirst().source();
        return switch (source) {
            case "system-property", "environment", "application-config" -> setting(suffix, fallback, min, max);
            default -> fallback;
        };
    }

    private static long duration(String suffix, long fallback, long max) {
        long value = PropertiesLoader.getLong("reactor.http-client." + suffix, fallback);
        if (value <= 0 || value > max) throw new IllegalArgumentException("reactor.http-client." + suffix
                + " must be between 1 and " + max);
        return value;
    }
}

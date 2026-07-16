package com.reactor.rust.bridge;

import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.exception.HttpErrorMapper;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.websocket.WebSocketRegistry;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JNI bridge between Rust HTTP server and Java handlers.
 * Single entry point - no version variants.
 *
 * Native library loading order:
 * 1. System property: -Drust.lib.path=/path/to/library
 * 2. java.library.path: System.loadLibrary("rust_hyper")
 * 3. JAR resources: native/{platform}/rust_hyper.{ext}
 */
public class NativeBridge {

    static final int EXPECTED_NATIVE_ABI_VERSION = 24;
    static final int EXPECTED_DUBBO_NATIVE_ABI_VERSION = 7;
    static final int EXPECTED_REDIS_NATIVE_ABI_VERSION = 6;
    private static final long DEFAULT_MAX_REQUEST_BODY_BYTES = 1024L * 1024L;
    private static final long DEFAULT_MAX_RESPONSE_BODY_BYTES = 8L * 1024L * 1024L;
    private static final long DEFAULT_MAX_IN_FLIGHT_BODY_BYTES = 64L * 1024L * 1024L;
    private static final long DEFAULT_MAX_IN_FLIGHT_RESPONSE_BYTES = 128L * 1024L * 1024L;
    private static final int DEFAULT_MAX_CONNECTIONS = 2048;
    private static final int DEFAULT_JNI_WORKERS = 0;
    private static final int DEFAULT_JNI_QUEUE_CAPACITY = 1024;
    private static final int DEFAULT_RESPONSE_POOL_CAPACITY = 64;
    private static final long DEFAULT_MAX_WEBSOCKET_FRAME_BYTES = 1024L * 1024L;
    private static final int DEFAULT_WEBSOCKET_OUTBOUND_QUEUE_CAPACITY = 1024;
    private static final int DEFAULT_WEBSOCKET_SEND_TIMEOUT_MS = 5000;
    private static final long DEFAULT_MAX_REQUEST_HEADER_BYTES = 16L * 1024L;
    private static final int DEFAULT_MAX_REQUEST_HEADERS = 64;
    private static final int DEFAULT_HEADER_READ_TIMEOUT_MS = 5000;
    private static final int DEFAULT_REQUEST_BODY_TIMEOUT_MS = 10000;
    private static final int DEFAULT_IDLE_TIMEOUT_MS = 30000;
    private static final boolean DEFAULT_KEEP_ALIVE_ENABLED = true;
    private static final int DEFAULT_NATIVE_LOG_LEVEL = 1;
    private static final int DEFAULT_RUNTIME_WORKER_THREADS = 0;
    private static final int DEFAULT_RUNTIME_MAX_BLOCKING_THREADS = 0;
    private static final long DEFAULT_RUNTIME_THREAD_STACK_BYTES = 0L;
    private static final long DEFAULT_JNI_THREAD_STACK_BYTES = 0L;
    private static final long DEFAULT_SERVER_THREAD_STACK_BYTES = 0L;
    private static final boolean DEFAULT_HTTP1_ONLY_ENABLED = false;
    private static final int DEFAULT_FILE_STREAM_CHUNK_BYTES = 64 * 1024;
    private static final long DEFAULT_STATIC_FILE_INLINE_MAX_BYTES = 512L * 1024L;
    private static final int DEFAULT_STATIC_FILE_MAX_CONCURRENT_STREAMS = 128;
    private static final int DEFAULT_NATIVE_CACHE_MAX_ENTRIES = 1024;
    private static final long DEFAULT_NATIVE_CACHE_MAX_BYTES = 16L * 1024L * 1024L;
    private static final long DEFAULT_NATIVE_CACHE_TTL_MS = 300_000L;
    private static final int DEFAULT_ASYNC_RESPONSE_TIMEOUT_MS = 2_000;
    private static final int DEFAULT_SERVER_STARTUP_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT_MS = 30_000;
    public static final int WS_SEND_OK = 1;
    public static final int WS_SEND_NOT_FOUND = 0;
    public static final int WS_SEND_QUEUE_FULL = -1;
    public static final int WS_SEND_TOO_LARGE = -2;
    public static final int WS_SEND_INVALID = -3;

    private static final byte[] RESPONSE_FRAME_MAGIC =
            new byte[] {'R', 'J', 'R', 'S', 'P', 'V', '1', '!'};
    private static final int RESPONSE_FRAME_HEADER_SIZE = 18;
    private static final byte[] EMPTY_REQUEST_BODY = new byte[0];
    private static volatile int asyncResponseTimeoutMs = DEFAULT_ASYNC_RESPONSE_TIMEOUT_MS;

    static {
        NativeLibraryLoader.load();
    }

    public static native void releaseNativeMemory();

    public static native void releaseNativeMemoryRetaining(
            int retainSmall,
            int retainMedium,
            int retainLarge,
            int retainHuge,
            boolean trimAllocator
    );

    public static native int nativeAbiVersion();

    public static native String nativeBuildInfo();

    public static String nativeArtifactInfo() {
        return NativeLibraryLoader.loadedArtifactInfo();
    }

    public static native String nativeMetricsPrometheus();

    public static native String nativeMemoryDiagnosticsJson();

    public static native void nativeResetMetrics();

    public static native void completeAsyncResponse(long requestId, byte[] responseFrame);

    public static native void completeAsyncResponseBuffer(long requestId, ByteBuffer responseFrame, int length);

    public static native int registerStaticResponse(byte[] body, String encodedHeaders, int statusCode);

    public static native int registerStaticFileResponse(
            String path,
            String encodedHeaders,
            int statusCode,
            long inlineMaxBytes
    );

    public static native void configureNativeResponseCache(int maxEntries, long maxBytes, long defaultTtlMs);

    public static native void configureFileStreaming(int chunkBytes);

    public static native void configureStaticFileStreaming(int maxConcurrentStreams);

    public static native int lookupDynamicResponse(String key);

    public static native int registerDynamicResponse(
            String key,
            byte[] body,
            String encodedHeaders,
            int statusCode,
            long ttlMs
    );

    public static native int writeHeavyJsonRust(ByteBuffer outBuffer, int offset, int itemCount, long timestamp);

    // ======================
    // WEBSOCKET NATIVE METHODS
    // ======================

    /**
     * Register a WebSocket route with Rust.
     * @param path WebSocket path (e.g., "/ws/echo")
     * @param handlerId Handler ID for routing
     */
    public static native void registerWebSocketRoute(String path, int handlerId);

    public static native int sendWebSocketText(long sessionId, String message);

    public static native int sendWebSocketBinary(long sessionId, byte[] data, int len);

    public static native int sendWebSocketBinaryBuffer(
            long sessionId,
            ByteBuffer data,
            int offset,
            int len
    );

    public static native int closeWebSocket(long sessionId);

    public static native int closeWebSocketWithReason(long sessionId, int code, String reason);

    // ======================
    // RUST → JAVA register
    // ======================
    public static native void passNativeBridgeClass(Class<?> clazz);

    // ======================
    // JAVA → RUST
    // ======================
    public static native void configureRuntime(
            long maxRequestBodyBytes,
            long maxResponseBodyBytes,
            long maxInFlightBodyBytes,
            long maxInFlightResponseBytes,
            int maxConnections,
            int jniWorkers,
            int jniQueueCapacity,
            int responsePoolSmallCapacity,
            int responsePoolMediumCapacity,
            int responsePoolLargeCapacity,
            int responsePoolHugeCapacity,
            long maxWebSocketFrameBytes,
            int webSocketOutboundQueueCapacity,
            int webSocketSendTimeoutMs,
            long maxRequestHeaderBytes,
            int maxRequestHeaders,
            int headerReadTimeoutMs,
            int requestBodyTimeoutMs,
            int idleTimeoutMs,
            int runtimeWorkerThreads,
            int runtimeMaxBlockingThreads,
            long runtimeThreadStackBytes,
            long jniThreadStackBytes,
            long serverThreadStackBytes,
            boolean http1OnlyEnabled,
            boolean keepAliveEnabled,
            int nativeLogLevel,
            int asyncResponseTimeoutMs
    );

    private static native boolean nativeStartHttpServer(
            int port,
            int startupTimeoutMs,
            int gracefulShutdownTimeoutMs);

    private static native boolean nativeStopHttpServer(int waitTimeoutMs);

    private static native int nativeHttpServerState();

    public static void startHttpServer(int port) {
        int startupTimeoutMs = positiveServerTimeout(
                "reactor.rust.server.startup-timeout-ms",
                DEFAULT_SERVER_STARTUP_TIMEOUT_MS
        );
        int gracefulShutdownTimeoutMs = positiveServerTimeout(
                "reactor.rust.server.graceful-shutdown-timeout-ms",
                DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT_MS
        );
        if (!nativeStartHttpServer(port, startupTimeoutMs, gracefulShutdownTimeoutMs)) {
            throw new IllegalStateException("Native Hyper server did not reach ready state");
        }
    }

    public static boolean stopHttpServer() {
        int gracefulShutdownTimeoutMs = positiveServerTimeout(
                "reactor.rust.server.graceful-shutdown-timeout-ms",
                DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT_MS
        );
        int waitTimeoutMs = Math.min(Integer.MAX_VALUE - 1_000, gracefulShutdownTimeoutMs + 1_000);
        return nativeStopHttpServer(waitTimeoutMs);
    }

    public static boolean isHttpServerReady() {
        return nativeHttpServerState() == 1;
    }

    public static boolean isHttpServerDraining() {
        return nativeHttpServerState() == 2;
    }

    public static native void registerRoutes(List<RouteDef> routes);

    public static long staticFileInlineMaxBytes() {
        return Math.max(0L, PropertiesLoader.getLong(
                "reactor.rust.static-file.inline-max-bytes",
                DEFAULT_STATIC_FILE_INLINE_MAX_BYTES
        ));
    }

    public static void configureRuntimeFromProperties() {
        long maxRequestBodyBytes = PropertiesLoader.getLong(
                "reactor.rust.http.max-request-body-bytes",
                DEFAULT_MAX_REQUEST_BODY_BYTES
        );
        long maxResponseBodyBytes = PropertiesLoader.getLong(
                "reactor.rust.http.max-response-body-bytes",
                DEFAULT_MAX_RESPONSE_BODY_BYTES
        );
        long maxInFlightBodyBytes = PropertiesLoader.getLong(
                "reactor.rust.http.max-inflight-body-bytes",
                DEFAULT_MAX_IN_FLIGHT_BODY_BYTES
        );
        long maxInFlightResponseBytes = PropertiesLoader.getLong(
                "reactor.rust.http.max-inflight-response-bytes",
                DEFAULT_MAX_IN_FLIGHT_RESPONSE_BYTES
        );
        int maxConnections = PropertiesLoader.getInt(
                "reactor.rust.http.max-connections",
                DEFAULT_MAX_CONNECTIONS
        );
        int jniWorkers = PropertiesLoader.getInt(
                "reactor.rust.jni.workers",
                DEFAULT_JNI_WORKERS
        );
        int jniQueueCapacity = PropertiesLoader.getInt(
                "reactor.rust.jni.queue-capacity",
                DEFAULT_JNI_QUEUE_CAPACITY
        );
        int responsePoolSmallCapacity = PropertiesLoader.getInt(
                "reactor.rust.response-pool.small-capacity",
                DEFAULT_RESPONSE_POOL_CAPACITY
        );
        int responsePoolMediumCapacity = PropertiesLoader.getInt(
                "reactor.rust.response-pool.medium-capacity",
                DEFAULT_RESPONSE_POOL_CAPACITY
        );
        int responsePoolLargeCapacity = PropertiesLoader.getInt(
                "reactor.rust.response-pool.large-capacity",
                DEFAULT_RESPONSE_POOL_CAPACITY
        );
        int responsePoolHugeCapacity = PropertiesLoader.getInt(
                "reactor.rust.response-pool.huge-capacity",
                DEFAULT_RESPONSE_POOL_CAPACITY
        );
        long maxWebSocketFrameBytes = PropertiesLoader.getLong(
                "reactor.rust.websocket.max-frame-bytes",
                DEFAULT_MAX_WEBSOCKET_FRAME_BYTES
        );
        int webSocketOutboundQueueCapacity = PropertiesLoader.getInt(
                "reactor.rust.websocket.outbound-queue-capacity",
                DEFAULT_WEBSOCKET_OUTBOUND_QUEUE_CAPACITY
        );
        int webSocketSendTimeoutMs = PropertiesLoader.getInt(
                "reactor.rust.websocket.send-timeout-ms",
                DEFAULT_WEBSOCKET_SEND_TIMEOUT_MS
        );
        long maxRequestHeaderBytes = PropertiesLoader.getLong(
                "reactor.rust.http.max-request-header-bytes",
                DEFAULT_MAX_REQUEST_HEADER_BYTES
        );
        int maxRequestHeaders = PropertiesLoader.getInt(
                "reactor.rust.http.max-request-headers",
                DEFAULT_MAX_REQUEST_HEADERS
        );
        int headerReadTimeoutMs = PropertiesLoader.getInt(
                "reactor.rust.http.header-read-timeout-ms",
                DEFAULT_HEADER_READ_TIMEOUT_MS
        );
        int requestBodyTimeoutMs = PropertiesLoader.getInt(
                "reactor.rust.http.request-body-timeout-ms",
                DEFAULT_REQUEST_BODY_TIMEOUT_MS
        );
        int idleTimeoutMs = PropertiesLoader.getInt(
                "reactor.rust.http.idle-timeout-ms",
                DEFAULT_IDLE_TIMEOUT_MS
        );
        int runtimeWorkerThreads = PropertiesLoader.getInt(
                "reactor.rust.runtime.worker-threads",
                DEFAULT_RUNTIME_WORKER_THREADS
        );
        int runtimeMaxBlockingThreads = PropertiesLoader.getInt(
                "reactor.rust.runtime.max-blocking-threads",
                DEFAULT_RUNTIME_MAX_BLOCKING_THREADS
        );
        long runtimeThreadStackBytes = PropertiesLoader.getLong(
                "reactor.rust.runtime.thread-stack-bytes",
                DEFAULT_RUNTIME_THREAD_STACK_BYTES
        );
        long jniThreadStackBytes = PropertiesLoader.getLong(
                "reactor.rust.jni.thread-stack-bytes",
                DEFAULT_JNI_THREAD_STACK_BYTES
        );
        long serverThreadStackBytes = PropertiesLoader.getLong(
                "reactor.rust.server.thread-stack-bytes",
                DEFAULT_SERVER_THREAD_STACK_BYTES
        );
        boolean http1OnlyEnabled = PropertiesLoader.getBoolean(
                "reactor.rust.http.http1-only-enabled",
                DEFAULT_HTTP1_ONLY_ENABLED
        );
        boolean keepAliveEnabled = PropertiesLoader.getBoolean(
                "reactor.rust.http.keep-alive-enabled",
                DEFAULT_KEEP_ALIVE_ENABLED
        );
        int fileStreamChunkBytes = PropertiesLoader.getInt(
                "reactor.rust.file-stream.chunk-bytes",
                DEFAULT_FILE_STREAM_CHUNK_BYTES
        );
        int staticFileMaxConcurrentStreams = PropertiesLoader.getInt(
                "reactor.rust.static-file.max-concurrent-streams",
                DEFAULT_STATIC_FILE_MAX_CONCURRENT_STREAMS
        );
        int nativeCacheMaxEntries = PropertiesLoader.getInt(
                "reactor.rust.native-cache.max-entries",
                DEFAULT_NATIVE_CACHE_MAX_ENTRIES
        );
        long nativeCacheMaxBytes = PropertiesLoader.getLong(
                "reactor.rust.native-cache.max-bytes",
                DEFAULT_NATIVE_CACHE_MAX_BYTES
        );
        long nativeCacheTtlMs = PropertiesLoader.getLong(
                "reactor.rust.native-cache.ttl-ms",
                DEFAULT_NATIVE_CACHE_TTL_MS
        );
        int nativeLogLevel = parseNativeLogLevel(PropertiesLoader.get(
                "reactor.rust.log.level",
                "error"
        ));
        asyncResponseTimeoutMs = Math.max(1, PropertiesLoader.getInt(
                "reactor.rust.async.response-timeout-ms",
                DEFAULT_ASYNC_RESPONSE_TIMEOUT_MS
        ));
        NativeBridge.asyncResponseTimeoutMs = asyncResponseTimeoutMs;

        try {
            int nativeAbi = nativeAbiVersion();
            if (nativeAbi != EXPECTED_NATIVE_ABI_VERSION) {
                throw new IllegalStateException(
                        "Native rust_hyper ABI mismatch: expected "
                                + EXPECTED_NATIVE_ABI_VERSION + " but loaded " + nativeAbi
                                + ". Rebuild rust-spring and update native resources."
                );
            }
            NativeProvenance.BuildInfo buildInfo = NativeLibraryLoader.validateRuntimeProvenance(
                    nativeBuildInfo(),
                    EXPECTED_NATIVE_ABI_VERSION
            );
            FrameworkLogger.info(
                    "[NativeBridge] Native build: revision=" + buildInfo.sourceRevision()
                            + " target=" + buildInfo.target()
                            + " profile=" + buildInfo.profile()
                            + " features=" + buildInfo.features()
                            + " artifact={" + nativeArtifactInfo() + "}"
            );
            configureRuntime(
                    maxRequestBodyBytes,
                    maxResponseBodyBytes,
                    maxInFlightBodyBytes,
                    maxInFlightResponseBytes,
                    maxConnections,
                    jniWorkers,
                    jniQueueCapacity,
                    responsePoolSmallCapacity,
                    responsePoolMediumCapacity,
                    responsePoolLargeCapacity,
                    responsePoolHugeCapacity,
                    maxWebSocketFrameBytes,
                    webSocketOutboundQueueCapacity,
                    webSocketSendTimeoutMs,
                    maxRequestHeaderBytes,
                    maxRequestHeaders,
                    headerReadTimeoutMs,
                    requestBodyTimeoutMs,
                    idleTimeoutMs,
                    runtimeWorkerThreads,
                    runtimeMaxBlockingThreads,
                    runtimeThreadStackBytes,
                    jniThreadStackBytes,
                    serverThreadStackBytes,
                    http1OnlyEnabled,
                    keepAliveEnabled,
                    nativeLogLevel,
                    asyncResponseTimeoutMs
            );
            configureFileStreaming(fileStreamChunkBytes);
            configureStaticFileStreaming(staticFileMaxConcurrentStreams);
            configureNativeResponseCache(nativeCacheMaxEntries, nativeCacheMaxBytes, nativeCacheTtlMs);
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException(
                    "Native rust_hyper library is missing the expected ABI; rebuild rust-spring and update native resources",
                    e
            );
        }

        FrameworkLogger.info("[JAVA] Native runtime configured: "
                + "maxRequestBodyBytes=" + maxRequestBodyBytes
                + ", maxResponseBodyBytes=" + maxResponseBodyBytes
                + ", maxInFlightBodyBytes=" + maxInFlightBodyBytes
                + ", maxInFlightResponseBytes=" + maxInFlightResponseBytes
                + ", maxConnections=" + maxConnections
                + ", jniWorkers=" + (jniWorkers > 0 ? jniWorkers : "auto")
                + ", jniQueueCapacity=" + jniQueueCapacity
                + ", jniThreadStackBytes=" + jniThreadStackBytes
                + ", serverThreadStackBytes=" + serverThreadStackBytes
                + ", responsePoolSmallCapacity=" + responsePoolSmallCapacity
                + ", responsePoolMediumCapacity=" + responsePoolMediumCapacity
                + ", responsePoolLargeCapacity=" + responsePoolLargeCapacity
                + ", responsePoolHugeCapacity=" + responsePoolHugeCapacity
                + ", maxWebSocketFrameBytes=" + maxWebSocketFrameBytes
                + ", webSocketOutboundQueueCapacity=" + webSocketOutboundQueueCapacity
                + ", webSocketSendTimeoutMs=" + webSocketSendTimeoutMs
                + ", maxRequestHeaderBytes=" + maxRequestHeaderBytes
                + ", maxRequestHeaders=" + maxRequestHeaders
                + ", headerReadTimeoutMs=" + headerReadTimeoutMs
                + ", requestBodyTimeoutMs=" + requestBodyTimeoutMs
                + ", idleTimeoutMs=" + idleTimeoutMs
                + ", runtimeWorkerThreads=" + (runtimeWorkerThreads > 0 ? runtimeWorkerThreads : "auto")
                + ", runtimeMaxBlockingThreads=" + (runtimeMaxBlockingThreads > 0 ? runtimeMaxBlockingThreads : "auto")
                + ", runtimeThreadStackBytes=" + runtimeThreadStackBytes
                + ", http1OnlyEnabled=" + http1OnlyEnabled
                + ", keepAliveEnabled=" + keepAliveEnabled
                + ", fileStreamChunkBytes=" + fileStreamChunkBytes
                + ", nativeCacheMaxEntries=" + nativeCacheMaxEntries
                + ", nativeCacheMaxBytes=" + nativeCacheMaxBytes
                + ", nativeCacheTtlMs=" + nativeCacheTtlMs
                + ", nativeLogLevel=" + nativeLogLevel
                + ", asyncResponseTimeoutMs=" + asyncResponseTimeoutMs);
    }

    public static boolean isDebugLoggingEnabled() {
        return Boolean.getBoolean("reactor.rust.java.debug") || FrameworkLogger.isDebugEnabled();
    }

    private static int parseNativeLogLevel(String level) {
        if (level == null || level.isBlank()) {
            return DEFAULT_NATIVE_LOG_LEVEL;
        }
        return switch (level.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "off", "none" -> 0;
            case "error" -> 1;
            case "warn", "warning" -> 2;
            case "info" -> 3;
            case "debug", "trace" -> 4;
            default -> throw new IllegalArgumentException(
                    "reactor.rust.log.level must be one of off, error, warn, info, debug"
            );
        };
    }

    private static int positiveServerTimeout(String key, int defaultValue) {
        int value = PropertiesLoader.getInt(key, defaultValue);
        if (value < 100 || value > 300_000) {
            throw new IllegalArgumentException(key + " must be between 100 and 300000 milliseconds");
        }
        return value;
    }

    /**
     * Single handler entry point from Rust.
     * Signature: (ByteBuffer, int offset, byte[] body, String pathParams, String queryString, String headers)
     */
    public static int handleRustRequestIntoBuffer(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) {
        return invokeHandler(handlerId, outBuffer, offset, capacity, inBytes, pathParams, queryString, headers);
    }

    public static int handleRustDirectRequestIntoBuffer(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            ByteBuffer inBuffer,
            int inLength,
            String pathParams,
            String queryString,
            String headers
    ) {
        return invokeDirectHandler(handlerId, outBuffer, offset, capacity, inBuffer, inLength, pathParams, queryString, headers);
    }

    public static int handleRustBodylessRequestIntoBuffer(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            String pathParams,
            String queryString,
            String headers
    ) {
        return invokeHandler(handlerId, outBuffer, offset, capacity, EMPTY_REQUEST_BODY, pathParams, queryString, headers);
    }

    public static int handleRustBodylessOutputRequestIntoBuffer(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity
    ) {
        return invokeBodylessOutputHandler(handlerId, outBuffer, offset, capacity);
    }

    public static int handleRustQueryIntRequestIntoBuffer(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            int queryInt
    ) {
        return invokeQueryIntHandler(handlerId, outBuffer, offset, capacity, queryInt);
    }

    public static int handleRustQueryLongRequestIntoBuffer(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            long queryLong
    ) {
        return invokeQueryLongHandler(handlerId, outBuffer, offset, capacity, queryLong);
    }

    public static int handleRustQueryBooleanRequestIntoBuffer(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            boolean queryBoolean
    ) {
        return invokeQueryBooleanHandler(handlerId, outBuffer, offset, capacity, queryBoolean);
    }

    public static int handleRustQueryDoubleRequestIntoBuffer(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            double queryDouble
    ) {
        return invokeQueryDoubleHandler(handlerId, outBuffer, offset, capacity, queryDouble);
    }

    public static int handleRustQueryShortRequestIntoBuffer(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            short queryShort
    ) {
        return invokeQueryShortHandler(handlerId, outBuffer, offset, capacity, queryShort);
    }

    public static boolean handleRustAsyncBodylessRequest(
            int handlerId,
            long requestId,
            String pathParams,
            String queryString,
            String headers
    ) {
        return startAsyncHandler(handlerId, requestId, EMPTY_REQUEST_BODY, pathParams, queryString, headers);
    }

    public static boolean handleRustAsyncRequest(
            int handlerId,
            long requestId,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) {
        return startAsyncHandler(handlerId, requestId, inBytes, pathParams, queryString, headers);
    }

    public static boolean handleRustAsyncQueryIntRequest(
            int handlerId,
            long requestId,
            int queryInt
    ) {
        return startAsyncQueryIntHandler(handlerId, requestId, queryInt);
    }

    private static boolean startAsyncHandler(
            int handlerId,
            long requestId,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) {
        try {
            HandlerRegistry.getInstance()
                    .invokeAsyncFrame(handlerId, inBytes, pathParams, queryString, headers)
                    .orTimeout(asyncResponseTimeoutMs, TimeUnit.MILLISECONDS)
                    .whenComplete((frame, error) -> completeAsyncHandler(requestId, frame, error));
            return true;
        } catch (Throwable e) {
            throwIfFatal(e);
            completeAsyncHandler(requestId, (HandlerRegistry.AsyncResponseFrame) null, e);
            return true;
        }
    }

    private static boolean startAsyncQueryIntHandler(
            int handlerId,
            long requestId,
            int queryInt
    ) {
        try {
            HandlerRegistry.getInstance()
                    .invokeAsyncFrameQueryInt(handlerId, queryInt)
                    .orTimeout(asyncResponseTimeoutMs, TimeUnit.MILLISECONDS)
                    .whenComplete((frame, error) -> completeAsyncHandler(requestId, frame, error));
            return true;
        } catch (Throwable e) {
            throwIfFatal(e);
            completeAsyncHandler(requestId, (HandlerRegistry.AsyncResponseFrame) null, e);
            return true;
        }
    }

    private static void completeAsyncHandler(long requestId, HandlerRegistry.AsyncResponseFrame frame, Throwable error) {
        if (error != null) {
            throwIfFatal(error);
            completeAsyncHandler(requestId, errorFrame(error), null);
            return;
        } else if (frame == null) {
            completeAsyncHandler(requestId, errorFrame(new IllegalStateException("async handler completed with null frame")), null);
            return;
        }
        try {
            ByteBuffer responseFrame = frame.buffer();
            if (responseFrame.isDirect()) {
                completeAsyncResponseBuffer(requestId, responseFrame, frame.length());
            } else {
                completeAsyncResponse(requestId, frame.toByteArray());
            }
        } catch (RuntimeException | LinkageError completionFailure) {
            FrameworkLogger.debugError("[JAVA] Native async response completion was dropped: "
                    + completionFailure.getMessage());
        } finally {
            HandlerRegistry.getInstance().releaseAsyncResponseFrame(frame);
        }
    }

    private static void completeAsyncHandler(long requestId, byte[] frame, Throwable error) {
        byte[] responseFrame = frame;
        if (error != null) {
            throwIfFatal(error);
            responseFrame = errorFrame(error);
        } else if (responseFrame == null) {
            responseFrame = errorFrame(new IllegalStateException("async handler completed with null frame"));
        }
        try {
            completeAsyncResponse(requestId, responseFrame);
        } catch (RuntimeException | LinkageError completionFailure) {
            FrameworkLogger.debugError("[JAVA] Native async response completion was dropped: "
                    + completionFailure.getMessage());
        }
    }

    private static byte[] errorFrame(Throwable error) {
        throwIfFatal(error);
        HttpErrorMapper.MappedError mapped = HttpErrorMapper.map(error);
        byte[] body = HttpErrorMapper.toJsonBytes(mapped);
        ByteBuffer frame = ByteBuffer.allocate(RESPONSE_FRAME_HEADER_SIZE + body.length);
        frame.put(RESPONSE_FRAME_MAGIC);
        frame.putShort((short) mapped.status());
        frame.putInt(0);
        frame.putInt(body.length);
        frame.put(body);
        return frame.array();
    }

    private static int invokeHandler(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) {
        HandlerRegistry registry = HandlerRegistry.getInstance();

        try {
            int written = registry.invokeBuffered(
                    handlerId,
                    outBuffer,
                    offset,
                    inBytes,
                    pathParams,
                    queryString,
                    headers
            );

            if (written < 0) {
                return written;
            }
            if (written > capacity) {
                return -written;
            }
            return written;

        } catch (Throwable e) {
            return writeBridgeError(outBuffer, offset, capacity, e);
        }
    }

    private static int invokeDirectHandler(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            ByteBuffer inBuffer,
            int inLength,
            String pathParams,
            String queryString,
            String headers
    ) {
        HandlerRegistry registry = HandlerRegistry.getInstance();

        try {
            int written = registry.invokeBufferedDirect(
                    handlerId,
                    outBuffer,
                    offset,
                    inBuffer,
                    inLength,
                    pathParams,
                    queryString,
                    headers
            );

            if (written < 0) {
                return written;
            }
            if (written > capacity) {
                return -written;
            }
            return written;

        } catch (Throwable e) {
            return writeBridgeError(outBuffer, offset, capacity, e);
        }
    }

    private static int invokeBodylessOutputHandler(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity
    ) {
        HandlerRegistry registry = HandlerRegistry.getInstance();

        try {
            int written = registry.invokeBufferedBodylessOutput(handlerId, outBuffer, offset);

            if (written < 0) {
                return written;
            }
            if (written > capacity) {
                return -written;
            }
            return written;

        } catch (Throwable e) {
            return writeBridgeError(outBuffer, offset, capacity, e);
        }
    }

    private static int invokeQueryIntHandler(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            int queryInt
    ) {
        HandlerRegistry registry = HandlerRegistry.getInstance();

        try {
            int written = registry.invokeBufferedQueryInt(handlerId, outBuffer, offset, queryInt);

            if (written < 0) {
                return written;
            }
            if (written > capacity) {
                return -written;
            }
            return written;

        } catch (Throwable e) {
            return writeBridgeError(outBuffer, offset, capacity, e);
        }
    }

    private static int invokeQueryLongHandler(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            long queryLong
    ) {
        HandlerRegistry registry = HandlerRegistry.getInstance();

        try {
            int written = registry.invokeBufferedQueryLong(handlerId, outBuffer, offset, queryLong);

            if (written < 0) {
                return written;
            }
            if (written > capacity) {
                return -written;
            }
            return written;

        } catch (Throwable e) {
            return writeBridgeError(outBuffer, offset, capacity, e);
        }
    }

    private static int invokeQueryBooleanHandler(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            boolean queryBoolean
    ) {
        HandlerRegistry registry = HandlerRegistry.getInstance();

        try {
            int written = registry.invokeBufferedQueryBoolean(handlerId, outBuffer, offset, queryBoolean);

            if (written < 0) {
                return written;
            }
            if (written > capacity) {
                return -written;
            }
            return written;

        } catch (Throwable e) {
            return writeBridgeError(outBuffer, offset, capacity, e);
        }
    }

    private static int invokeQueryDoubleHandler(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            double queryDouble
    ) {
        HandlerRegistry registry = HandlerRegistry.getInstance();

        try {
            int written = registry.invokeBufferedQueryDouble(handlerId, outBuffer, offset, queryDouble);

            if (written < 0) {
                return written;
            }
            if (written > capacity) {
                return -written;
            }
            return written;

        } catch (Throwable e) {
            return writeBridgeError(outBuffer, offset, capacity, e);
        }
    }

    private static int invokeQueryShortHandler(
            int handlerId,
            ByteBuffer outBuffer,
            int offset,
            int capacity,
            short queryShort
    ) {
        HandlerRegistry registry = HandlerRegistry.getInstance();

        try {
            int written = registry.invokeBufferedQueryShort(handlerId, outBuffer, offset, queryShort);

            if (written < 0) {
                return written;
            }
            if (written > capacity) {
                return -written;
            }
            return written;

        } catch (Throwable e) {
            return writeBridgeError(outBuffer, offset, capacity, e);
        }
    }

    private static int writeBridgeError(ByteBuffer outBuffer, int offset, int capacity, Throwable error) {
        throwIfFatal(error);
        HttpErrorMapper.MappedError mapped = HttpErrorMapper.map(error);
        byte[] err = HttpErrorMapper.toJsonBytes(mapped);
        int totalSize = RESPONSE_FRAME_HEADER_SIZE + err.length;
        if (totalSize > capacity) {
            return -totalSize;
        }
        outBuffer.position(offset);
        outBuffer.put(RESPONSE_FRAME_MAGIC);
        outBuffer.putShort((short) mapped.status());
        outBuffer.putInt(0);
        outBuffer.putInt(err.length);
        outBuffer.put(err);
        return totalSize;
    }

    private static void throwIfFatal(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        if (current instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (current instanceof ThreadDeath threadDeath) {
            throw threadDeath;
        }
    }

    // ======================
    // WEBSOCKET CALLBACKS (Called from Rust)
    // ======================

    /**
     * Called from Rust when a WebSocket connection is opened.
     */
    public static void onWebSocketOpen(long sessionId, String path) {
        onWebSocketOpen(sessionId, path, "", "");
    }

    /**
     * Called from Rust when a WebSocket connection is opened.
     */
    public static void onWebSocketOpen(long sessionId, String path, String pathParams, String queryParams) {
        try {
            WebSocketRegistry registry = WebSocketRegistry.getInstance();
            registry.onOpen(sessionId, path, pathParams, queryParams);
            debugLog("[NativeBridge] WebSocket opened: sessionId=" + sessionId + ", path=" + path);
        } catch (Exception e) {
            debugError("[NativeBridge] Error in onWebSocketOpen: " + e.getMessage());
        }
    }

    /**
     * Called from Rust when a WebSocket text message is received.
     * Handlers send outbound frames explicitly via WebSocketSession.
     */
    public static String onWebSocketMessage(long sessionId, String message) {
        try {
            WebSocketRegistry registry = WebSocketRegistry.getInstance();
            registry.onMessage(sessionId, message);
            return null;
        } catch (Exception e) {
            debugError("[NativeBridge] Error in onWebSocketMessage: " + e.getMessage());
            return new String(
                    HttpErrorMapper.toJsonBytes(HttpErrorMapper.map(e)),
                    StandardCharsets.UTF_8
            );
        }
    }

    /**
     * Called from Rust when a WebSocket binary message is received.
     * Handlers send outbound frames explicitly via WebSocketSession.
     */
    public static byte[] onWebSocketBinary(long sessionId, byte[] data) {
        try {
            WebSocketRegistry registry = WebSocketRegistry.getInstance();
            registry.onBinary(sessionId, data);
            return null;
        } catch (Exception e) {
            debugError("[NativeBridge] Error in onWebSocketBinary: " + e.getMessage());
            return HttpErrorMapper.toJsonBytes(HttpErrorMapper.map(e));
        }
    }

    /**
     * Called from Rust when a WebSocket connection is closed.
     */
    public static void onWebSocketClose(long sessionId) {
        try {
            WebSocketRegistry registry = WebSocketRegistry.getInstance();
            registry.onClose(sessionId);
            debugLog("[NativeBridge] WebSocket closed: sessionId=" + sessionId);
        } catch (Exception e) {
            debugError("[NativeBridge] Error in onWebSocketClose: " + e.getMessage());
        }
    }

    /**
     * Called from Rust when a WebSocket error occurs.
     */
    public static void onWebSocketError(long sessionId, String error) {
        try {
            WebSocketRegistry registry = WebSocketRegistry.getInstance();
            registry.onError(sessionId, error);
            debugError("[NativeBridge] WebSocket error: sessionId=" + sessionId + ", error=" + error);
        } catch (Exception e) {
            debugError("[NativeBridge] Error in onWebSocketError: " + e.getMessage());
        }
    }

    private static void debugLog(String message) {
        if (isDebugLoggingEnabled()) {
            FrameworkLogger.debug(message);
        }
    }

    private static void debugError(String message) {
        if (isDebugLoggingEnabled()) {
            FrameworkLogger.debugError(message);
        }
    }

}

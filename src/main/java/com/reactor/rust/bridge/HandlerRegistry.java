package com.reactor.rust.bridge;

import com.reactor.rust.annotations.ResponseStatus;
import com.reactor.rust.annotations.ContentType;
import com.reactor.rust.annotations.DirectPathBoolean;
import com.reactor.rust.annotations.DirectPathDouble;
import com.reactor.rust.annotations.DirectPathInt;
import com.reactor.rust.annotations.DirectPathLong;
import com.reactor.rust.annotations.DirectPathShort;
import com.reactor.rust.annotations.DirectQueryBoolean;
import com.reactor.rust.annotations.DirectQueryDouble;
import com.reactor.rust.annotations.DirectQueryInt;
import com.reactor.rust.annotations.DirectQueryLong;
import com.reactor.rust.annotations.DirectQueryShort;
import com.reactor.rust.async.AsyncHandlerExecutor;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.exception.HttpErrorMapper;
import com.reactor.rust.http.DirectJsonResponse;
import com.reactor.rust.http.FileResponse;
import com.reactor.rust.http.JsonProducerResponse;
import com.reactor.rust.http.MediaType;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.json.DslJsonService;
import com.reactor.rust.json.JsonBodyProducer;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.util.RequestValueMap;
import com.reactor.rust.util.UrlCodec;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Optimized handler registry with:
 * - MethodMetadata cache (zero runtime annotation lookup)
 * - RequestValueMap for parameter resolution (O(1) lookup)
 * - Bounded, request-owned async response buffer pool
 * - Exact MethodHandle invocation for common signatures
 */
public class HandlerRegistry {

    private static final HandlerRegistry INSTANCE = new HandlerRegistry();

    private static final int INITIAL_ASYNC_FRAME_BYTES = Math.max(
            1024,
            PropertiesLoader.getInt("reactor.rust.async.frame-initial-bytes", 16 * 1024)
    );
    private static final boolean ASYNC_DIRECT_BUFFER_ENABLED =
            PropertiesLoader.getBoolean("reactor.rust.async.direct-buffer.enabled", false);
    private static final int MAX_ASYNC_RESPONSE_FRAME_BYTES = 8 * 1024 * 1024 + 64 * 1024;
    private static final int ASYNC_FRAME_RETAIN_MAX_BYTES = Math.max(
            INITIAL_ASYNC_FRAME_BYTES,
            PropertiesLoader.getInt("reactor.rust.async.frame-retain-max-bytes", 256 * 1024)
    );
    private static final int ASYNC_FRAME_POOL_CAPACITY = Math.max(
            0,
            PropertiesLoader.getInt("reactor.rust.async.frame-pool-capacity", 8)
    );
    private static final AsyncFrameBufferPool ASYNC_FRAME_POOL = new AsyncFrameBufferPool(
            ASYNC_FRAME_POOL_CAPACITY,
            INITIAL_ASYNC_FRAME_BYTES,
            ASYNC_FRAME_RETAIN_MAX_BYTES,
            MAX_ASYNC_RESPONSE_FRAME_BYTES,
            ASYNC_DIRECT_BUFFER_ENABLED
    );

    // Thread-confined maps avoid request-level allocation after the first use on a worker.
    private static final ThreadLocal<RequestValueMap> PARAM_MAP_POOL =
        ThreadLocal.withInitial(RequestValueMap::new);
    private static final ThreadLocal<RequestValueMap> HEADER_MAP_POOL =
        ThreadLocal.withInitial(RequestValueMap::new);

    // Lazy logger - only logs when DEBUG is true
    private static final boolean DEBUG = Boolean.getBoolean("handler.debug") || FrameworkLogger.isDebugEnabled();
    private static final byte[] RESPONSE_FRAME_MAGIC =
            new byte[] {'R', 'J', 'R', 'S', 'P', 'V', '1', '!'};
    private static final byte[] FILE_RESPONSE_FRAME_MAGIC =
            new byte[] {'R', 'J', 'F', 'I', 'L', 'E', '1', '!'};
    private static final byte[] STATIC_RESPONSE_FRAME_MAGIC =
            new byte[] {'R', 'J', 'S', 'T', 'A', 'T', '1', '!'};
    private static final int RESPONSE_FRAME_HEADER_SIZE = 18;
    private static final int MAX_FILE_RESPONSE_PATH_BYTES = 4096;
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final int MAX_EXACT_ANNOTATED_PARAMS = 8;
    private static final Object SINGLE_VALUE_FAST_PATH_MISS = new Object();
    private static final byte[] DEFAULT_JSON_CONTENT_TYPE_HEADER =
            ("Content-Type: " + MediaType.APPLICATION_JSON_UTF8 + "\n").getBytes(StandardCharsets.UTF_8);

    public static HandlerRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<Integer, HandlerDescriptor> handlers = new ConcurrentHashMap<>();
    private final List<Object> handlerBeans = new CopyOnWriteArrayList<>();
    private final AtomicInteger idGenerator = new AtomicInteger(1);

    public static class HandlerDescriptor {
        public final Object bean;
        public final Method method;
        public final Class<?> requestType;
        public final Class<?> responseType;
        public final MethodHandle handle;
        public final boolean usesAnnotatedParams;
        public final boolean usesDirectBodyBuffer;
        public final boolean usesDirectQueryInt;
        public final boolean usesDirectQueryLong;
        public final boolean usesDirectQueryBoolean;
        public final boolean usesDirectQueryDouble;
        public final boolean usesDirectQueryShort;
        public final boolean usesDirectScalarInt;
        public final boolean usesDirectBodylessOutput;
        public final boolean returnsResponseEntity;
        public final boolean isAsync;
        public final int customResponseStatus;
        public final byte[] defaultContentTypeHeader;
        private final LongAdder invocationCount = new LongAdder();

        // Cached metadata for fast parameter resolution
        public final MethodMetadata metadata;
        public final CompiledRouteInvoker compiledInvoker;

        public HandlerDescriptor(Object bean,
                Method method,
                Class<?> requestType,
                Class<?> responseType,
                MethodHandle handle,
                boolean usesAnnotatedParams,
                boolean usesDirectBodyBuffer,
                boolean usesDirectQueryInt,
                boolean usesDirectQueryLong,
                boolean usesDirectQueryBoolean,
                boolean usesDirectQueryDouble,
                boolean usesDirectQueryShort,
                boolean usesDirectScalarInt,
                boolean usesDirectBodylessOutput,
                boolean returnsResponseEntity,
                boolean isAsync,
                int customResponseStatus,
                byte[] defaultContentTypeHeader) {
            this(bean, method, requestType, responseType, handle, usesAnnotatedParams, usesDirectBodyBuffer,
                    usesDirectQueryInt, usesDirectQueryLong, usesDirectQueryBoolean,
                    usesDirectQueryDouble, usesDirectQueryShort, usesDirectScalarInt,
                    usesDirectBodylessOutput, returnsResponseEntity, isAsync,
                    customResponseStatus, defaultContentTypeHeader,
                    MethodMetadata.getOrCreate(method, requestType, responseType));
        }

        private HandlerDescriptor(Object bean,
                Method method,
                Class<?> requestType,
                Class<?> responseType,
                MethodHandle handle,
                boolean usesAnnotatedParams,
                boolean usesDirectBodyBuffer,
                boolean usesDirectQueryInt,
                boolean usesDirectQueryLong,
                boolean usesDirectQueryBoolean,
                boolean usesDirectQueryDouble,
                boolean usesDirectQueryShort,
                boolean usesDirectScalarInt,
                boolean usesDirectBodylessOutput,
                boolean returnsResponseEntity,
                boolean isAsync,
                int customResponseStatus,
                byte[] defaultContentTypeHeader,
                MethodMetadata metadata) {
            this.bean = bean;
            this.method = method;
            this.requestType = requestType;
            this.responseType = responseType;
            this.handle = handle;
            this.usesAnnotatedParams = usesAnnotatedParams;
            this.usesDirectBodyBuffer = usesDirectBodyBuffer;
            this.usesDirectQueryInt = usesDirectQueryInt;
            this.usesDirectQueryLong = usesDirectQueryLong;
            this.usesDirectQueryBoolean = usesDirectQueryBoolean;
            this.usesDirectQueryDouble = usesDirectQueryDouble;
            this.usesDirectQueryShort = usesDirectQueryShort;
            this.usesDirectScalarInt = usesDirectScalarInt;
            this.usesDirectBodylessOutput = usesDirectBodylessOutput;
            this.returnsResponseEntity = returnsResponseEntity;
            this.isAsync = isAsync;
            this.customResponseStatus = customResponseStatus;
            this.defaultContentTypeHeader =
                    defaultContentTypeHeader != null ? defaultContentTypeHeader : DEFAULT_JSON_CONTENT_TYPE_HEADER;
            this.metadata = metadata;
            this.compiledInvoker = CompiledRouteInvoker.compile(handle, metadata);
        }

        // Legacy constructor for backwards compatibility
        public HandlerDescriptor(Object bean,
                Method method,
                Class<?> requestType,
                Class<?> responseType,
                MethodHandle handle) {
            this(bean, method, requestType, responseType, handle, false, false,
                    false, false, false, false, false, false, false, false, false, 200,
                    DEFAULT_JSON_CONTENT_TYPE_HEADER);
        }

        void recordInvocation() {
            if (RoutePlanRegistry.getInstance().runtimeMetricsEnabled()) {
                invocationCount.increment();
            }
        }

        long invocationCount() {
            return invocationCount.sum();
        }
    }

    public static final class AsyncResponseFrame {
        private final ByteBuffer buffer;
        private final int length;

        private AsyncResponseFrame(ByteBuffer buffer, int length) {
            this.buffer = buffer;
            this.length = length;
        }

        public ByteBuffer buffer() {
            ByteBuffer duplicate = buffer.duplicate();
            duplicate.position(0);
            duplicate.limit(length);
            return duplicate;
        }

        public int length() {
            return length;
        }

        public byte[] toByteArray() {
            ByteBuffer duplicate = buffer();
            byte[] response = new byte[length];
            duplicate.get(response, 0, length);
            return response;
        }
    }

    void releaseAsyncResponseFrame(AsyncResponseFrame frame) {
        if (frame != null) {
            ASYNC_FRAME_POOL.release(frame.buffer);
        }
    }

    public String asyncFramePoolDiagnosticsJson() {
        AsyncFrameBufferPool.Snapshot snapshot = ASYNC_FRAME_POOL.snapshot();
        return "{\"capacity\":" + snapshot.capacity()
                + ",\"size\":" + snapshot.size()
                + ",\"initial_buffer_bytes\":" + snapshot.initialBufferBytes()
                + ",\"retain_max_bytes\":" + snapshot.retainMaxBytes()
                + ",\"direct\":" + snapshot.direct()
                + ",\"hit\":" + snapshot.hits()
                + ",\"miss\":" + snapshot.misses()
                + ",\"returned\":" + snapshot.returned()
                + ",\"dropped\":" + snapshot.dropped()
                + '}';
    }

    public String asyncFramePoolMetricsPrometheus() {
        AsyncFrameBufferPool.Snapshot snapshot = ASYNC_FRAME_POOL.snapshot();
        return "reactor_java_async_frame_pool_capacity " + snapshot.capacity() + '\n'
                + "reactor_java_async_frame_pool_size " + snapshot.size() + '\n'
                + "reactor_java_async_frame_pool_retain_max_bytes " + snapshot.retainMaxBytes() + '\n'
                + "reactor_java_async_frame_pool_hit_total " + snapshot.hits() + '\n'
                + "reactor_java_async_frame_pool_miss_total " + snapshot.misses() + '\n'
                + "reactor_java_async_frame_pool_return_total " + snapshot.returned() + '\n'
                + "reactor_java_async_frame_pool_drop_total " + snapshot.dropped() + '\n';
    }

    public void resetAsyncFramePoolMetrics() {
        ASYNC_FRAME_POOL.resetMetrics();
    }

    private HandlerRegistry() {}

    public List<Object> getHandlers() {
        return handlerBeans;
    }

    public List<HandlerDescriptor> descriptorsSnapshot() {
        return List.copyOf(handlers.values());
    }

    public void registerBean(Object bean) {
        if (!handlerBeans.contains(bean)) {
            handlerBeans.add(bean);
            if (DEBUG) {
                FrameworkLogger.debug("[HandlerRegistry] bean registered = " + bean.getClass().getName());
            }
        }
    }

    public boolean isBodyless(int handlerId) {
        HandlerDescriptor desc = handlers.get(handlerId);
        if (desc == null) return false;
        return (desc.requestType == Void.class) || (desc.method.getParameterCount() == 0);
    }

    public long getInvocationCount(int handlerId) {
        HandlerDescriptor desc = handlers.get(handlerId);
        return desc != null ? desc.invocationCount() : 0L;
    }

    public boolean usesExactInvoker(int handlerId) {
        HandlerDescriptor desc = handlers.get(handlerId);
        return desc != null && desc.compiledInvoker.usesExactAdapter();
    }

    public int registerHandler(Object bean,
            Method method,
            Class<?> requestType,
            Class<?> responseType) {

        try {
            MethodHandle mh = MethodHandles.lookup()
                    .unreflect(method)
                    .bindTo(bean);

            // Check if method returns ResponseEntity
            boolean returnsResponseEntity = ParameterResolver.returnsResponseEntity(method);

            // Legacy V4 handlers receive the raw JNI arguments directly.
            boolean legacyV4 = isLegacyV4(method);
            boolean directV5 = isDirectV5(method);
            boolean directScalarInt = isDirectScalarInt(method);
            boolean directQueryInt = (isDirectInt(method) || directScalarInt)
                    && (method.isAnnotationPresent(DirectQueryInt.class)
                    || method.isAnnotationPresent(DirectPathInt.class));
            boolean directQueryLong = isDirectLong(method)
                    && (method.isAnnotationPresent(DirectQueryLong.class)
                    || method.isAnnotationPresent(DirectPathLong.class));
            boolean directQueryBoolean = isDirectBoolean(method)
                    && (method.isAnnotationPresent(DirectQueryBoolean.class)
                    || method.isAnnotationPresent(DirectPathBoolean.class));
            boolean directQueryDouble = isDirectDouble(method)
                    && (method.isAnnotationPresent(DirectQueryDouble.class)
                    || method.isAnnotationPresent(DirectPathDouble.class));
            boolean directQueryShort = isDirectShort(method)
                    && (method.isAnnotationPresent(DirectQueryShort.class)
                    || method.isAnnotationPresent(DirectPathShort.class));
            boolean directBodylessOutput = isDirectBodylessOutput(method);

            // Modern handlers may be no-arg, annotated, or return ResponseEntity.
            boolean usesAnnotatedParams = !legacyV4
                    && !directV5
                    && !directQueryInt
                    && !directQueryLong
                    && !directQueryBoolean
                    && !directQueryDouble
                    && !directQueryShort
                    && !directScalarInt
                    && !directBodylessOutput
                    && (ParameterResolver.isAnnotatedMethod(method)
                    || method.getParameterCount() == 0
                    || returnsResponseEntity);
            if (usesAnnotatedParams && method.getParameterCount() > MAX_EXACT_ANNOTATED_PARAMS) {
                throw new IllegalArgumentException(
                        "Annotated handler " + method
                                + " has " + method.getParameterCount()
                                + " parameters; max supported for exact MethodHandle invocation is "
                                + MAX_EXACT_ANNOTATED_PARAMS
                                + ". Use a request DTO instead of many scalar parameters."
                );
            }

            // Check if method returns CompletableFuture (async)
            boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());
            boolean borrowsOutputBuffer = legacyV4
                    || directV5
                    || isDirectInt(method)
                    || isDirectLong(method)
                    || isDirectBoolean(method)
                    || isDirectDouble(method)
                    || isDirectShort(method)
                    || directBodylessOutput;
            if (isAsync && borrowsOutputBuffer) {
                throw new IllegalArgumentException(
                        "Async handler " + method
                                + " cannot receive a framework-owned ByteBuffer. "
                                + "Return CompletionStage<JsonBodyProducer>, CompletionStage<RawResponse>, "
                                + "or CompletionStage<ResponseEntity<?>> instead."
                );
            }

            // Check for @ResponseStatus annotation
            int customResponseStatus = 200;
            ResponseStatus responseStatus = method.getAnnotation(ResponseStatus.class);
            if (responseStatus != null) {
                customResponseStatus = responseStatus.value();
            }
            byte[] defaultContentTypeHeader = defaultContentTypeHeader(method);

            int id = idGenerator.getAndIncrement();
            handlers.put(id, new HandlerDescriptor(
                bean, method, requestType, responseType, mh,
                usesAnnotatedParams, directV5, directQueryInt, directQueryLong, directQueryBoolean,
                directQueryDouble, directQueryShort, directScalarInt, directBodylessOutput,
                returnsResponseEntity, isAsync, customResponseStatus,
                defaultContentTypeHeader
            ));

            if (DEBUG) {
                FrameworkLogger.debug("[HandlerRegistry] Handler registered: id=" + id
                        + " bean=" + bean.getClass().getName()
                        + " method=" + method.getName()
                        + " reqType=" + requestType.getName()
                        + " respType=" + responseType.getName()
                        + " annotatedParams=" + usesAnnotatedParams
                        + " directBodyBuffer=" + directV5
                        + " directQueryInt=" + directQueryInt
                        + " directQueryLong=" + directQueryLong
                        + " directQueryBoolean=" + directQueryBoolean
                        + " directQueryDouble=" + directQueryDouble
                        + " directQueryShort=" + directQueryShort
                        + " directScalarInt=" + directScalarInt
                        + " directBodylessOutput=" + directBodylessOutput
                        + " returnsResponseEntity=" + returnsResponseEntity
                    + " isAsync=" + isAsync
                    + " defaultContentType=" + new String(defaultContentTypeHeader, StandardCharsets.UTF_8).trim());
            }

            return id;

        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to create MethodHandle for handler", e);
        }
    }

    private static byte[] defaultContentTypeHeader(Method method) {
        ContentType contentType = method.getAnnotation(ContentType.class);
        String value = contentType != null && contentType.value() != null && !contentType.value().isBlank()
                ? contentType.value()
                : MediaType.APPLICATION_JSON_UTF8;
        return ("Content-Type: " + normalizeTextualContentType(value) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isLegacyV4(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 6
                && parameterTypes[0] == ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == byte[].class
                && parameterTypes[3] == String.class
                && parameterTypes[4] == String.class
                && parameterTypes[5] == String.class;
    }

    private static boolean isDirectV5(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 7
                && parameterTypes[0] == ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == ByteBuffer.class
                && parameterTypes[3] == int.class
                && parameterTypes[4] == String.class
                && parameterTypes[5] == String.class
                && parameterTypes[6] == String.class;
    }

    private static boolean isDirectInt(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 3
                && parameterTypes[0] == ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == int.class;
    }

    private static boolean isDirectScalarInt(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 1
                && parameterTypes[0] == int.class
                && returnsDirectScalarIntResult(method);
    }

    private static boolean returnsDirectScalarIntResult(Method method) {
        Class<?> returnType = method.getReturnType();
        if (JsonProducerResponse.class.isAssignableFrom(returnType)
                || JsonBodyProducer.class.isAssignableFrom(returnType)
                || RawResponse.class.isAssignableFrom(returnType)) {
            return true;
        }
        if (!CompletionStage.class.isAssignableFrom(returnType)) {
            return false;
        }
        Type genericReturnType = method.getGenericReturnType();
        if (!(genericReturnType instanceof ParameterizedType parameterizedType)) {
            return false;
        }
        for (Type argument : parameterizedType.getActualTypeArguments()) {
            if (argument instanceof Class<?> clazz
                    && (JsonProducerResponse.class.isAssignableFrom(clazz)
                    || JsonBodyProducer.class.isAssignableFrom(clazz))) {
                return true;
            }
            if (argument instanceof ParameterizedType nested
                    && nested.getRawType() instanceof Class<?> raw
                    && (JsonProducerResponse.class.isAssignableFrom(raw)
                    || JsonBodyProducer.class.isAssignableFrom(raw))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDirectLong(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 3
                && parameterTypes[0] == ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == long.class;
    }

    private static boolean isDirectBoolean(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 3
                && parameterTypes[0] == ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == boolean.class;
    }

    private static boolean isDirectDouble(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 3
                && parameterTypes[0] == ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == double.class;
    }

    private static boolean isDirectShort(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 3
                && parameterTypes[0] == ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == short.class;
    }

    private static boolean isDirectBodylessOutput(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 2
                && parameterTypes[0] == ByteBuffer.class
                && parameterTypes[1] == int.class;
    }

    /**
     * Single invoke method - supports both V4 signature and annotated parameters.
     */
    public int invokeBuffered(
            int handlerId,
            ByteBuffer out,
            int offset,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) {
        HandlerDescriptor desc = handlers.get(handlerId);

        if (desc == null) {
            return writeError(out, offset, "Unknown handlerId");
        }
        desc.recordInvocation();

        try {
            // Choose invocation strategy based on method signature
            if (desc.usesAnnotatedParams) {
                return invokeAnnotatedFast(desc, out, offset, inBytes, pathParams, queryString, headers);
            } else if (desc.usesDirectBodyBuffer) {
                return invokeV5Direct(desc, out, offset, null, 0,
                        pathParams, queryString, headers);
            } else if (desc.usesDirectBodylessOutput) {
                return invokeBodylessOutput(desc, out, offset);
            } else {
                return invokeV4(desc, out, offset, inBytes, pathParams, queryString, headers);
            }

        } catch (Throwable e) {
            return writeError(out, offset, e);
        }
    }

    /**
     * Direct-buffer request body entry point from JNI.
     * The input buffer is valid only for this synchronous invocation.
     */
    public int invokeBufferedDirect(
            int handlerId,
            ByteBuffer out,
            int offset,
            ByteBuffer inBuffer,
            int inLength,
            String pathParams,
            String queryString,
            String headers
    ) {
        HandlerDescriptor desc = handlers.get(handlerId);

        if (desc == null) {
            return writeError(out, offset, "Unknown handlerId");
        }
        desc.recordInvocation();

        try {
            if (desc.usesAnnotatedParams) {
                return invokeAnnotatedFastDirect(desc, out, offset, inBuffer, inLength, pathParams, queryString, headers);
            }
            if (desc.usesDirectBodyBuffer) {
                return invokeV5Direct(desc, out, offset, inBuffer, inLength, pathParams, queryString, headers);
            }
            if (desc.usesDirectBodylessOutput) {
                return invokeBodylessOutput(desc, out, offset);
            }

            return invokeV4(desc, out, offset, toByteArray(inBuffer, inLength), pathParams, queryString, headers);
        } catch (Throwable e) {
            return writeError(out, offset, e);
        }
    }

    public int invokeBufferedQueryInt(
            int handlerId,
            ByteBuffer out,
            int offset,
            int queryInt
    ) {
        HandlerDescriptor desc = handlers.get(handlerId);

        if (desc == null) {
            return writeError(out, offset, "Unknown handlerId");
        }
        if (!desc.usesDirectQueryInt) {
            return writeError(out, offset, "Handler does not support direct query int");
        }
        desc.recordInvocation();

        try {
            Object result = desc.usesDirectScalarInt
                    ? desc.handle.invoke(queryInt)
                    : desc.handle.invoke(out, offset, queryInt);
            return processDirectResult(desc, result, out, offset);
        } catch (Throwable e) {
            return writeError(out, offset, e);
        }
    }

    public int invokeBufferedQueryLong(
            int handlerId,
            ByteBuffer out,
            int offset,
            long queryLong
    ) {
        HandlerDescriptor desc = handlers.get(handlerId);

        if (desc == null) {
            return writeError(out, offset, "Unknown handlerId");
        }
        if (!desc.usesDirectQueryLong) {
            return writeError(out, offset, "Handler does not support direct query long");
        }
        desc.recordInvocation();

        try {
            return processDirectResult(desc, desc.handle.invoke(out, offset, queryLong), out, offset);
        } catch (Throwable e) {
            return writeError(out, offset, e);
        }
    }

    public int invokeBufferedQueryBoolean(
            int handlerId,
            ByteBuffer out,
            int offset,
            boolean queryBoolean
    ) {
        HandlerDescriptor desc = handlers.get(handlerId);

        if (desc == null) {
            return writeError(out, offset, "Unknown handlerId");
        }
        if (!desc.usesDirectQueryBoolean) {
            return writeError(out, offset, "Handler does not support direct query boolean");
        }
        desc.recordInvocation();

        try {
            return processDirectResult(desc, desc.handle.invoke(out, offset, queryBoolean), out, offset);
        } catch (Throwable e) {
            return writeError(out, offset, e);
        }
    }

    public int invokeBufferedQueryDouble(
            int handlerId,
            ByteBuffer out,
            int offset,
            double queryDouble
    ) {
        HandlerDescriptor desc = handlers.get(handlerId);

        if (desc == null) {
            return writeError(out, offset, "Unknown handlerId");
        }
        if (!desc.usesDirectQueryDouble) {
            return writeError(out, offset, "Handler does not support direct query double");
        }
        desc.recordInvocation();

        try {
            return processDirectResult(desc, desc.handle.invoke(out, offset, queryDouble), out, offset);
        } catch (Throwable e) {
            return writeError(out, offset, e);
        }
    }

    public int invokeBufferedQueryShort(
            int handlerId,
            ByteBuffer out,
            int offset,
            short queryShort
    ) {
        HandlerDescriptor desc = handlers.get(handlerId);

        if (desc == null) {
            return writeError(out, offset, "Unknown handlerId");
        }
        if (!desc.usesDirectQueryShort) {
            return writeError(out, offset, "Handler does not support direct query short");
        }
        desc.recordInvocation();

        try {
            return processDirectResult(desc, desc.handle.invoke(out, offset, queryShort), out, offset);
        } catch (Throwable e) {
            return writeError(out, offset, e);
        }
    }

    public int invokeBufferedBodylessOutput(
            int handlerId,
            ByteBuffer out,
            int offset
    ) {
        HandlerDescriptor desc = handlers.get(handlerId);

        if (desc == null) {
            return writeError(out, offset, "Unknown handlerId");
        }
        if (!desc.usesDirectBodylessOutput) {
            return writeError(out, offset, "Handler does not support direct bodyless output");
        }
        desc.recordInvocation();

        try {
            return invokeBodylessOutput(desc, out, offset);
        } catch (Throwable e) {
            return writeError(out, offset, e);
        }
    }

    /**
     * Invoke V4 signature handler (legacy).
     */
    private int invokeV4(
            HandlerDescriptor desc,
            ByteBuffer out,
            int offset,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) throws Throwable {

        Object result = desc.handle.invoke(out, offset, inBytes, pathParams, queryString, headers);

        return processDirectResult(desc, result, out, offset);
    }

    /**
     * Direct body-buffer handler. The input buffer is valid only during this call.
     */
    private int invokeV5Direct(
            HandlerDescriptor desc,
            ByteBuffer out,
            int offset,
            ByteBuffer inBuffer,
            int inLength,
            String pathParams,
            String queryString,
            String headers
    ) throws Throwable {

        Object result = desc.handle.invoke(
                out,
                offset,
                duplicateBody(inBuffer, inLength),
                safeBodyLength(inBuffer, inLength),
                pathParams,
                queryString,
                headers
        );

        return processDirectResult(desc, result, out, offset);
    }

    private int invokeBodylessOutput(
            HandlerDescriptor desc,
            ByteBuffer out,
            int offset
    ) throws Throwable {
        Object result = desc.handle.invoke(out, offset);
        return processDirectResult(desc, result, out, offset);
    }

    private int processDirectResult(
            HandlerDescriptor desc,
            Object result,
            ByteBuffer out,
            int offset
    ) {
        if (result instanceof Integer) {
            return (Integer) result;
        }

        if (result instanceof FileResponse fileResponse) {
            return writeFileResponse(fileResponse, 200, EMPTY_BYTES, out, offset);
        }

        if (result instanceof RawResponse rawResponse) {
            return writeRawResponse(rawResponse, 200, EMPTY_BYTES, out, offset);
        }

        if (result instanceof DirectJsonResponse<?> directJsonResponse) {
            return writeDirectJsonResponse(
                    directJsonResponse,
                    directJsonResponse.getStatusCode(),
                    EMPTY_BYTES,
                    out,
                    offset
            );
        }

        if (result instanceof JsonProducerResponse producerResponse) {
            return writeJsonProducerResponse(
                    producerResponse,
                    producerResponse.getStatusCode(),
                    EMPTY_BYTES,
                    out,
                    offset
            );
        }

        if (result instanceof JsonBodyProducer producer) {
            return writeJsonBodyProducer(producer, 200, EMPTY_BYTES, desc.defaultContentTypeHeader, out, offset);
        }

        if (result instanceof ResponseEntity<?> responseEntity) {
            return writeResponseEntity(responseEntity, desc.defaultContentTypeHeader, out, offset);
        }

        if (desc.customResponseStatus != 200 && result != null) {
            return writeObjectFrame(desc.customResponseStatus, result, desc.defaultContentTypeHeader, out, offset);
        }

        if (result == null) {
            return writeError(out, offset, "Unexpected null return");
        }

        return writeError(out, offset, "Unexpected return type: " + result.getClass().getName());
    }

    /**
     * Fast annotated invocation using RequestValueMap for O(1) parameter lookup.
     */
    private int invokeAnnotatedFast(
            HandlerDescriptor desc,
            ByteBuffer out,
            int offset,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) throws Throwable {

        if (desc.compiledInvoker.arity() == 0 && !desc.metadata.needsBody) {
            Object result = desc.compiledInvoker.invoke(EMPTY_BYTES, null, null);
            return writeAnnotatedResult(desc, result, out, offset);
        }

        Object singleValueResult = tryInvokeSingleRawValue(desc, pathParams, queryString, headers);
        if (singleValueResult != SINGLE_VALUE_FAST_PATH_MISS) {
            return writeAnnotatedResult(desc, singleValueResult, out, offset);
        }

        RequestValueMap paramMap = PARAM_MAP_POOL.get();
        RequestValueMap headerMap = HEADER_MAP_POOL.get();

        try {
            paramMap.clear();
            headerMap.clear();

            // Parse only what the method actually consumes.
            if (desc.metadata.needsPathParams) {
                parseParamsFast(paramMap, pathParams, false, desc.metadata.pathParamNames);
            }
            if (desc.metadata.needsQueryParams) {
                parseParamsFast(paramMap, queryString, true, desc.metadata.queryParamNames);
            }
            if (desc.metadata.needsHeaders) {
                parseHeadersFast(headerMap, headers, desc.metadata.headerNames);
            }

            Object result = invokeAnnotatedHandle(desc, inBytes, paramMap, headerMap);
            return writeAnnotatedResult(desc, result, out, offset);

        } finally {
            paramMap.clear();
            headerMap.clear();
        }
    }

    private int invokeAnnotatedFastDirect(
            HandlerDescriptor desc,
            ByteBuffer out,
            int offset,
            ByteBuffer inBuffer,
            int inLength,
            String pathParams,
            String queryString,
            String headers
    ) throws Throwable {

        if (desc.compiledInvoker.arity() == 0 && !desc.metadata.needsBody) {
            Object result = desc.compiledInvoker.invokeDirect(null, 0, null, null);
            return writeAnnotatedResult(desc, result, out, offset);
        }

        Object singleValueResult = tryInvokeSingleRawValue(desc, pathParams, queryString, headers);
        if (singleValueResult != SINGLE_VALUE_FAST_PATH_MISS) {
            return writeAnnotatedResult(desc, singleValueResult, out, offset);
        }

        RequestValueMap paramMap = PARAM_MAP_POOL.get();
        RequestValueMap headerMap = HEADER_MAP_POOL.get();

        try {
            paramMap.clear();
            headerMap.clear();

            if (desc.metadata.needsPathParams) {
                parseParamsFast(paramMap, pathParams, false, desc.metadata.pathParamNames);
            }
            if (desc.metadata.needsQueryParams) {
                parseParamsFast(paramMap, queryString, true, desc.metadata.queryParamNames);
            }
            if (desc.metadata.needsHeaders) {
                parseHeadersFast(headerMap, headers, desc.metadata.headerNames);
            }

            Object result = invokeAnnotatedHandleDirect(desc, inBuffer, inLength, paramMap, headerMap);
            return writeAnnotatedResult(desc, result, out, offset);

        } finally {
            paramMap.clear();
            headerMap.clear();
        }
    }

    private int writeAnnotatedResult(
            HandlerDescriptor desc,
            Object result,
            ByteBuffer out,
            int offset
    ) {
        if (result instanceof Integer) {
            return (Integer) result;
        }

        if (result instanceof FileResponse fileResponse) {
            return writeFileResponse(fileResponse, 200, EMPTY_BYTES, out, offset);
        }

        if (result instanceof RawResponse rawResponse) {
            return writeRawResponse(rawResponse, 200, EMPTY_BYTES, out, offset);
        }

        if (result instanceof DirectJsonResponse<?> directJsonResponse) {
            return writeDirectJsonResponse(
                    directJsonResponse,
                    directJsonResponse.getStatusCode(),
                    EMPTY_BYTES,
                    out,
                    offset
            );
        }

        if (result instanceof JsonProducerResponse producerResponse) {
            return writeJsonProducerResponse(
                    producerResponse,
                    producerResponse.getStatusCode(),
                    EMPTY_BYTES,
                    out,
                    offset
            );
        }

        if (result instanceof JsonBodyProducer producer) {
            return writeJsonBodyProducer(producer, 200, EMPTY_BYTES, desc.defaultContentTypeHeader, out, offset);
        }

        if (result instanceof ResponseEntity<?> responseEntity) {
            return writeResponseEntity(responseEntity, desc.defaultContentTypeHeader, out, offset);
        }

        if (result != null && desc.responseType != Void.class) {
            if (desc.customResponseStatus != 200) {
                return writeObjectFrame(desc.customResponseStatus, result, desc.defaultContentTypeHeader, out, offset);
            }
            return writeObjectFrame(200, result, desc.defaultContentTypeHeader, out, offset);
        }

        if (desc.customResponseStatus != 200) {
            return writeFrameWithBytes(desc.customResponseStatus, desc.defaultContentTypeHeader, EMPTY_BYTES, out, offset);
        }

        return 0;
    }

    /**
     * Fast parameter parsing into RequestValueMap.
     */
    private void parseParamsFast(RequestValueMap map, String params, boolean plusAsSpace, String[] wantedNames) {
        if (params == null || params.isEmpty()) return;

        int start = 0;
        int eqIdx = -1;
        int len = params.length();

        for (int i = 0; i <= len; i++) {
            char ch = i < len ? params.charAt(i) : '&';
            if (i < len && ch == '=' && eqIdx < 0) {
                eqIdx = i;
                continue;
            }
            if (i == len || ch == '&') {
                if (i > start) {
                    if (eqIdx > start && eqIdx < i) {
                        String key = matchWantedParamName(params, start, eqIdx, plusAsSpace, wantedNames);
                        if (key != null) {
                            String value = UrlCodec.decodeComponent(params.substring(eqIdx + 1, i), plusAsSpace);
                            map.put(key, value);
                        }
                    }
                }
                start = i + 1;
                eqIdx = -1;
            }
        }
    }

    /**
     * Fast header parsing into RequestValueMap.
     */
    private void parseHeadersFast(RequestValueMap map, String headers, String[] wantedNames) {
        if (headers == null || headers.isEmpty()) return;

        int start = 0;
        int colonIdx = -1;
        int len = headers.length();

        for (int i = 0; i <= len; i++) {
            char ch = i < len ? headers.charAt(i) : '\n';
            if (i < len && ch == ':' && colonIdx < 0) {
                colonIdx = i;
                continue;
            }
            if (i == len || ch == '\n') {
                if (i > start) {
                    if (colonIdx > start && colonIdx < i) {
                        int keyStart = trimLeft(headers, start, colonIdx);
                        int keyEnd = trimRight(headers, keyStart, colonIdx);
                        String key = matchWantedHeaderName(headers, keyStart, keyEnd, wantedNames);
                        if (key != null) {
                            int valueStart = trimLeft(headers, colonIdx + 1, i);
                            int valueEnd = trimRight(headers, valueStart, i);
                            String value = headers.substring(valueStart, valueEnd);
                            map.put(key, value);
                        }
                    }
                }
                start = i + 1;
                colonIdx = -1;
            }
        }
    }

    private Object tryInvokeSingleRawValue(
            HandlerDescriptor desc,
            String pathParams,
            String queryString,
            String headers
    ) throws Throwable {
        MethodMetadata metadata = desc.metadata;
        if (metadata.needsBody || !desc.compiledInvoker.acceptsSingleRawValue()) {
            return SINGLE_VALUE_FAST_PATH_MISS;
        }

        if (metadata.needsPathParams
                && !metadata.needsQueryParams
                && !metadata.needsHeaders
                && metadata.pathParamNames.length == 1) {
            String value = findParamValue(pathParams, metadata.pathParamNames[0], false);
            return desc.compiledInvoker.invokeSingleRawValue(value);
        }

        if (metadata.needsQueryParams
                && !metadata.needsPathParams
                && !metadata.needsHeaders
                && metadata.queryParamNames.length == 1) {
            String value = findParamValue(queryString, metadata.queryParamNames[0], true);
            return desc.compiledInvoker.invokeSingleRawValue(value);
        }

        if (metadata.needsHeaders
                && !metadata.needsPathParams
                && !metadata.needsQueryParams
                && !metadata.needsCookies
                && metadata.headerNames.length == 1) {
            String value = findHeaderValue(headers, metadata.headerNames[0]);
            return desc.compiledInvoker.invokeSingleRawValue(value);
        }

        if (metadata.needsCookies
                && !metadata.needsPathParams
                && !metadata.needsQueryParams
                && metadata.headerNames.length == 1
                && "cookie".equals(metadata.headerNames[0])) {
            String cookieHeader = findHeaderValue(headers, "cookie");
            return desc.compiledInvoker.invokeSingleRawValue(cookieHeader);
        }

        return SINGLE_VALUE_FAST_PATH_MISS;
    }

    private String findParamValue(String params, String name, boolean plusAsSpace) {
        if (params == null || params.isEmpty() || name == null || name.isEmpty()) {
            return null;
        }

        String found = null;
        int start = 0;
        int eqIdx = -1;
        int len = params.length();

        for (int i = 0; i <= len; i++) {
            char ch = i < len ? params.charAt(i) : '&';
            if (i < len && ch == '=' && eqIdx < 0) {
                eqIdx = i;
                continue;
            }
            if (i == len || ch == '&') {
                if (i > start && eqIdx > start && eqIdx < i
                        && matchesParamName(params, start, eqIdx, plusAsSpace, name)) {
                    found = UrlCodec.decodeComponent(params.substring(eqIdx + 1, i), plusAsSpace);
                }
                start = i + 1;
                eqIdx = -1;
            }
        }

        return found;
    }

    private String findHeaderValue(String headers, String name) {
        if (headers == null || headers.isEmpty() || name == null || name.isEmpty()) {
            return null;
        }

        String found = null;
        int start = 0;
        int colonIdx = -1;
        int len = headers.length();

        for (int i = 0; i <= len; i++) {
            char ch = i < len ? headers.charAt(i) : '\n';
            if (i < len && ch == ':' && colonIdx < 0) {
                colonIdx = i;
                continue;
            }
            if (i == len || ch == '\n') {
                if (i > start && colonIdx > start && colonIdx < i) {
                    int keyStart = trimLeft(headers, start, colonIdx);
                    int keyEnd = trimRight(headers, keyStart, colonIdx);
                    if (regionEqualsIgnoreCase(headers, keyStart, keyEnd, name)) {
                        int valueStart = trimLeft(headers, colonIdx + 1, i);
                        int valueEnd = trimRight(headers, valueStart, i);
                        found = headers.substring(valueStart, valueEnd);
                    }
                }
                start = i + 1;
                colonIdx = -1;
            }
        }

        return found;
    }

    private String matchWantedParamName(
            String params,
            int start,
            int end,
            boolean plusAsSpace,
            String[] wantedNames
    ) {
        if (wantedNames == null || wantedNames.length == 0) {
            return UrlCodec.decodeComponent(params.substring(start, end), plusAsSpace);
        }

        for (String wanted : wantedNames) {
            if (regionEquals(params, start, end, wanted)) {
                return wanted;
            }
        }

        boolean encoded = false;
        for (int i = start; i < end; i++) {
            char ch = params.charAt(i);
            if (ch == '%' || (plusAsSpace && ch == '+')) {
                encoded = true;
                break;
            }
        }
        if (!encoded) {
            return null;
        }

        String decoded = UrlCodec.decodeComponent(params.substring(start, end), plusAsSpace);
        for (String wanted : wantedNames) {
            if (wanted.equals(decoded)) {
                return wanted;
            }
        }
        return null;
    }

    private boolean matchesParamName(
            String params,
            int start,
            int end,
            boolean plusAsSpace,
            String wanted
    ) {
        if (regionEquals(params, start, end, wanted)) {
            return true;
        }

        boolean encoded = false;
        for (int i = start; i < end; i++) {
            char ch = params.charAt(i);
            if (ch == '%' || (plusAsSpace && ch == '+')) {
                encoded = true;
                break;
            }
        }
        return encoded && wanted.equals(UrlCodec.decodeComponent(params.substring(start, end), plusAsSpace));
    }

    private String matchWantedHeaderName(String headers, int start, int end, String[] wantedNames) {
        if (start >= end) {
            return null;
        }
        if (wantedNames == null || wantedNames.length == 0) {
            return headers.substring(start, end).toLowerCase(Locale.ROOT);
        }
        for (String wanted : wantedNames) {
            if (regionEqualsIgnoreCase(headers, start, end, wanted)) {
                return wanted;
            }
        }
        return null;
    }

    private static boolean regionEquals(String value, int start, int end, String expected) {
        int len = end - start;
        if (expected == null || expected.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (value.charAt(start + i) != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean regionEqualsIgnoreCase(String value, int start, int end, String expected) {
        int len = end - start;
        if (expected == null || expected.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char a = value.charAt(start + i);
            char b = expected.charAt(i);
            if (a == b) {
                continue;
            }
            if (Character.toLowerCase(a) != b) {
                return false;
            }
        }
        return true;
    }

    private static int trimLeft(String value, int start, int end) {
        int i = start;
        while (i < end) {
            char ch = value.charAt(i);
            if (ch != ' ' && ch != '\t') {
                break;
            }
            i++;
        }
        return i;
    }

    private static int trimRight(String value, int start, int end) {
        int i = end;
        while (i > start) {
            char ch = value.charAt(i - 1);
            if (ch != ' ' && ch != '\t' && ch != '\r') {
                break;
            }
            i--;
        }
        return i;
    }

    private Object invokeAnnotatedHandle(
            HandlerDescriptor desc,
            byte[] body,
            RequestValueMap params,
            RequestValueMap headers
    ) throws Throwable {
        return desc.compiledInvoker.invoke(body, params, headers);
    }

    private Object invokeAnnotatedHandleDirect(
            HandlerDescriptor desc,
            ByteBuffer body,
            int bodyLen,
            RequestValueMap params,
            RequestValueMap headers
    ) throws Throwable {
        return desc.compiledInvoker.invokeDirect(body, bodyLen, params, headers);
    }

    private int safeBodyLength(ByteBuffer body, int length) {
        if (body == null || length <= 0) {
            return 0;
        }
        return Math.min(length, body.capacity());
    }

    private ByteBuffer duplicateBody(ByteBuffer body, int length) {
        if (body == null || length <= 0) {
            return null;
        }

        ByteBuffer duplicate = body.duplicate();
        duplicate.position(0);
        duplicate.limit(safeBodyLength(body, length));
        return duplicate;
    }

    private byte[] toByteArray(ByteBuffer body, int length) {
        if (body == null || length <= 0) {
            return EMPTY_BYTES;
        }

        ByteBuffer duplicate = body.duplicate();
        duplicate.position(0);
        int safeLength = Math.min(length, duplicate.capacity());
        duplicate.limit(safeLength);
        byte[] bytes = new byte[safeLength];
        duplicate.get(bytes);
        return bytes;
    }

    private String findCookieValue(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isEmpty() || name == null || name.isEmpty()) {
            return null;
        }

        int start = 0;
        int len = cookieHeader.length();
        while (start < len) {
            while (start < len && (cookieHeader.charAt(start) == ' ' || cookieHeader.charAt(start) == '\t'
                    || cookieHeader.charAt(start) == ';')) {
                start++;
            }
            if (start >= len) {
                break;
            }
            int end = cookieHeader.indexOf(';', start);
            if (end < 0) {
                end = len;
            }
            int eqIdx = cookieHeader.indexOf('=', start);
            if (eqIdx > start && eqIdx < end) {
                String cookieName = UrlCodec.decodeComponent(cookieHeader.substring(start, eqIdx).trim(), false);
                if (name.equals(cookieName)) {
                    return UrlCodec.decodeComponent(cookieHeader.substring(eqIdx + 1, end).trim(), false);
                }
            }
            start = end + 1;
        }
        return null;
    }

    /**
     * Write ResponseEntity to buffer.
     */
    private int writeResponseEntity(
            ResponseEntity<?> responseEntity,
            byte[] defaultContentTypeHeader,
            ByteBuffer out,
            int offset
    ) {
        int statusCode = responseEntity.getStatus() != null
                ? responseEntity.getStatus().getCode()
                : 200;
        Object body = responseEntity.getBody();
        byte[] headerBytes = body != null && !(body instanceof FileResponse) && !(body instanceof RawResponse)
                ? encodeHeadersWithDefaultContentType(responseEntity.getHeaders(), defaultContentTypeHeader)
                : encodeHeaders(responseEntity.getHeaders());
        int frameAndHeadersSize = RESPONSE_FRAME_HEADER_SIZE + headerBytes.length;

        if (body == null) {
            return writeFrameWithBytes(statusCode, headerBytes, EMPTY_BYTES, out, offset);
        }

        if (body instanceof FileResponse fileResponse) {
            return writeFileResponse(fileResponse, statusCode, headerBytes, out, offset);
        }

        if (body instanceof RawResponse rawResponse) {
            return writeRawResponse(rawResponse, statusCode, headerBytes, out, offset);
        }

        if (body instanceof DirectJsonResponse<?> directJsonResponse) {
            return writeDirectJsonResponse(directJsonResponse, statusCode, headerBytes, out, offset);
        }

        if (body instanceof JsonProducerResponse producerResponse) {
            return writeJsonProducerResponse(producerResponse, statusCode, headerBytes, out, offset);
        }

        if (body instanceof JsonBodyProducer producer) {
            return writeJsonBodyProducer(producer, statusCode, headerBytes, descDefaultContentType(defaultContentTypeHeader), out, offset);
        }

        int bodyOffset = offset + frameAndHeadersSize;
        int bodyLen = DslJsonService.writeToBuffer(body, out, bodyOffset);
        if (bodyLen < 0) {
            return -(frameAndHeadersSize + -bodyLen);
        }

        writeFrameHeader(statusCode, headerBytes, bodyLen, out, offset);
        return frameAndHeadersSize + bodyLen;
    }

    /**
     * Write error response to buffer.
     */
    private int writeError(ByteBuffer out, int offset, String internalMessage) {
        return writeError(out, offset, new IllegalStateException(internalMessage));
    }

    private int writeError(ByteBuffer out, int offset, Throwable error) {
        HttpErrorMapper.MappedError mapped = HttpErrorMapper.map(error);
        return writeFrameWithBytes(
                mapped.status(),
                DEFAULT_JSON_CONTENT_TYPE_HEADER,
                HttpErrorMapper.toJsonBytes(mapped),
                out,
                offset
        );
    }

    private int writeObjectFrame(
            int statusCode,
            Object body,
            byte[] headerBytes,
            ByteBuffer out,
            int offset
    ) {
        byte[] safeHeaderBytes = headerBytes != null ? headerBytes : DEFAULT_JSON_CONTENT_TYPE_HEADER;
        int bodyLen = 0;
        if (body != null) {
            bodyLen = DslJsonService.writeToBuffer(
                    body,
                    out,
                    offset + RESPONSE_FRAME_HEADER_SIZE + safeHeaderBytes.length
            );
            if (bodyLen < 0) {
                return -(RESPONSE_FRAME_HEADER_SIZE + safeHeaderBytes.length + -bodyLen);
            }
        }
        writeFrameHeader(statusCode, safeHeaderBytes, bodyLen, out, offset);
        return RESPONSE_FRAME_HEADER_SIZE + safeHeaderBytes.length + bodyLen;
    }

    private int writeDirectJsonResponse(
            DirectJsonResponse<?> response,
            int statusCode,
            byte[] entityHeaderBytes,
            ByteBuffer out,
            int offset
    ) {
        byte[] directHeaderBytes = entityHeaderBytes.length == 0
                ? response.getEncodedHeadersWithDefaultJson()
                : response.getEncodedHeaders();
        int headersLen = entityHeaderBytes.length + directHeaderBytes.length;
        int frameAndHeadersSize = RESPONSE_FRAME_HEADER_SIZE + headersLen;
        int bodyOffset = offset + frameAndHeadersSize;
        int bodyLen = response.writeBody(out, bodyOffset);
        if (bodyLen < 0) {
            return -(frameAndHeadersSize + -bodyLen);
        }
        int totalSize = frameAndHeadersSize + bodyLen;
        if (totalSize > out.capacity() - offset) {
            return -totalSize;
        }

        out.position(offset);
        out.put(RESPONSE_FRAME_MAGIC);
        out.putShort((short) statusCode);
        out.putInt(headersLen);
        out.putInt(bodyLen);
        if (entityHeaderBytes.length > 0) {
            out.put(entityHeaderBytes);
        }
        if (directHeaderBytes.length > 0) {
            out.put(directHeaderBytes);
        }
        return totalSize;
    }

    private int writeJsonProducerResponse(
            JsonProducerResponse response,
            int statusCode,
            byte[] entityHeaderBytes,
            ByteBuffer out,
            int offset
    ) {
        byte[] producerHeaderBytes = entityHeaderBytes.length == 0
                ? response.getEncodedHeadersWithDefaultJson()
                : response.getEncodedHeaders();
        int headersLen = entityHeaderBytes.length + producerHeaderBytes.length;
        int frameAndHeadersSize = RESPONSE_FRAME_HEADER_SIZE + headersLen;
        int bodyOffset = offset + frameAndHeadersSize;
        int bodyLen = response.writeBody(out, bodyOffset);
        if (bodyLen < 0) {
            return -(frameAndHeadersSize + -bodyLen);
        }
        int totalSize = frameAndHeadersSize + bodyLen;
        if (totalSize > out.capacity() - offset) {
            return -totalSize;
        }

        out.position(offset);
        out.put(RESPONSE_FRAME_MAGIC);
        out.putShort((short) statusCode);
        out.putInt(headersLen);
        out.putInt(bodyLen);
        if (entityHeaderBytes.length > 0) {
            out.put(entityHeaderBytes);
        }
        if (producerHeaderBytes.length > 0) {
            out.put(producerHeaderBytes);
        }
        return totalSize;
    }

    private int writeJsonBodyProducer(
            JsonBodyProducer producer,
            int statusCode,
            byte[] headerBytes,
            byte[] defaultContentTypeHeader,
            ByteBuffer out,
            int offset
    ) {
        byte[] safeHeaderBytes = headerBytes.length == 0
                ? defaultContentTypeHeader
                : headerBytes;
        int frameAndHeadersSize = RESPONSE_FRAME_HEADER_SIZE + safeHeaderBytes.length;
        int bodyOffset = offset + frameAndHeadersSize;
        int bodyLen = producer.write(out, bodyOffset);
        if (bodyLen < 0) {
            return -(frameAndHeadersSize + -bodyLen);
        }
        int totalSize = frameAndHeadersSize + bodyLen;
        if (totalSize > out.capacity() - offset) {
            return -totalSize;
        }

        writeFrameHeader(statusCode, safeHeaderBytes, bodyLen, out, offset);
        return totalSize;
    }

    private byte[] descDefaultContentType(byte[] defaultContentTypeHeader) {
        return defaultContentTypeHeader != null ? defaultContentTypeHeader : DEFAULT_JSON_CONTENT_TYPE_HEADER;
    }

    private int writeFrameWithBytes(
            int statusCode,
            byte[] headerBytes,
            byte[] bodyBytes,
            ByteBuffer out,
            int offset
    ) {
        int totalSize = RESPONSE_FRAME_HEADER_SIZE + headerBytes.length + bodyBytes.length;
        if (totalSize > out.capacity() - offset) {
            return -totalSize;
        }

        writeFrameHeader(statusCode, headerBytes, bodyBytes.length, out, offset);
        out.position(offset + RESPONSE_FRAME_HEADER_SIZE + headerBytes.length);
        out.put(bodyBytes);
        return totalSize;
    }

    private int writeRawResponse(
            RawResponse rawResponse,
            int statusCode,
            byte[] entityHeaderBytes,
            ByteBuffer out,
            int offset
    ) {
        if (rawResponse.getNativeId() > 0) {
            return writeStaticResponseFrame(
                    rawResponse.getNativeId(),
                    statusCode,
                    entityHeaderBytes,
                    rawResponse.getEncodedHeaders(),
                    out,
                    offset
            );
        }

        byte[] rawHeaderBytes = rawResponse.getEncodedHeaders();
        byte[] bodyBytes = rawResponse.getBody();
        int headersLen = entityHeaderBytes.length + rawHeaderBytes.length;
        int totalSize = RESPONSE_FRAME_HEADER_SIZE + headersLen + bodyBytes.length;
        if (totalSize > out.capacity() - offset) {
            return -totalSize;
        }

        out.position(offset);
        out.put(RESPONSE_FRAME_MAGIC);
        out.putShort((short) statusCode);
        out.putInt(headersLen);
        out.putInt(bodyBytes.length);
        if (entityHeaderBytes.length > 0) {
            out.put(entityHeaderBytes);
        }
        if (rawHeaderBytes.length > 0) {
            out.put(rawHeaderBytes);
        }
        if (bodyBytes.length > 0) {
            out.put(bodyBytes);
        }
        return totalSize;
    }

    private int writeStaticResponseFrame(
            int nativeId,
            int statusCode,
            byte[] entityHeaderBytes,
            byte[] rawHeaderBytes,
            ByteBuffer out,
            int offset
    ) {
        int headersLen = entityHeaderBytes.length + rawHeaderBytes.length;
        int totalSize = RESPONSE_FRAME_HEADER_SIZE + headersLen;
        if (totalSize > out.capacity() - offset) {
            return -totalSize;
        }

        out.position(offset);
        out.put(STATIC_RESPONSE_FRAME_MAGIC);
        out.putShort((short) statusCode);
        out.putInt(headersLen);
        out.putInt(nativeId);
        if (entityHeaderBytes.length > 0) {
            out.put(entityHeaderBytes);
        }
        if (rawHeaderBytes.length > 0) {
            out.put(rawHeaderBytes);
        }
        return totalSize;
    }

    private int writeFileResponse(
            FileResponse fileResponse,
            int statusCode,
            byte[] entityHeaderBytes,
            ByteBuffer out,
            int offset
    ) {
        byte[] fileHeaderBytes = fileResponse.getEncodedHeaders();
        byte[] pathBytes = fileResponse.getAbsolutePath().getBytes(StandardCharsets.UTF_8);
        if (pathBytes.length == 0 || pathBytes.length > MAX_FILE_RESPONSE_PATH_BYTES) {
            return writeError(out, offset, "Invalid file response path");
        }

        int headersLen = entityHeaderBytes.length + fileHeaderBytes.length;
        int totalSize = RESPONSE_FRAME_HEADER_SIZE + headersLen + pathBytes.length;
        if (totalSize > out.capacity() - offset) {
            return -totalSize;
        }

        out.position(offset);
        out.put(FILE_RESPONSE_FRAME_MAGIC);
        out.putShort((short) statusCode);
        out.putInt(headersLen);
        out.putInt(pathBytes.length);
        if (entityHeaderBytes.length > 0) {
            out.put(entityHeaderBytes);
        }
        if (fileHeaderBytes.length > 0) {
            out.put(fileHeaderBytes);
        }
        out.put(pathBytes);
        return totalSize;
    }

    private void writeFrameHeader(
            int statusCode,
            byte[] headerBytes,
            int bodyLen,
            ByteBuffer out,
            int offset
    ) {
        out.position(offset);
        out.put(RESPONSE_FRAME_MAGIC);
        out.putShort((short) statusCode);
        out.putInt(headerBytes.length);
        out.putInt(bodyLen);
        if (headerBytes.length > 0) {
            out.put(headerBytes);
        }
    }

    private byte[] encodeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return EMPTY_BYTES;
        }

        StringBuilder sb = new StringBuilder(headers.size() * 32);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String value = "Content-Type".equalsIgnoreCase(entry.getKey())
                    ? normalizeTextualContentType(entry.getValue())
                    : entry.getValue();
            sb.append(entry.getKey()).append(": ").append(value).append('\n');
        }

        if (sb.length() == 0) {
            return EMPTY_BYTES;
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] encodeHeadersWithDefaultContentType(
            Map<String, String> headers,
            byte[] defaultContentTypeHeader
    ) {
        byte[] headerBytes = encodeHeaders(headers);
        if (hasContentType(headers)) {
            return headerBytes;
        }
        byte[] defaultHeader = defaultContentTypeHeader != null
                ? defaultContentTypeHeader
                : DEFAULT_JSON_CONTENT_TYPE_HEADER;
        if (headerBytes.length == 0) {
            return defaultHeader;
        }
        byte[] merged = new byte[defaultHeader.length + headerBytes.length];
        System.arraycopy(defaultHeader, 0, merged, 0, defaultHeader.length);
        System.arraycopy(headerBytes, 0, merged, defaultHeader.length, headerBytes.length);
        return merged;
    }

    private boolean hasContentType(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        for (String key : headers.keySet()) {
            if ("Content-Type".equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeTextualContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return contentType;
        }
        String value = contentType.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("charset=")) {
            return value;
        }
        if (lower.startsWith("text/")
                || lower.startsWith("application/json")
                || lower.contains("+json")) {
            return value + "; charset=utf-8";
        }
        return value;
    }

    // ========================================
    // ASYNC HANDLER SUPPORT (CompletableFuture)
    // ========================================

    public CompletableFuture<byte[]> invokeAsync(
            int handlerId,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) {
        return invokeAsyncFrame(handlerId, inBytes, pathParams, queryString, headers)
                .thenApply(frame -> {
                    try {
                        return frame.toByteArray();
                    } finally {
                        releaseAsyncResponseFrame(frame);
                    }
                });
    }

    public CompletableFuture<AsyncResponseFrame> invokeAsyncFrame(
            int handlerId,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) {
        HandlerDescriptor desc = handlers.get(handlerId);

        if (desc == null) {
            return CompletableFuture.completedFuture(
                    encodeAsyncErrorFrame(new IllegalArgumentException("Unknown handlerId"))
            );
        }
        desc.recordInvocation();

        if (desc.isAsync) {
            try {
                Object raw = invokeAsyncRaw(desc, inBytes, pathParams, queryString, headers);
                return encodeAsyncRawResult(desc, raw);
            } catch (Throwable e) {
                return CompletableFuture.completedFuture(encodeAsyncErrorFrame(e));
            }
        }

        return AsyncHandlerExecutor.getInstance().submit(() -> {
            ByteBuffer buffer = ASYNC_FRAME_POOL.acquire(INITIAL_ASYNC_FRAME_BYTES);
            try {
                buffer.clear();

                int written;
                if (desc.usesAnnotatedParams) {
                    written = invokeAnnotatedFast(desc, buffer, 0, inBytes, pathParams, queryString, headers);
                } else {
                    written = invokeV4Async(desc, buffer, 0, inBytes, pathParams, queryString, headers);
                }

                return new AsyncResponseFrame(buffer, written);

            } catch (Throwable e) {
                ASYNC_FRAME_POOL.release(buffer);
                return encodeAsyncErrorFrame(e);
            }
        });
    }

    public CompletableFuture<AsyncResponseFrame> invokeAsyncFrameQueryInt(
            int handlerId,
            int queryInt
    ) {
        HandlerDescriptor desc = handlers.get(handlerId);

        if (desc == null) {
            return CompletableFuture.completedFuture(
                    encodeAsyncErrorFrame(new IllegalArgumentException("Unknown handlerId"))
            );
        }
        if (!desc.isAsync || !desc.usesDirectQueryInt || !desc.usesDirectScalarInt) {
            return CompletableFuture.completedFuture(
                    encodeAsyncErrorFrame(new IllegalArgumentException("Handler does not support async direct query int"))
            );
        }
        desc.recordInvocation();

        try {
            Object raw = desc.handle.invoke(queryInt);
            return encodeAsyncRawResult(desc, raw);
        } catch (Throwable e) {
            return CompletableFuture.completedFuture(encodeAsyncErrorFrame(e));
        }
    }

    private CompletableFuture<AsyncResponseFrame> encodeAsyncRawResult(HandlerDescriptor desc, Object raw) {
        if (raw instanceof CompletionStage<?> stage) {
            return stage.toCompletableFuture().handle((result, error) -> error == null
                    ? encodeAsyncResultFrame(desc, result)
                    : encodeAsyncErrorFrame(error));
        }
        return CompletableFuture.completedFuture(encodeAsyncResultFrame(desc, raw));
    }

    private Object invokeAsyncRaw(
            HandlerDescriptor desc,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) throws Throwable {
        if (desc.usesAnnotatedParams) {
            if (desc.compiledInvoker.arity() == 0 && !desc.metadata.needsBody) {
                return desc.compiledInvoker.invoke(EMPTY_BYTES, null, null);
            }
            Object singleValueResult = tryInvokeSingleRawValue(desc, pathParams, queryString, headers);
            if (singleValueResult != SINGLE_VALUE_FAST_PATH_MISS) {
                return singleValueResult;
            }
            RequestValueMap paramMap = PARAM_MAP_POOL.get();
            RequestValueMap headerMap = HEADER_MAP_POOL.get();
            try {
                paramMap.clear();
                headerMap.clear();
                if (desc.metadata.needsPathParams) {
                    parseParamsFast(paramMap, pathParams, false, desc.metadata.pathParamNames);
                }
                if (desc.metadata.needsQueryParams) {
                    parseParamsFast(paramMap, queryString, true, desc.metadata.queryParamNames);
                }
                if (desc.metadata.needsHeaders) {
                    parseHeadersFast(headerMap, headers, desc.metadata.headerNames);
                }
                return invokeAnnotatedHandle(desc, inBytes, paramMap, headerMap);
            } finally {
                paramMap.clear();
                headerMap.clear();
            }
        }

        throw new IllegalStateException("Async handler has no safe non-borrowing invocation plan: " + desc.method);
    }

    private AsyncResponseFrame encodeAsyncResultFrame(HandlerDescriptor desc, Object result) {
        ByteBuffer buffer = ASYNC_FRAME_POOL.acquire(INITIAL_ASYNC_FRAME_BYTES);
        try {
            if (result instanceof Integer written) {
                if (written < 0) {
                    throw new IllegalStateException("async direct response returned required size without retry: " + -written);
                }
                return new AsyncResponseFrame(buffer, written);
            }
            for (int attempt = 0; attempt < 3; attempt++) {
                buffer.clear();
                int written = processAsyncResult(desc, result, buffer, 0);
                if (written >= 0) {
                    return new AsyncResponseFrame(buffer, written);
                }
                int required = -written;
                if (required <= 0 || required > MAX_ASYNC_RESPONSE_FRAME_BYTES) {
                    throw new IllegalStateException("async response frame too large: " + required);
                }
                buffer = ASYNC_FRAME_POOL.grow(buffer, required);
            }
            throw new IllegalStateException("async response frame retry exceeded");
        } catch (Throwable e) {
            ASYNC_FRAME_POOL.release(buffer);
            return encodeAsyncErrorFrame(e);
        }
    }

    private AsyncResponseFrame encodeAsyncErrorFrame(Throwable e) {
        if (DEBUG) {
            FrameworkLogger.debugError(
                    "[HandlerRegistry] Async error: " + e.getClass().getName(),
                    e);
        }
        HttpErrorMapper.MappedError mapped = HttpErrorMapper.map(e);
        ByteBuffer buffer = ASYNC_FRAME_POOL.acquire(INITIAL_ASYNC_FRAME_BYTES);
        buffer.clear();
        byte[] body = HttpErrorMapper.toJsonBytes(mapped);
        int written = writeFrameWithBytes(mapped.status(), DEFAULT_JSON_CONTENT_TYPE_HEADER, body, buffer, 0);
        return new AsyncResponseFrame(buffer, written);
    }

    @SuppressWarnings("unchecked")
    private int invokeV4Async(
            HandlerDescriptor desc,
            ByteBuffer out,
            int offset,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) throws Throwable {

        Object result = desc.handle.invoke(out, offset, inBytes, pathParams, queryString, headers);

        if (result instanceof CompletableFuture<?> future) {
            Object asyncResult = future.join();
            return processAsyncResult(desc, asyncResult, out, offset);
        }

        return processAsyncResult(desc, result, out, offset);
    }

    /**
     * Process async result - handle different return types.
     */
    private int processAsyncResult(HandlerDescriptor desc, Object result, ByteBuffer out, int offset) {
        if (result instanceof Integer) {
            return (Integer) result;
        }

        if (result instanceof FileResponse fileResponse) {
            return writeFileResponse(fileResponse, 200, EMPTY_BYTES, out, offset);
        }

        if (result instanceof RawResponse rawResponse) {
            return writeRawResponse(rawResponse, 200, EMPTY_BYTES, out, offset);
        }

        if (result instanceof DirectJsonResponse<?> directJsonResponse) {
            return writeDirectJsonResponse(
                    directJsonResponse,
                    directJsonResponse.getStatusCode(),
                    EMPTY_BYTES,
                    out,
                    offset
            );
        }

        if (result instanceof JsonProducerResponse producerResponse) {
            return writeJsonProducerResponse(
                    producerResponse,
                    producerResponse.getStatusCode(),
                    EMPTY_BYTES,
                    out,
                    offset
            );
        }

        if (result instanceof JsonBodyProducer producer) {
            return writeJsonBodyProducer(producer, 200, EMPTY_BYTES, desc.defaultContentTypeHeader, out, offset);
        }

        if (result instanceof ResponseEntity<?> responseEntity) {
            return writeResponseEntity(responseEntity, desc.defaultContentTypeHeader, out, offset);
        }

        if (result != null) {
            return writeObjectFrame(200, result, desc.defaultContentTypeHeader, out, offset);
        }

        return 0;
    }
}

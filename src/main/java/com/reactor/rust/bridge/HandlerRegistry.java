package com.reactor.rust.bridge;

import com.reactor.rust.annotations.ResponseStatus;
import com.reactor.rust.annotations.ContentType;
import com.reactor.rust.async.AsyncHandlerExecutor;
import com.reactor.rust.http.FileResponse;
import com.reactor.rust.http.MediaType;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.json.DslJsonService;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.util.FastMapV2;
import com.reactor.rust.util.UrlCodec;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
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

/**
 * Optimized handler registry with:
 * - MethodMetadata cache (zero runtime annotation lookup)
 * - FastMapV2 for parameter resolution (O(1) lookup)
 * - ThreadLocal ByteBuffer pool
 * - Exact MethodHandle invocation for common signatures
 */
public class HandlerRegistry {

    private static final HandlerRegistry INSTANCE = new HandlerRegistry();

    // ThreadLocal ByteBuffer pool for async handlers (64KB buffers)
    private static final ThreadLocal<ByteBuffer> ASYNC_BUFFER_POOL =
        ThreadLocal.withInitial(() -> ByteBuffer.allocate(64 * 1024));

    // ThreadLocal FastMapV2 pools for zero-allocation parameter parsing
    private static final ThreadLocal<FastMapV2> PARAM_MAP_POOL =
        ThreadLocal.withInitial(FastMapV2::new);
    private static final ThreadLocal<FastMapV2> HEADER_MAP_POOL =
        ThreadLocal.withInitial(FastMapV2::new);

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
    private static final byte[] ERROR_PREFIX = "{\"error\":\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ERROR_SUFFIX = "\"}".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_EXACT_ANNOTATED_PARAMS = 8;
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
        public final boolean returnsResponseEntity;
        public final boolean isAsync;
        public final int customResponseStatus;
        public final byte[] defaultContentTypeHeader;

        // Cached metadata for fast parameter resolution
        public final MethodMetadata metadata;

        public HandlerDescriptor(Object bean,
                Method method,
                Class<?> requestType,
                Class<?> responseType,
                MethodHandle handle,
                boolean usesAnnotatedParams,
                boolean usesDirectBodyBuffer,
                boolean usesDirectQueryInt,
                boolean returnsResponseEntity,
                boolean isAsync,
                int customResponseStatus,
                byte[] defaultContentTypeHeader) {
            this.bean = bean;
            this.method = method;
            this.requestType = requestType;
            this.responseType = responseType;
            this.handle = handle;
            this.usesAnnotatedParams = usesAnnotatedParams;
            this.usesDirectBodyBuffer = usesDirectBodyBuffer;
            this.usesDirectQueryInt = usesDirectQueryInt;
            this.returnsResponseEntity = returnsResponseEntity;
            this.isAsync = isAsync;
            this.customResponseStatus = customResponseStatus;
            this.defaultContentTypeHeader =
                    defaultContentTypeHeader != null ? defaultContentTypeHeader : DEFAULT_JSON_CONTENT_TYPE_HEADER;
            this.metadata = MethodMetadata.getOrCreate(method, requestType, responseType);
        }

        // Legacy constructor for backwards compatibility
        public HandlerDescriptor(Object bean,
                Method method,
                Class<?> requestType,
                Class<?> responseType,
                MethodHandle handle) {
            this(bean, method, requestType, responseType, handle, false, false, false, false, false, 200,
                    DEFAULT_JSON_CONTENT_TYPE_HEADER);
        }
    }

    private HandlerRegistry() {}

    public List<Object> getHandlers() {
        return handlerBeans;
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
            boolean directQueryInt = isDirectQueryInt(method);

            // Modern handlers may be no-arg, annotated, or return ResponseEntity.
            boolean usesAnnotatedParams = !legacyV4
                    && !directV5
                    && !directQueryInt
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
                usesAnnotatedParams, directV5, directQueryInt, returnsResponseEntity, isAsync, customResponseStatus,
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

    private static boolean isDirectQueryInt(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 3
                && parameterTypes[0] == ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == int.class;
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

        try {
            // Choose invocation strategy based on method signature
            if (desc.usesAnnotatedParams) {
                return invokeAnnotatedFast(desc, out, offset, inBytes, pathParams, queryString, headers);
            } else if (desc.usesDirectBodyBuffer) {
                return invokeV5Direct(desc, out, offset, null, 0,
                        pathParams, queryString, headers);
            } else {
                return invokeV4(desc, out, offset, inBytes, pathParams, queryString, headers);
            }

        } catch (Throwable e) {
            return writeError(out, offset, e.getMessage());
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

        try {
            if (desc.usesAnnotatedParams) {
                return invokeAnnotatedFastDirect(desc, out, offset, inBuffer, inLength, pathParams, queryString, headers);
            }
            if (desc.usesDirectBodyBuffer) {
                return invokeV5Direct(desc, out, offset, inBuffer, inLength, pathParams, queryString, headers);
            }

            return invokeV4(desc, out, offset, toByteArray(inBuffer, inLength), pathParams, queryString, headers);
        } catch (Throwable e) {
            return writeError(out, offset, e.getMessage());
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

        try {
            return processDirectResult(desc, desc.handle.invoke(out, offset, queryInt), out, offset);
        } catch (Throwable e) {
            return writeError(out, offset, e.getMessage());
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
     * Fast annotated invocation using FastMapV2 for O(1) parameter lookup.
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

        // Use ThreadLocal FastMapV2 pools - O(1) lookup, zero allocation
        FastMapV2 paramMap = PARAM_MAP_POOL.get();
        FastMapV2 headerMap = HEADER_MAP_POOL.get();

        try {
            paramMap.clear();
            headerMap.clear();

            // Parse only what the method actually consumes.
            if (desc.metadata.needsPathParams) {
                parseParamsFast(paramMap, pathParams, false);
            }
            if (desc.metadata.needsQueryParams) {
                parseParamsFast(paramMap, queryString, true);
            }
            if (desc.metadata.needsHeaders) {
                parseHeadersFast(headerMap, headers);
            }

            Object result = invokeAnnotatedHandle(desc, inBytes, paramMap, headerMap);

            // Handle different return types
            if (result instanceof Integer) {
                return (Integer) result;
            }

            if (result instanceof FileResponse fileResponse) {
                return writeFileResponse(fileResponse, 200, EMPTY_BYTES, out, offset);
            }

            if (result instanceof RawResponse rawResponse) {
                return writeRawResponse(rawResponse, 200, EMPTY_BYTES, out, offset);
            }

            if (result instanceof ResponseEntity<?> responseEntity) {
                return writeResponseEntity(responseEntity, desc.defaultContentTypeHeader, out, offset);
            }

            // Auto-serialize response object
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

        FastMapV2 paramMap = PARAM_MAP_POOL.get();
        FastMapV2 headerMap = HEADER_MAP_POOL.get();

        try {
            paramMap.clear();
            headerMap.clear();

            if (desc.metadata.needsPathParams) {
                parseParamsFast(paramMap, pathParams, false);
            }
            if (desc.metadata.needsQueryParams) {
                parseParamsFast(paramMap, queryString, true);
            }
            if (desc.metadata.needsHeaders) {
                parseHeadersFast(headerMap, headers);
            }

            Object result = invokeAnnotatedHandleDirect(desc, inBuffer, inLength, paramMap, headerMap);

            if (result instanceof Integer) {
                return (Integer) result;
            }

            if (result instanceof FileResponse fileResponse) {
                return writeFileResponse(fileResponse, 200, EMPTY_BYTES, out, offset);
            }

            if (result instanceof RawResponse rawResponse) {
                return writeRawResponse(rawResponse, 200, EMPTY_BYTES, out, offset);
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

        } finally {
            paramMap.clear();
            headerMap.clear();
        }
    }

    /**
     * Fast parameter parsing into FastMapV2.
     */
    private void parseParamsFast(FastMapV2 map, String params, boolean plusAsSpace) {
        if (params == null || params.isEmpty()) return;

        int start = 0;
        int len = params.length();

        for (int i = 0; i <= len; i++) {
            if (i == len || params.charAt(i) == '&') {
                if (i > start) {
                    int eqIdx = params.indexOf('=', start);
                    if (eqIdx > start && eqIdx < i) {
                        String key = UrlCodec.decodeComponent(params.substring(start, eqIdx), plusAsSpace);
                        String value = UrlCodec.decodeComponent(params.substring(eqIdx + 1, i), plusAsSpace);
                        map.put(key, value);
                    }
                }
                start = i + 1;
            }
        }
    }

    /**
     * Fast header parsing into FastMapV2.
     */
    private void parseHeadersFast(FastMapV2 map, String headers) {
        if (headers == null || headers.isEmpty()) return;

        int start = 0;
        int len = headers.length();

        for (int i = 0; i <= len; i++) {
            if (i == len || headers.charAt(i) == '\n') {
                if (i > start) {
                    int colonIdx = headers.indexOf(':', start);
                    if (colonIdx > start && colonIdx < i) {
                        String key = headers.substring(start, colonIdx).trim().toLowerCase(Locale.ROOT);
                        String value = headers.substring(colonIdx + 1, i).trim();
                        if (!key.isEmpty()) {
                            map.put(key, value);
                        }
                    }
                }
                start = i + 1;
            }
        }
    }

    private Object invokeAnnotatedHandle(
            HandlerDescriptor desc,
            byte[] body,
            FastMapV2 params,
            FastMapV2 headers
    ) throws Throwable {
        MethodMetadata.ParamInfo[] infos = desc.metadata.paramInfos;

        return switch (infos.length) {
            case 0 -> desc.handle.invoke();
            case 1 -> desc.handle.invoke(resolveArgumentFast(infos[0], body, params, headers));
            case 2 -> desc.handle.invoke(
                    resolveArgumentFast(infos[0], body, params, headers),
                    resolveArgumentFast(infos[1], body, params, headers)
            );
            case 3 -> desc.handle.invoke(
                    resolveArgumentFast(infos[0], body, params, headers),
                    resolveArgumentFast(infos[1], body, params, headers),
                    resolveArgumentFast(infos[2], body, params, headers)
            );
            case 4 -> desc.handle.invoke(
                    resolveArgumentFast(infos[0], body, params, headers),
                    resolveArgumentFast(infos[1], body, params, headers),
                    resolveArgumentFast(infos[2], body, params, headers),
                    resolveArgumentFast(infos[3], body, params, headers)
            );
            case 5 -> desc.handle.invoke(
                    resolveArgumentFast(infos[0], body, params, headers),
                    resolveArgumentFast(infos[1], body, params, headers),
                    resolveArgumentFast(infos[2], body, params, headers),
                    resolveArgumentFast(infos[3], body, params, headers),
                    resolveArgumentFast(infos[4], body, params, headers)
            );
            case 6 -> desc.handle.invoke(
                    resolveArgumentFast(infos[0], body, params, headers),
                    resolveArgumentFast(infos[1], body, params, headers),
                    resolveArgumentFast(infos[2], body, params, headers),
                    resolveArgumentFast(infos[3], body, params, headers),
                    resolveArgumentFast(infos[4], body, params, headers),
                    resolveArgumentFast(infos[5], body, params, headers)
            );
            case 7 -> desc.handle.invoke(
                    resolveArgumentFast(infos[0], body, params, headers),
                    resolveArgumentFast(infos[1], body, params, headers),
                    resolveArgumentFast(infos[2], body, params, headers),
                    resolveArgumentFast(infos[3], body, params, headers),
                    resolveArgumentFast(infos[4], body, params, headers),
                    resolveArgumentFast(infos[5], body, params, headers),
                    resolveArgumentFast(infos[6], body, params, headers)
            );
            case 8 -> desc.handle.invoke(
                    resolveArgumentFast(infos[0], body, params, headers),
                    resolveArgumentFast(infos[1], body, params, headers),
                    resolveArgumentFast(infos[2], body, params, headers),
                    resolveArgumentFast(infos[3], body, params, headers),
                    resolveArgumentFast(infos[4], body, params, headers),
                    resolveArgumentFast(infos[5], body, params, headers),
                    resolveArgumentFast(infos[6], body, params, headers),
                    resolveArgumentFast(infos[7], body, params, headers)
            );
            default -> throw new IllegalStateException("Unsupported annotated parameter count: " + infos.length);
        };
    }

    private Object invokeAnnotatedHandleDirect(
            HandlerDescriptor desc,
            ByteBuffer body,
            int bodyLen,
            FastMapV2 params,
            FastMapV2 headers
    ) throws Throwable {
        MethodMetadata.ParamInfo[] infos = desc.metadata.paramInfos;

        return switch (infos.length) {
            case 0 -> desc.handle.invoke();
            case 1 -> desc.handle.invoke(resolveArgumentFastDirect(infos[0], body, bodyLen, params, headers));
            case 2 -> desc.handle.invoke(
                    resolveArgumentFastDirect(infos[0], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[1], body, bodyLen, params, headers)
            );
            case 3 -> desc.handle.invoke(
                    resolveArgumentFastDirect(infos[0], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[1], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[2], body, bodyLen, params, headers)
            );
            case 4 -> desc.handle.invoke(
                    resolveArgumentFastDirect(infos[0], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[1], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[2], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[3], body, bodyLen, params, headers)
            );
            case 5 -> desc.handle.invoke(
                    resolveArgumentFastDirect(infos[0], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[1], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[2], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[3], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[4], body, bodyLen, params, headers)
            );
            case 6 -> desc.handle.invoke(
                    resolveArgumentFastDirect(infos[0], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[1], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[2], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[3], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[4], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[5], body, bodyLen, params, headers)
            );
            case 7 -> desc.handle.invoke(
                    resolveArgumentFastDirect(infos[0], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[1], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[2], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[3], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[4], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[5], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[6], body, bodyLen, params, headers)
            );
            case 8 -> desc.handle.invoke(
                    resolveArgumentFastDirect(infos[0], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[1], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[2], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[3], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[4], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[5], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[6], body, bodyLen, params, headers),
                    resolveArgumentFastDirect(infos[7], body, bodyLen, params, headers)
            );
            default -> throw new IllegalStateException("Unsupported annotated parameter count: " + infos.length);
        };
    }

    private Object resolveArgumentFast(
            MethodMetadata.ParamInfo info,
            byte[] body,
            FastMapV2 params,
            FastMapV2 headers
    ) {
        return switch (info.paramType) {
            case PATH_VARIABLE, REQUEST_PARAM -> {
                String value = params.get(info.name);
                if (value == null && info.defaultValue != null) {
                    value = info.defaultValue;
                }
                yield convertType(value, info.type);
            }
            case HEADER_PARAM -> {
                String headerValue = headers.get(info.name.toLowerCase(Locale.ROOT));
                if (headerValue == null && info.defaultValue != null) {
                    headerValue = info.defaultValue;
                }
                yield convertType(headerValue, info.type);
            }
            case COOKIE_VALUE -> {
                String cookieValue = findCookieValue(headers.get("cookie"), info.name);
                if (cookieValue == null && info.defaultValue != null) {
                    cookieValue = info.defaultValue;
                }
                yield convertType(cookieValue, info.type);
            }
            case REQUEST_BODY -> {
                if (body != null && body.length > 0) {
                    if (info.type == byte[].class) {
                        yield body;
                    }
                    if (info.type == ByteBuffer.class) {
                        yield ByteBuffer.wrap(body);
                    }
                    yield DslJsonService.parse(body, info.type);
                }
                yield null;
            }
            case LEGACY_BUFFER -> null;
            case LEGACY_INT -> 0;
            default -> null;
        };
    }

    private Object resolveArgumentFastDirect(
            MethodMetadata.ParamInfo info,
            ByteBuffer body,
            int bodyLen,
            FastMapV2 params,
            FastMapV2 headers
    ) {
        return switch (info.paramType) {
            case PATH_VARIABLE, REQUEST_PARAM -> {
                String value = params.get(info.name);
                if (value == null && info.defaultValue != null) {
                    value = info.defaultValue;
                }
                yield convertType(value, info.type);
            }
            case HEADER_PARAM -> {
                String headerValue = headers.get(info.name.toLowerCase(Locale.ROOT));
                if (headerValue == null && info.defaultValue != null) {
                    headerValue = info.defaultValue;
                }
                yield convertType(headerValue, info.type);
            }
            case COOKIE_VALUE -> {
                String cookieValue = findCookieValue(headers.get("cookie"), info.name);
                if (cookieValue == null && info.defaultValue != null) {
                    cookieValue = info.defaultValue;
                }
                yield convertType(cookieValue, info.type);
            }
            case REQUEST_BODY -> {
                if (body != null && bodyLen > 0) {
                    if (info.type == ByteBuffer.class) {
                        yield duplicateBody(body, bodyLen);
                    }
                    if (info.type == byte[].class) {
                        yield toByteArray(body, bodyLen);
                    }
                    yield DslJsonService.parse(body, bodyLen, info.type);
                }
                yield null;
            }
            case LEGACY_BUFFER -> null;
            case LEGACY_INT -> 0;
            default -> null;
        };
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

    /**
     * Convert string to target type (optimized).
     */
    private Object convertType(String value, Class<?> targetType) {
        if (value == null) return null;

        if (targetType == String.class) return value;
        if (targetType == int.class || targetType == Integer.class) return Integer.parseInt(value);
        if (targetType == long.class || targetType == Long.class) return Long.parseLong(value);
        if (targetType == double.class || targetType == Double.class) return Double.parseDouble(value);
        if (targetType == boolean.class || targetType == Boolean.class) return Boolean.parseBoolean(value);

        return value;
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
    private int writeError(ByteBuffer out, int offset, String message) {
        byte[] escaped = escapeJson(message).getBytes(StandardCharsets.UTF_8);
        int bodyLen = ERROR_PREFIX.length + escaped.length + ERROR_SUFFIX.length;
        byte[] body = new byte[bodyLen];
        int pos = 0;
        System.arraycopy(ERROR_PREFIX, 0, body, pos, ERROR_PREFIX.length);
        pos += ERROR_PREFIX.length;
        System.arraycopy(escaped, 0, body, pos, escaped.length);
        pos += escaped.length;
        System.arraycopy(ERROR_SUFFIX, 0, body, pos, ERROR_SUFFIX.length);
        return writeFrameWithBytes(500, DEFAULT_JSON_CONTENT_TYPE_HEADER, body, out, offset);
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
        if (rawResponse.getNativeId() > 0 && entityHeaderBytes.length == 0) {
            return writeStaticResponseFrame(rawResponse.getNativeId(), out, offset);
        }

        byte[] rawHeaderBytes = encodeHeaders(rawResponse.getHeaders());
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

    private int writeStaticResponseFrame(int nativeId, ByteBuffer out, int offset) {
        if (RESPONSE_FRAME_HEADER_SIZE > out.capacity() - offset) {
            return -RESPONSE_FRAME_HEADER_SIZE;
        }

        out.position(offset);
        out.put(STATIC_RESPONSE_FRAME_MAGIC);
        out.putShort((short) 0);
        out.putInt(0);
        out.putInt(nativeId);
        return RESPONSE_FRAME_HEADER_SIZE;
    }

    private int writeFileResponse(
            FileResponse fileResponse,
            int statusCode,
            byte[] entityHeaderBytes,
            ByteBuffer out,
            int offset
    ) {
        byte[] fileHeaderBytes = encodeHeaders(fileResponse.getHeaders());
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

    /**
     * Escape special characters in JSON string.
     */
    private String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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
        HandlerDescriptor desc = handlers.get(handlerId);

        if (desc == null) {
            return CompletableFuture.completedFuture(
                    ("{\"error\":\"Unknown handlerId\"}").getBytes(StandardCharsets.UTF_8)
            );
        }

        if (desc.isAsync) {
            try {
                Object raw = invokeAsyncRaw(desc, inBytes, pathParams, queryString, headers);
                if (raw instanceof CompletionStage<?> stage) {
                    return stage.toCompletableFuture()
                            .thenApply(result -> encodeAsyncResult(desc, result))
                            .exceptionally(this::encodeAsyncError);
                }
                return CompletableFuture.completedFuture(encodeAsyncResult(desc, raw));
            } catch (Throwable e) {
                return CompletableFuture.completedFuture(encodeAsyncError(e));
            }
        }

        return AsyncHandlerExecutor.getInstance().submit(() -> {
            try {
                ByteBuffer buffer = ASYNC_BUFFER_POOL.get();
                buffer.clear();

                int written;
                if (desc.usesAnnotatedParams) {
                    written = invokeAnnotatedFast(desc, buffer, 0, inBytes, pathParams, queryString, headers);
                } else {
                    written = invokeV4Async(desc, buffer, 0, inBytes, pathParams, queryString, headers);
                }

                byte[] result = new byte[written];
                buffer.position(0);
                buffer.get(result, 0, written);
                return result;

            } catch (Throwable e) {
                if (DEBUG) {
                    FrameworkLogger.debugError("[HandlerRegistry] Error: " + e.getClass().getName());
                    e.printStackTrace();
                }
                String errorMsg = e.getMessage();
                if (errorMsg == null) {
                    errorMsg = e.getClass().getName();
                    if (e.getCause() != null) {
                        errorMsg += ": " + e.getCause().getMessage();
                    }
                }
                return ("{\"error\":\"" + escapeJson(errorMsg) + "\"}").getBytes(StandardCharsets.UTF_8);
            }
        });
    }

    private Object invokeAsyncRaw(
            HandlerDescriptor desc,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) throws Throwable {
        if (desc.usesAnnotatedParams) {
            FastMapV2 paramMap = PARAM_MAP_POOL.get();
            FastMapV2 headerMap = HEADER_MAP_POOL.get();
            paramMap.clear();
            headerMap.clear();
            parseParamsFast(paramMap, pathParams, false);
            parseParamsFast(paramMap, queryString, true);
            parseHeadersFast(headerMap, headers);
            return invokeAnnotatedHandle(desc, inBytes, paramMap, headerMap);
        }

        ByteBuffer out = ASYNC_BUFFER_POOL.get();
        out.clear();
        if (desc.usesDirectBodyBuffer) {
            ByteBuffer inBuffer = ByteBuffer.wrap(inBytes == null ? EMPTY_BYTES : inBytes);
            return desc.handle.invoke(
                    out,
                    0,
                    inBuffer,
                    inBuffer.remaining(),
                    pathParams,
                    queryString,
                    headers
            );
        }
        return desc.handle.invoke(out, 0, inBytes, pathParams, queryString, headers);
    }

    private byte[] encodeAsyncResult(HandlerDescriptor desc, Object result) {
        try {
            ByteBuffer buffer = ASYNC_BUFFER_POOL.get();
            buffer.clear();
            int written = processAsyncResult(desc, result, buffer, 0);
            byte[] response = new byte[written];
            buffer.position(0);
            buffer.get(response, 0, written);
            return response;
        } catch (Throwable e) {
            return encodeAsyncError(e);
        }
    }

    private byte[] encodeAsyncError(Throwable e) {
        if (DEBUG) {
            FrameworkLogger.debugError("[HandlerRegistry] Async error: " + e.getClass().getName());
            e.printStackTrace();
        }
        String errorMsg = e.getMessage();
        if (errorMsg == null) {
            errorMsg = e.getClass().getName();
            if (e.getCause() != null) {
                errorMsg += ": " + e.getCause().getMessage();
            }
        }
        ByteBuffer buffer = ASYNC_BUFFER_POOL.get();
        buffer.clear();
        byte[] body = ("{\"error\":\"" + escapeJson(errorMsg) + "\"}").getBytes(StandardCharsets.UTF_8);
        int written = writeFrameWithBytes(500, DEFAULT_JSON_CONTENT_TYPE_HEADER, body, buffer, 0);
        byte[] response = new byte[written];
        buffer.position(0);
        buffer.get(response, 0, response.length);
        return response;
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

        if (result instanceof ResponseEntity<?> responseEntity) {
            return writeResponseEntity(responseEntity, desc.defaultContentTypeHeader, out, offset);
        }

        if (result != null) {
            return writeObjectFrame(200, result, desc.defaultContentTypeHeader, out, offset);
        }

        return 0;
    }
}

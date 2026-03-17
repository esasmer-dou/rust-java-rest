package com.reactor.rust.bridge;

import com.reactor.rust.annotations.ResponseStatus;
import com.reactor.rust.async.AsyncHandlerExecutor;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.json.DslJsonService;
import com.reactor.rust.util.FastMapV2;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
    private static final boolean DEBUG = Boolean.getBoolean("handler.debug");

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
        public final boolean returnsResponseEntity;
        public final boolean isAsync;
        public final int customResponseStatus;

        // Cached metadata for fast parameter resolution
        public final MethodMetadata metadata;

        public HandlerDescriptor(Object bean,
                Method method,
                Class<?> requestType,
                Class<?> responseType,
                MethodHandle handle,
                boolean usesAnnotatedParams,
                boolean returnsResponseEntity,
                boolean isAsync,
                int customResponseStatus) {
            this.bean = bean;
            this.method = method;
            this.requestType = requestType;
            this.responseType = responseType;
            this.handle = handle;
            this.usesAnnotatedParams = usesAnnotatedParams;
            this.returnsResponseEntity = returnsResponseEntity;
            this.isAsync = isAsync;
            this.customResponseStatus = customResponseStatus;
            this.metadata = MethodMetadata.getOrCreate(method, requestType, responseType);
        }

        // Legacy constructor for backwards compatibility
        public HandlerDescriptor(Object bean,
                Method method,
                Class<?> requestType,
                Class<?> responseType,
                MethodHandle handle) {
            this(bean, method, requestType, responseType, handle, false, false, false, 200);
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
                System.out.println("[HandlerRegistry] bean registered = " + bean.getClass().getName());
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

            // Check if method uses annotated parameters
            boolean usesAnnotatedParams = ParameterResolver.isAnnotatedMethod(method);

            // Check if method returns ResponseEntity
            boolean returnsResponseEntity = ParameterResolver.returnsResponseEntity(method);

            // Check if method returns CompletableFuture (async)
            boolean isAsync = CompletableFuture.class.isAssignableFrom(method.getReturnType());

            // Check for @ResponseStatus annotation
            int customResponseStatus = 200;
            ResponseStatus responseStatus = method.getAnnotation(ResponseStatus.class);
            if (responseStatus != null) {
                customResponseStatus = responseStatus.value();
            }

            int id = idGenerator.getAndIncrement();
            handlers.put(id, new HandlerDescriptor(
                bean, method, requestType, responseType, mh,
                usesAnnotatedParams, returnsResponseEntity, isAsync, customResponseStatus
            ));

            if (DEBUG) {
                System.out.println("[HandlerRegistry] Handler registered: id=" + id
                        + " bean=" + bean.getClass().getName()
                        + " method=" + method.getName()
                        + " reqType=" + requestType.getName()
                        + " respType=" + responseType.getName()
                        + " annotatedParams=" + usesAnnotatedParams
                        + " returnsResponseEntity=" + returnsResponseEntity
                        + " isAsync=" + isAsync);
            }

            return id;

        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to create MethodHandle for handler", e);
        }
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
            } else {
                return invokeV4(desc, out, offset, inBytes, pathParams, queryString, headers);
            }

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

        if (result instanceof Integer) {
            return (Integer) result;
        }

        // Handle ResponseEntity return type
        if (result instanceof ResponseEntity<?> responseEntity) {
            return writeResponseEntity(responseEntity, out, offset);
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

            // Fast parse using FastMapV2
            parseParamsFast(paramMap, pathParams);
            parseParamsFast(paramMap, queryString);
            parseHeadersFast(headerMap, headers);

            // Resolve parameters using cached metadata
            Object[] args = resolveArgumentsFast(
                desc.metadata.paramInfos,
                inBytes, paramMap, headerMap
            );

            // Invoke method
            Object result = desc.handle.invokeWithArguments(args);

            // Handle different return types
            if (result instanceof Integer) {
                return (Integer) result;
            }

            if (result instanceof ResponseEntity<?> responseEntity) {
                return writeResponseEntity(responseEntity, out, offset);
            }

            // Auto-serialize response object
            if (result != null && desc.responseType != Void.class) {
                return DslJsonService.writeToBuffer(result, out, offset);
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
    private void parseParamsFast(FastMapV2 map, String params) {
        if (params == null || params.isEmpty()) return;

        int start = 0;
        int len = params.length();

        for (int i = 0; i <= len; i++) {
            if (i == len || params.charAt(i) == '&') {
                if (i > start) {
                    int eqIdx = params.indexOf('=', start);
                    if (eqIdx > start && eqIdx < i) {
                        String key = params.substring(start, eqIdx);
                        String value = params.substring(eqIdx + 1, i);
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
                        String key = headers.substring(start, colonIdx).trim().toLowerCase();
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

    /**
     * Resolve arguments using pre-computed parameter info.
     */
    private Object[] resolveArgumentsFast(
            MethodMetadata.ParamInfo[] paramInfos,
            byte[] body,
            FastMapV2 params,
            FastMapV2 headers
    ) {
        Object[] args = new Object[paramInfos.length];

        for (int i = 0; i < paramInfos.length; i++) {
            MethodMetadata.ParamInfo info = paramInfos[i];

            switch (info.paramType) {
                case PATH_VARIABLE:
                case REQUEST_PARAM:
                    String value = params.get(info.name);
                    if (value == null && info.defaultValue != null) {
                        value = info.defaultValue;
                    }
                    args[i] = convertType(value, info.type);
                    break;

                case HEADER_PARAM:
                    String headerValue = headers.get(info.name.toLowerCase());
                    if (headerValue == null && info.defaultValue != null) {
                        headerValue = info.defaultValue;
                    }
                    args[i] = convertType(headerValue, info.type);
                    break;

                case REQUEST_BODY:
                    if (body != null && body.length > 0) {
                        args[i] = DslJsonService.parse(body, info.type);
                    } else {
                        args[i] = null;
                    }
                    break;

                case LEGACY_BUFFER:
                    args[i] = null;
                    break;

                case LEGACY_INT:
                    args[i] = 0;
                    break;

                default:
                    args[i] = null;
            }
        }

        return args;
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

    /**
     * Write ResponseEntity to buffer.
     */
    private int writeResponseEntity(ResponseEntity<?> responseEntity, ByteBuffer out, int offset) {
        Object body = responseEntity.getBody();
        if (body == null) {
            return 0;
        }
        return DslJsonService.writeToBuffer(body, out, offset);
    }

    /**
     * Write error response to buffer.
     */
    private int writeError(ByteBuffer out, int offset, String message) {
        byte[] err = ("{\"error\":\"" + escapeJson(message) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        out.position(offset);
        out.put(err);
        return err.length;
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
                    System.err.println("[HandlerRegistry] Error: " + e.getClass().getName());
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
            return processAsyncResult(asyncResult, out, offset);
        }

        return processAsyncResult(result, out, offset);
    }

    /**
     * Process async result - handle different return types.
     */
    private int processAsyncResult(Object result, ByteBuffer out, int offset) {
        if (result instanceof Integer) {
            return (Integer) result;
        }

        if (result instanceof ResponseEntity<?> responseEntity) {
            return writeResponseEntity(responseEntity, out, offset);
        }

        if (result != null) {
            return DslJsonService.writeToBuffer(result, out, offset);
        }

        return 0;
    }
}

package com.reactor.rust.bridge;

import com.reactor.rust.annotations.ResponseStatus;
import com.reactor.rust.async.AsyncHandlerExecutor;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.json.DslJsonService;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handler registry with MethodHandle-based invocation.
 *
 * Supports two handler styles:
 * 1. V4 signature: (ByteBuffer out, int offset, byte[] body, String pathParams, String queryString, String headers)
 * 2. Annotated parameters: (@PathVariable, @RequestParam, @HeaderParam, @RequestBody, @CookieValue)
 *
 * Also supports:
 * - ResponseEntity return type (automatic serialization)
 * - @ResponseStatus annotation for custom HTTP status codes
 *
 * OPTIMIZED:
 * - Removed System.out.println from hot paths (uses lazy logging)
 * - ThreadLocal ByteBuffer pool for async handlers
 */
public class HandlerRegistry {

    private static final HandlerRegistry INSTANCE = new HandlerRegistry();

    // ThreadLocal ByteBuffer pool for async handlers (64KB buffers)
    private static final ThreadLocal<ByteBuffer> ASYNC_BUFFER_POOL =
        ThreadLocal.withInitial(() -> ByteBuffer.allocate(64 * 1024));

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
                return invokeAnnotated(desc, out, offset, inBytes, pathParams, queryString, headers);
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
     * Invoke handler with annotated parameters (new style).
     */
    private int invokeAnnotated(
            HandlerDescriptor desc,
            ByteBuffer out,
            int offset,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) throws Throwable {

        // Resolve parameters from annotations
        Object[] args = ParameterResolver.resolveParameters(
            desc.method, inBytes, pathParams, queryString, headers
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

    /**
     * Invoke async handler - returns CompletableFuture.
     * Result will be written to a ThreadLocal buffer (zero-allocation).
     *
     * @param handlerId Handler ID
     * @param inBytes Input body bytes
     * @param pathParams Path parameters
     * @param queryString Query string
     * @param headers Headers
     * @return CompletableFuture with response bytes
     */
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
                // Reuse ThreadLocal buffer - no allocation
                ByteBuffer buffer = ASYNC_BUFFER_POOL.get();
                buffer.clear(); // Reset position and limit

                int written;
                if (desc.usesAnnotatedParams) {
                    written = invokeAnnotatedAsync(desc, buffer, 0, inBytes, pathParams, queryString, headers);
                } else {
                    written = invokeV4Async(desc, buffer, 0, inBytes, pathParams, queryString, headers);
                }

                // Extract bytes from buffer
                byte[] result = new byte[written];
                buffer.position(0);
                buffer.get(result, 0, written);
                return result;

            } catch (Throwable e) {
                // Only log errors in DEBUG mode
                if (DEBUG) {
                    System.err.println("[HandlerRegistry] Error handling request: " + e.getClass().getName());
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

    /**
     * Invoke V4 async handler that returns CompletableFuture.
     */
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

        // If already CompletableFuture, wait for result
        if (result instanceof CompletableFuture<?> future) {
            Object asyncResult = future.join();
            return processAsyncResult(asyncResult, out, offset);
        }

        // Direct result
        return processAsyncResult(result, out, offset);
    }

    /**
     * Invoke annotated async handler that returns CompletableFuture.
     */
    @SuppressWarnings("unchecked")
    private int invokeAnnotatedAsync(
            HandlerDescriptor desc,
            ByteBuffer out,
            int offset,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) throws Throwable {

        Object[] args = ParameterResolver.resolveParameters(
                desc.method, inBytes, pathParams, queryString, headers
        );

        Object result = desc.handle.invokeWithArguments(args);

        // If CompletableFuture, wait for result
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

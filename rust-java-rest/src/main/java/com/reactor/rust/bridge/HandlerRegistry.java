package com.reactor.rust.bridge;

import com.reactor.rust.annotations.ResponseStatus;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.json.DslJsonService;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
 */
public class HandlerRegistry {

    private static final HandlerRegistry INSTANCE = new HandlerRegistry();

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
        public final int customResponseStatus;

        public HandlerDescriptor(Object bean,
                Method method,
                Class<?> requestType,
                Class<?> responseType,
                MethodHandle handle,
                boolean usesAnnotatedParams,
                boolean returnsResponseEntity,
                int customResponseStatus) {
            this.bean = bean;
            this.method = method;
            this.requestType = requestType;
            this.responseType = responseType;
            this.handle = handle;
            this.usesAnnotatedParams = usesAnnotatedParams;
            this.returnsResponseEntity = returnsResponseEntity;
            this.customResponseStatus = customResponseStatus;
        }

        // Legacy constructor for backwards compatibility
        public HandlerDescriptor(Object bean,
                Method method,
                Class<?> requestType,
                Class<?> responseType,
                MethodHandle handle) {
            this(bean, method, requestType, responseType, handle, false, false, 200);
        }
    }

    private HandlerRegistry() {}

    public List<Object> getHandlers() {
        return handlerBeans;
    }

    public void registerBean(Object bean) {
        if (!handlerBeans.contains(bean)) {
            handlerBeans.add(bean);
            System.out.println(">>> [HandlerRegistry] bean registered = " + bean.getClass().getName());
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

            // Check for @ResponseStatus annotation
            int customResponseStatus = 200;
            ResponseStatus responseStatus = method.getAnnotation(ResponseStatus.class);
            if (responseStatus != null) {
                customResponseStatus = responseStatus.value();
            }

            int id = idGenerator.getAndIncrement();
            handlers.put(id, new HandlerDescriptor(
                bean, method, requestType, responseType, mh,
                usesAnnotatedParams, returnsResponseEntity, customResponseStatus
            ));

            System.out.println("[JAVA] Handler registered: id=" + id
                    + " bean=" + bean.getClass().getName()
                    + " method=" + method.getName()
                    + " reqType=" + requestType.getName()
                    + " respType=" + responseType.getName()
                    + " annotatedParams=" + usesAnnotatedParams
                    + " returnsResponseEntity=" + returnsResponseEntity);

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
}

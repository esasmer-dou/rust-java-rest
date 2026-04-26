package com.reactor.rust.bridge;

import com.reactor.rust.annotations.*;
import com.reactor.rust.logging.FrameworkLogger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Route Scanner - No Spring
 * Scans handlers for route annotations and registers routes with Rust.
 *
 * Supports:
 * - @RustRoute (legacy)
 * - @GetMapping, @PostMapping, @PutMapping, @DeleteMapping, @PatchMapping
 * - @RequestMapping (class-level for base path prefix)
 */
public final class RouteScanner {

    private RouteScanner() {}

    /**
     * Scan all registered handlers and register routes with Rust
     */
    public static void scanAndRegister() {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        List<Object> handlers = registry.getHandlers();

        List<RouteDef> routes = new ArrayList<>();

        for (Object bean : handlers) {
            scanHandler(bean, routes);
        }

        // Pass NativeBridge class to Rust for JNI callbacks
        NativeBridge.passNativeBridgeClass(NativeBridge.class);

        // Register all routes with Rust
        NativeBridge.registerRoutes(routes);

        FrameworkLogger.info("[RUST] Routes registered: exact=" +
                routes.stream().filter(r -> !r.path.contains("{")).count() +
                " pattern=" +
                routes.stream().filter(r -> r.path.contains("{")).count());
    }

    /**
     * Scan a single handler for route annotations
     */
    private static void scanHandler(Object bean, List<RouteDef> routes) {
        Class<?> clazz = bean.getClass();

        // Get base path from class-level @RequestMapping
        String basePath = "";
        RequestMapping classMapping = clazz.getAnnotation(RequestMapping.class);
        if (classMapping != null) {
            basePath = classMapping.value();
            if (!basePath.isEmpty() && !basePath.startsWith("/")) {
                basePath = "/" + basePath;
            }
            if (basePath.endsWith("/")) {
                basePath = basePath.substring(0, basePath.length() - 1);
            }
        }

        for (Method method : clazz.getDeclaredMethods()) {
            RouteInfo routeInfo = extractRouteInfo(method, basePath);
            if (routeInfo == null) {
                continue;
            }

            int handlerId = HandlerRegistry.getInstance().registerHandler(
                    bean,
                    method,
                    routeInfo.requestType,
                    routeInfo.responseType
            );
            MethodMetadata metadata = MethodMetadata.getOrCreate(
                    method,
                    routeInfo.requestType,
                    routeInfo.responseType
            );
            boolean legacyV4 = isLegacyV4(method);
            boolean directV5 = isDirectV5(method);
            boolean directQueryInt = isDirectQueryInt(method);
            DirectQueryInt directQueryIntAnnotation = method.getAnnotation(DirectQueryInt.class);
            if (directQueryIntAnnotation != null && !directQueryInt) {
                throw new IllegalArgumentException(
                        "@DirectQueryInt requires handler signature (ByteBuffer out, int offset, int value): " + method
                );
            }
            if (directQueryInt && directQueryIntAnnotation == null) {
                throw new IllegalArgumentException(
                        "Direct query int handler " + method + " must declare @DirectQueryInt"
                );
            }
            if (directQueryInt && !isVoidRequestType(routeInfo.requestType)) {
                throw new IllegalArgumentException(
                        "Direct query int handler must use requestType=Void.class: " + method
                );
            }
            if (directQueryIntAnnotation != null && directQueryIntAnnotation.value().isBlank()) {
                throw new IllegalArgumentException("@DirectQueryInt value must not be blank: " + method);
            }
            RawRequestData rawRequestData = method.getAnnotation(RawRequestData.class);
            boolean bodyless = !metadata.needsBody && isVoidRequestType(routeInfo.requestType);
            boolean directNeedsPathParams = directV5 && (rawRequestData == null || rawRequestData.pathParams());
            boolean directNeedsQueryParams = directV5 && (rawRequestData == null || rawRequestData.query());
            boolean directNeedsHeaders = directV5 && (rawRequestData == null || rawRequestData.headers());
            String directQueryIntName = directQueryIntAnnotation != null ? directQueryIntAnnotation.value() : "";

            routes.add(new RouteDef(
                    routeInfo.httpMethod,
                    routeInfo.path,
                    handlerId,
                    routeInfo.requestType.getName(),
                    routeInfo.responseType.getName(),
                    bodyless,
                    legacyV4 || directNeedsPathParams || metadata.needsPathParams,
                    legacyV4 || directNeedsQueryParams || metadata.needsQueryParams,
                    legacyV4 || directNeedsHeaders || metadata.needsHeaders,
                    routeInfo.maxRequestBodyBytes,
                    routeInfo.maxResponseBodyBytes,
                    directQueryIntName,
                    directQueryIntAnnotation != null ? directQueryIntAnnotation.defaultValue() : 0,
                    directQueryIntAnnotation != null ? directQueryIntAnnotation.min() : Integer.MIN_VALUE,
                    directQueryIntAnnotation != null ? directQueryIntAnnotation.max() : Integer.MAX_VALUE
            ));

            FrameworkLogger.debug("[JAVA] Handler registered: id=" + handlerId +
                    " bean=" + bean.getClass().getName() +
                    " method=" + method.getName() +
                    " reqType=" + routeInfo.requestType.getName() +
                    " respType=" + routeInfo.responseType.getName());
        }
    }

    /**
     * Extract route info from method annotations.
     */
    private static RouteInfo extractRouteInfo(Method method, String basePath) {
        // Legacy @RustRoute annotation
        RustRoute rustRoute = method.getAnnotation(RustRoute.class);
        if (rustRoute != null) {
            return withLimits(method, new RouteInfo(
                    rustRoute.method(),
                    rustRoute.path(),
                    rustRoute.requestType(),
                    rustRoute.responseType()
            ));
        }

        // @GetMapping
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        if (getMapping != null) {
            return withLimits(method, new RouteInfo(
                    "GET",
                    buildPath(basePath, getMapping.value()),
                    getMapping.requestType(),
                    getMapping.responseType()
            ));
        }

        // @PostMapping
        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        if (postMapping != null) {
            return withLimits(method, new RouteInfo(
                    "POST",
                    buildPath(basePath, postMapping.value()),
                    postMapping.requestType(),
                    postMapping.responseType()
            ));
        }

        // @PutMapping
        PutMapping putMapping = method.getAnnotation(PutMapping.class);
        if (putMapping != null) {
            return withLimits(method, new RouteInfo(
                    "PUT",
                    buildPath(basePath, putMapping.value()),
                    putMapping.requestType(),
                    putMapping.responseType()
            ));
        }

        // @DeleteMapping
        DeleteMapping deleteMapping = method.getAnnotation(DeleteMapping.class);
        if (deleteMapping != null) {
            return withLimits(method, new RouteInfo(
                    "DELETE",
                    buildPath(basePath, deleteMapping.value()),
                    deleteMapping.requestType(),
                    deleteMapping.responseType()
            ));
        }

        // @PatchMapping
        PatchMapping patchMapping = method.getAnnotation(PatchMapping.class);
        if (patchMapping != null) {
            return withLimits(method, new RouteInfo(
                    "PATCH",
                    buildPath(basePath, patchMapping.value()),
                    patchMapping.requestType(),
                    patchMapping.responseType()
            ));
        }

        return null;
    }

    /**
     * Build full path from base path and method path.
     */
    private static String buildPath(String basePath, String methodPath) {
        if (methodPath == null || methodPath.isEmpty()) {
            return basePath.isEmpty() ? "/" : basePath;
        }

        if (!methodPath.startsWith("/")) {
            methodPath = "/" + methodPath;
        }

        return basePath.isEmpty() ? methodPath : basePath + methodPath;
    }

    private static RouteInfo withLimits(Method method, RouteInfo routeInfo) {
        MaxRequestBodySize maxRequest = method.getAnnotation(MaxRequestBodySize.class);
        MaxResponseSize maxResponse = method.getAnnotation(MaxResponseSize.class);
        return new RouteInfo(
                routeInfo.httpMethod,
                routeInfo.path,
                routeInfo.requestType,
                routeInfo.responseType,
                maxRequest != null ? maxRequest.value() : 0L,
                maxResponse != null ? maxResponse.value() : 0L
        );
    }

    private static boolean isVoidRequestType(Class<?> requestType) {
        return requestType == Void.class || requestType == void.class;
    }

    private static boolean isLegacyV4(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 6
                && parameterTypes[0] == java.nio.ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == byte[].class
                && parameterTypes[3] == String.class
                && parameterTypes[4] == String.class
                && parameterTypes[5] == String.class;
    }

    private static boolean isDirectV5(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 7
                && parameterTypes[0] == java.nio.ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == java.nio.ByteBuffer.class
                && parameterTypes[3] == int.class
                && parameterTypes[4] == String.class
                && parameterTypes[5] == String.class
                && parameterTypes[6] == String.class;
    }

    private static boolean isDirectQueryInt(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 3
                && parameterTypes[0] == java.nio.ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == int.class;
    }

    /**
     * Internal class to hold route information.
     */
    private static class RouteInfo {
        final String httpMethod;
        final String path;
        final Class<?> requestType;
        final Class<?> responseType;
        final long maxRequestBodyBytes;
        final long maxResponseBodyBytes;

        RouteInfo(String httpMethod, String path, Class<?> requestType, Class<?> responseType) {
            this(httpMethod, path, requestType, responseType, 0L, 0L);
        }

        RouteInfo(String httpMethod,
                String path,
                Class<?> requestType,
                Class<?> responseType,
                long maxRequestBodyBytes,
                long maxResponseBodyBytes) {
            this.httpMethod = httpMethod;
            this.path = path;
            this.requestType = requestType;
            this.responseType = responseType;
            this.maxRequestBodyBytes = maxRequestBodyBytes;
            this.maxResponseBodyBytes = maxResponseBodyBytes;
        }
    }
}

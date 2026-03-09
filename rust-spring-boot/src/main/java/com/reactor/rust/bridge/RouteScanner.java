package com.reactor.rust.bridge;

import com.reactor.rust.annotations.*;

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

        System.out.println("[RUST] Routes registered: exact=" +
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

            routes.add(new RouteDef(
                    routeInfo.httpMethod,
                    routeInfo.path,
                    handlerId,
                    routeInfo.requestType.getName(),
                    routeInfo.responseType.getName()
            ));

            System.out.println("[JAVA] Handler registered: id=" + handlerId +
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
            return new RouteInfo(
                    rustRoute.method(),
                    rustRoute.path(),
                    rustRoute.requestType(),
                    rustRoute.responseType()
            );
        }

        // @GetMapping
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        if (getMapping != null) {
            return new RouteInfo(
                    "GET",
                    buildPath(basePath, getMapping.value()),
                    Void.class,
                    Object.class
            );
        }

        // @PostMapping
        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        if (postMapping != null) {
            return new RouteInfo(
                    "POST",
                    buildPath(basePath, postMapping.value()),
                    Void.class,
                    Object.class
            );
        }

        // @PutMapping
        PutMapping putMapping = method.getAnnotation(PutMapping.class);
        if (putMapping != null) {
            return new RouteInfo(
                    "PUT",
                    buildPath(basePath, putMapping.value()),
                    Void.class,
                    Object.class
            );
        }

        // @DeleteMapping
        DeleteMapping deleteMapping = method.getAnnotation(DeleteMapping.class);
        if (deleteMapping != null) {
            return new RouteInfo(
                    "DELETE",
                    buildPath(basePath, deleteMapping.value()),
                    Void.class,
                    Object.class
            );
        }

        // @PatchMapping
        PatchMapping patchMapping = method.getAnnotation(PatchMapping.class);
        if (patchMapping != null) {
            return new RouteInfo(
                    "PATCH",
                    buildPath(basePath, patchMapping.value()),
                    Void.class,
                    Object.class
            );
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

    /**
     * Internal class to hold route information.
     */
    private static class RouteInfo {
        final String httpMethod;
        final String path;
        final Class<?> requestType;
        final Class<?> responseType;

        RouteInfo(String httpMethod, String path, Class<?> requestType, Class<?> responseType) {
            this.httpMethod = httpMethod;
            this.path = path;
            this.requestType = requestType;
            this.responseType = responseType;
        }
    }
}

package com.reactor.rust.bridge;

import com.reactor.rust.annotations.*;
import com.reactor.rust.http.FileResponse;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.logging.FrameworkLogger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

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
        RoutePlanRegistry routePlans = RoutePlanRegistry.getInstance();
        routePlans.clear();
        routePlans.configureFromProperties();

        for (Object bean : handlers) {
            scanHandler(bean, routes);
        }

        routePlans.publishStartupMetrics();
        routePlans.logSummary();
        routePlans.validateProductionGate();

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
            boolean directIntSignature = isDirectInt(method);
            boolean directLongSignature = isDirectLong(method);
            boolean directBooleanSignature = isDirectBoolean(method);
            boolean directDoubleSignature = isDirectDouble(method);
            boolean directShortSignature = isDirectShort(method);
            boolean directBodylessOutput = isDirectBodylessOutput(method);
            DirectQueryInt directQueryIntAnnotation = method.getAnnotation(DirectQueryInt.class);
            DirectQueryLong directQueryLongAnnotation = method.getAnnotation(DirectQueryLong.class);
            DirectQueryBoolean directQueryBooleanAnnotation = method.getAnnotation(DirectQueryBoolean.class);
            DirectQueryDouble directQueryDoubleAnnotation = method.getAnnotation(DirectQueryDouble.class);
            DirectQueryShort directQueryShortAnnotation = method.getAnnotation(DirectQueryShort.class);
            DirectPathInt directPathIntAnnotation = method.getAnnotation(DirectPathInt.class);
            DirectPathLong directPathLongAnnotation = method.getAnnotation(DirectPathLong.class);
            DirectPathBoolean directPathBooleanAnnotation = method.getAnnotation(DirectPathBoolean.class);
            DirectPathDouble directPathDoubleAnnotation = method.getAnnotation(DirectPathDouble.class);
            DirectPathShort directPathShortAnnotation = method.getAnnotation(DirectPathShort.class);
            boolean directQueryInt = directQueryIntAnnotation != null;
            boolean directQueryLong = directQueryLongAnnotation != null;
            boolean directQueryBoolean = directQueryBooleanAnnotation != null;
            boolean directQueryDouble = directQueryDoubleAnnotation != null;
            boolean directQueryShort = directQueryShortAnnotation != null;
            boolean directPathInt = directPathIntAnnotation != null;
            boolean directPathLong = directPathLongAnnotation != null;
            boolean directPathBoolean = directPathBooleanAnnotation != null;
            boolean directPathDouble = directPathDoubleAnnotation != null;
            boolean directPathShort = directPathShortAnnotation != null;
            int directPrimitiveAnnotations = (directQueryIntAnnotation != null ? 1 : 0)
                    + (directQueryLongAnnotation != null ? 1 : 0)
                    + (directQueryBooleanAnnotation != null ? 1 : 0)
                    + (directQueryDoubleAnnotation != null ? 1 : 0)
                    + (directQueryShortAnnotation != null ? 1 : 0)
                    + (directPathIntAnnotation != null ? 1 : 0)
                    + (directPathLongAnnotation != null ? 1 : 0)
                    + (directPathBooleanAnnotation != null ? 1 : 0)
                    + (directPathDoubleAnnotation != null ? 1 : 0)
                    + (directPathShortAnnotation != null ? 1 : 0);
            if (directPrimitiveAnnotations > 1) {
                throw new IllegalArgumentException(
                        "Only one direct primitive annotation is allowed per handler: " + method
                );
            }
            if (directBodylessOutput && !isVoidRequestType(routeInfo.requestType)) {
                throw new IllegalArgumentException(
                        "Direct bodyless output handler must use requestType=Void.class: " + method
                );
            }
            if (directQueryIntAnnotation != null && !directIntSignature) {
                throw new IllegalArgumentException(
                        "@DirectQueryInt requires handler signature (ByteBuffer out, int offset, int value): " + method
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
            if (directQueryLongAnnotation != null && !directLongSignature) {
                throw new IllegalArgumentException(
                        "@DirectQueryLong requires handler signature (ByteBuffer out, int offset, long value): " + method
                );
            }
            if (directQueryLong && !isVoidRequestType(routeInfo.requestType)) {
                throw new IllegalArgumentException(
                        "Direct query long handler must use requestType=Void.class: " + method
                );
            }
            if (directQueryLongAnnotation != null && directQueryLongAnnotation.value().isBlank()) {
                throw new IllegalArgumentException("@DirectQueryLong value must not be blank: " + method);
            }
            if (directQueryLongAnnotation != null && directQueryLongAnnotation.min() > directQueryLongAnnotation.max()) {
                throw new IllegalArgumentException("@DirectQueryLong min must be <= max: " + method);
            }
            if (directQueryBooleanAnnotation != null && !directBooleanSignature) {
                throw new IllegalArgumentException(
                        "@DirectQueryBoolean requires handler signature (ByteBuffer out, int offset, boolean value): " + method
                );
            }
            if (directQueryBoolean && !isVoidRequestType(routeInfo.requestType)) {
                throw new IllegalArgumentException(
                        "Direct query boolean handler must use requestType=Void.class: " + method
                );
            }
            if (directQueryBooleanAnnotation != null && directQueryBooleanAnnotation.value().isBlank()) {
                throw new IllegalArgumentException("@DirectQueryBoolean value must not be blank: " + method);
            }
            if (directQueryDoubleAnnotation != null && !directDoubleSignature) {
                throw new IllegalArgumentException(
                        "@DirectQueryDouble requires handler signature (ByteBuffer out, int offset, double value): " + method
                );
            }
            if (directQueryDouble && !isVoidRequestType(routeInfo.requestType)) {
                throw new IllegalArgumentException(
                        "Direct query double handler must use requestType=Void.class: " + method
                );
            }
            if (directQueryDoubleAnnotation != null && directQueryDoubleAnnotation.value().isBlank()) {
                throw new IllegalArgumentException("@DirectQueryDouble value must not be blank: " + method);
            }
            if (directQueryDoubleAnnotation != null
                    && !validDoubleRange(
                    directQueryDoubleAnnotation.defaultValue(),
                    directQueryDoubleAnnotation.min(),
                    directQueryDoubleAnnotation.max()
            )) {
                throw new IllegalArgumentException("@DirectQueryDouble default/min/max must be finite and min <= default <= max: " + method);
            }
            if (directQueryShortAnnotation != null && !directShortSignature) {
                throw new IllegalArgumentException(
                        "@DirectQueryShort requires handler signature (ByteBuffer out, int offset, short value): " + method
                );
            }
            if (directQueryShort && !isVoidRequestType(routeInfo.requestType)) {
                throw new IllegalArgumentException(
                        "Direct query short handler must use requestType=Void.class: " + method
                );
            }
            if (directQueryShortAnnotation != null && directQueryShortAnnotation.value().isBlank()) {
                throw new IllegalArgumentException("@DirectQueryShort value must not be blank: " + method);
            }
            if (directQueryShortAnnotation != null && directQueryShortAnnotation.min() > directQueryShortAnnotation.max()) {
                throw new IllegalArgumentException("@DirectQueryShort min must be <= max: " + method);
            }
            if (directPathIntAnnotation != null && !directIntSignature) {
                throw new IllegalArgumentException(
                        "@DirectPathInt requires handler signature (ByteBuffer out, int offset, int value): " + method
                );
            }
            if (directPathInt && !isVoidRequestType(routeInfo.requestType)) {
                throw new IllegalArgumentException(
                        "Direct path int handler must use requestType=Void.class: " + method
                );
            }
            if (directPathIntAnnotation != null && directPathIntAnnotation.value().isBlank()) {
                throw new IllegalArgumentException("@DirectPathInt value must not be blank: " + method);
            }
            if (directPathIntAnnotation != null
                    && !pathContainsVariable(routeInfo.path, directPathIntAnnotation.value())) {
                throw new IllegalArgumentException(
                        "@DirectPathInt variable '" + directPathIntAnnotation.value()
                                + "' is not present in route path " + routeInfo.path + ": " + method
                );
            }
            if (directPathIntAnnotation != null && directPathIntAnnotation.min() > directPathIntAnnotation.max()) {
                throw new IllegalArgumentException("@DirectPathInt min must be <= max: " + method);
            }
            if (directPathLongAnnotation != null && !directLongSignature) {
                throw new IllegalArgumentException(
                        "@DirectPathLong requires handler signature (ByteBuffer out, int offset, long value): " + method
                );
            }
            if (directPathLong && !isVoidRequestType(routeInfo.requestType)) {
                throw new IllegalArgumentException(
                        "Direct path long handler must use requestType=Void.class: " + method
                );
            }
            if (directPathLongAnnotation != null && directPathLongAnnotation.value().isBlank()) {
                throw new IllegalArgumentException("@DirectPathLong value must not be blank: " + method);
            }
            if (directPathLongAnnotation != null
                    && !pathContainsVariable(routeInfo.path, directPathLongAnnotation.value())) {
                throw new IllegalArgumentException(
                        "@DirectPathLong variable '" + directPathLongAnnotation.value()
                                + "' is not present in route path " + routeInfo.path + ": " + method
                );
            }
            if (directPathLongAnnotation != null && directPathLongAnnotation.min() > directPathLongAnnotation.max()) {
                throw new IllegalArgumentException("@DirectPathLong min must be <= max: " + method);
            }
            if (directPathBooleanAnnotation != null && !directBooleanSignature) {
                throw new IllegalArgumentException(
                        "@DirectPathBoolean requires handler signature (ByteBuffer out, int offset, boolean value): " + method
                );
            }
            if (directPathBoolean && !isVoidRequestType(routeInfo.requestType)) {
                throw new IllegalArgumentException(
                        "Direct path boolean handler must use requestType=Void.class: " + method
                );
            }
            if (directPathBooleanAnnotation != null && directPathBooleanAnnotation.value().isBlank()) {
                throw new IllegalArgumentException("@DirectPathBoolean value must not be blank: " + method);
            }
            if (directPathBooleanAnnotation != null
                    && !pathContainsVariable(routeInfo.path, directPathBooleanAnnotation.value())) {
                throw new IllegalArgumentException(
                        "@DirectPathBoolean variable '" + directPathBooleanAnnotation.value()
                                + "' is not present in route path " + routeInfo.path + ": " + method
                );
            }
            if (directPathDoubleAnnotation != null && !directDoubleSignature) {
                throw new IllegalArgumentException(
                        "@DirectPathDouble requires handler signature (ByteBuffer out, int offset, double value): " + method
                );
            }
            if (directPathDouble && !isVoidRequestType(routeInfo.requestType)) {
                throw new IllegalArgumentException(
                        "Direct path double handler must use requestType=Void.class: " + method
                );
            }
            if (directPathDoubleAnnotation != null && directPathDoubleAnnotation.value().isBlank()) {
                throw new IllegalArgumentException("@DirectPathDouble value must not be blank: " + method);
            }
            if (directPathDoubleAnnotation != null
                    && !pathContainsVariable(routeInfo.path, directPathDoubleAnnotation.value())) {
                throw new IllegalArgumentException(
                        "@DirectPathDouble variable '" + directPathDoubleAnnotation.value()
                                + "' is not present in route path " + routeInfo.path + ": " + method
                );
            }
            if (directPathDoubleAnnotation != null
                    && !validDoubleRange(
                    directPathDoubleAnnotation.min(),
                    directPathDoubleAnnotation.min(),
                    directPathDoubleAnnotation.max()
            )) {
                throw new IllegalArgumentException("@DirectPathDouble min/max must be finite and min <= max: " + method);
            }
            if (directPathShortAnnotation != null && !directShortSignature) {
                throw new IllegalArgumentException(
                        "@DirectPathShort requires handler signature (ByteBuffer out, int offset, short value): " + method
                );
            }
            if (directPathShort && !isVoidRequestType(routeInfo.requestType)) {
                throw new IllegalArgumentException(
                        "Direct path short handler must use requestType=Void.class: " + method
                );
            }
            if (directPathShortAnnotation != null && directPathShortAnnotation.value().isBlank()) {
                throw new IllegalArgumentException("@DirectPathShort value must not be blank: " + method);
            }
            if (directPathShortAnnotation != null
                    && !pathContainsVariable(routeInfo.path, directPathShortAnnotation.value())) {
                throw new IllegalArgumentException(
                        "@DirectPathShort variable '" + directPathShortAnnotation.value()
                                + "' is not present in route path " + routeInfo.path + ": " + method
                );
            }
            if (directPathShortAnnotation != null && directPathShortAnnotation.min() > directPathShortAnnotation.max()) {
                throw new IllegalArgumentException("@DirectPathShort min must be <= max: " + method);
            }
            RawRequestData rawRequestData = method.getAnnotation(RawRequestData.class);
            boolean bodyless = !metadata.needsBody && isVoidRequestType(routeInfo.requestType);
            boolean implicitRawMetadata = directV5 && rawRequestData == null;
            boolean directNeedsPathParams = directV5 && (implicitRawMetadata || rawRequestData.pathParams());
            boolean directNeedsQueryParams = directV5 && (implicitRawMetadata || rawRequestData.query());
            boolean directNeedsHeaders = directV5 && (implicitRawMetadata || rawRequestData.headers());
            String directQueryIntName = directQueryIntAnnotation != null ? directQueryIntAnnotation.value() : "";
            String directQueryLongName = directQueryLongAnnotation != null ? directQueryLongAnnotation.value() : "";
            String directQueryBooleanName = directQueryBooleanAnnotation != null ? directQueryBooleanAnnotation.value() : "";
            String directQueryDoubleName = directQueryDoubleAnnotation != null ? directQueryDoubleAnnotation.value() : "";
            String directQueryShortName = directQueryShortAnnotation != null ? directQueryShortAnnotation.value() : "";
            String directPathIntName = directPathIntAnnotation != null ? directPathIntAnnotation.value() : "";
            String directPathLongName = directPathLongAnnotation != null ? directPathLongAnnotation.value() : "";
            String directPathBooleanName = directPathBooleanAnnotation != null ? directPathBooleanAnnotation.value() : "";
            String directPathDoubleName = directPathDoubleAnnotation != null ? directPathDoubleAnnotation.value() : "";
            String directPathShortName = directPathShortAnnotation != null ? directPathShortAnnotation.value() : "";
            boolean asyncRoute = CompletionStage.class.isAssignableFrom(method.getReturnType());
            int nativeStaticResponseId = nativeStaticResponseId(bean, method, routeInfo, asyncRoute);
            int nativeStaticFileResponseId = nativeStaticFileResponseId(bean, method, routeInfo, asyncRoute);
            if (nativeStaticResponseId > 0 && nativeStaticFileResponseId > 0) {
                throw new IllegalArgumentException(
                        "A route cannot be both @NativeStaticRoute and @NativeStaticFileRoute: " + method
                );
            }

            RouteDef route = new RouteDef(
                    routeInfo.httpMethod,
                    routeInfo.path,
                    handlerId,
                    routeInfo.requestType.getName(),
                    routeInfo.responseType.getName(),
                    bodyless,
                    legacyV4 || directNeedsPathParams || metadata.needsPathParams,
                    legacyV4 || directNeedsQueryParams || metadata.needsQueryParams,
                    legacyV4 || directNeedsHeaders || metadata.needsHeaders,
                    asyncRoute,
                    routeInfo.maxRequestBodyBytes,
                    routeInfo.maxResponseBodyBytes,
                    directQueryIntName,
                    directQueryIntAnnotation != null ? directQueryIntAnnotation.defaultValue() : 0,
                    directQueryIntAnnotation != null ? directQueryIntAnnotation.min() : Integer.MIN_VALUE,
                    directQueryIntAnnotation != null ? directQueryIntAnnotation.max() : Integer.MAX_VALUE,
                    directQueryLongName,
                    directQueryLongAnnotation != null ? directQueryLongAnnotation.defaultValue() : 0L,
                    directQueryLongAnnotation != null ? directQueryLongAnnotation.min() : Long.MIN_VALUE,
                    directQueryLongAnnotation != null ? directQueryLongAnnotation.max() : Long.MAX_VALUE,
                    directQueryBooleanName,
                    directQueryBooleanAnnotation != null && directQueryBooleanAnnotation.defaultValue(),
                    directQueryDoubleName,
                    directQueryDoubleAnnotation != null ? directQueryDoubleAnnotation.defaultValue() : 0.0d,
                    directQueryDoubleAnnotation != null ? directQueryDoubleAnnotation.min() : -Double.MAX_VALUE,
                    directQueryDoubleAnnotation != null ? directQueryDoubleAnnotation.max() : Double.MAX_VALUE,
                    directQueryShortName,
                    directQueryShortAnnotation != null ? directQueryShortAnnotation.defaultValue() : (short) 0,
                    directQueryShortAnnotation != null ? directQueryShortAnnotation.min() : Short.MIN_VALUE,
                    directQueryShortAnnotation != null ? directQueryShortAnnotation.max() : Short.MAX_VALUE,
                    directPathIntName,
                    directPathIntAnnotation != null ? directPathIntAnnotation.min() : Integer.MIN_VALUE,
                    directPathIntAnnotation != null ? directPathIntAnnotation.max() : Integer.MAX_VALUE,
                    directPathLongName,
                    directPathLongAnnotation != null ? directPathLongAnnotation.min() : Long.MIN_VALUE,
                    directPathLongAnnotation != null ? directPathLongAnnotation.max() : Long.MAX_VALUE,
                    directPathBooleanName,
                    directPathDoubleName,
                    directPathDoubleAnnotation != null ? directPathDoubleAnnotation.min() : -Double.MAX_VALUE,
                    directPathDoubleAnnotation != null ? directPathDoubleAnnotation.max() : Double.MAX_VALUE,
                    directPathShortName,
                    directPathShortAnnotation != null ? directPathShortAnnotation.min() : Short.MIN_VALUE,
                    directPathShortAnnotation != null ? directPathShortAnnotation.max() : Short.MAX_VALUE,
                    directBodylessOutput,
                    nativeStaticResponseId,
                    nativeStaticFileResponseId
            );
            routes.add(route);
            RoutePlanRegistry.getInstance().add(RouteExecutionPlan.from(
                    route,
                    bean,
                    method,
                    legacyV4,
                    directV5,
                    directQueryInt,
                    directQueryLong,
                    directQueryBoolean,
                    directQueryDouble,
                    directQueryShort,
                    directPathInt,
                    directPathLong,
                    directPathBoolean,
                    directPathDouble,
                    directPathShort,
                    directBodylessOutput,
                    nativeStaticResponseId,
                    nativeStaticFileResponseId,
                    implicitRawMetadata,
                    HandlerRegistry.getInstance().usesExactInvoker(handlerId)
            ));

            FrameworkLogger.debug("[JAVA] Handler registered: id=" + handlerId +
                    " bean=" + bean.getClass().getName() +
                    " method=" + method.getName() +
                    " reqType=" + routeInfo.requestType.getName() +
                    " respType=" + routeInfo.responseType.getName());
        }
    }

    private static int nativeStaticResponseId(
            Object bean,
            Method method,
            RouteInfo routeInfo,
            boolean asyncRoute
    ) {
        if (!method.isAnnotationPresent(NativeStaticRoute.class)) {
            return 0;
        }
        if (!isVoidRequestType(routeInfo.requestType)) {
            throw new IllegalArgumentException(
                    "@NativeStaticRoute requires requestType=Void.class: " + method
            );
        }
        if (routeInfo.path.contains("{")) {
            throw new IllegalArgumentException(
                    "@NativeStaticRoute does not support path variables: " + method
            );
        }
        if (asyncRoute) {
            throw new IllegalArgumentException(
                    "@NativeStaticRoute cannot be used with CompletionStage handlers: " + method
            );
        }
        if (method.getParameterCount() != 0) {
            throw new IllegalArgumentException(
                    "@NativeStaticRoute requires a no-arg handler returning RawResponse: " + method
            );
        }
        if (method.getReturnType() != RawResponse.class || routeInfo.responseType != RawResponse.class) {
            throw new IllegalArgumentException(
                    "@NativeStaticRoute requires return type and responseType RawResponse.class: " + method
            );
        }

        try {
            method.setAccessible(true);
            Object result = method.invoke(bean);
            if (!(result instanceof RawResponse rawResponse)) {
                throw new IllegalArgumentException(
                        "@NativeStaticRoute handler returned null or non-RawResponse: " + method
                );
            }
            int nativeId = rawResponse.getNativeId();
            if (nativeId <= 0) {
                throw new IllegalArgumentException(
                        "@NativeStaticRoute handler must return RawResponse.registered*: " + method
                );
            }
            return nativeId;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("@NativeStaticRoute startup registration failed: " + method, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("@NativeStaticRoute handler is not accessible: " + method, e);
        }
    }

    private static int nativeStaticFileResponseId(
            Object bean,
            Method method,
            RouteInfo routeInfo,
            boolean asyncRoute
    ) {
        if (!method.isAnnotationPresent(NativeStaticFileRoute.class)) {
            return 0;
        }
        if (!isVoidRequestType(routeInfo.requestType)) {
            throw new IllegalArgumentException(
                    "@NativeStaticFileRoute requires requestType=Void.class: " + method
            );
        }
        if (routeInfo.path.contains("{")) {
            throw new IllegalArgumentException(
                    "@NativeStaticFileRoute does not support path variables: " + method
            );
        }
        if (asyncRoute) {
            throw new IllegalArgumentException(
                    "@NativeStaticFileRoute cannot be used with CompletionStage handlers: " + method
            );
        }
        if (method.getParameterCount() != 0) {
            throw new IllegalArgumentException(
                    "@NativeStaticFileRoute requires a no-arg handler returning FileResponse: " + method
            );
        }
        if (method.getReturnType() != FileResponse.class || routeInfo.responseType != FileResponse.class) {
            throw new IllegalArgumentException(
                    "@NativeStaticFileRoute requires return type and responseType FileResponse.class: " + method
            );
        }

        try {
            method.setAccessible(true);
            Object result = method.invoke(bean);
            if (!(result instanceof FileResponse fileResponse)) {
                throw new IllegalArgumentException(
                        "@NativeStaticFileRoute handler returned null or non-FileResponse: " + method
                );
            }
            if (!Files.isRegularFile(fileResponse.getPath())) {
                throw new IllegalArgumentException(
                        "@NativeStaticFileRoute file does not exist or is not a regular file: "
                                + fileResponse.getAbsolutePath()
                );
            }
            int nativeId = NativeBridge.registerStaticFileResponse(
                    fileResponse.getAbsolutePath(),
                    fileResponse.getEncodedHeadersString(),
                    200,
                    NativeBridge.staticFileInlineMaxBytes()
            );
            if (nativeId <= 0) {
                throw new IllegalStateException(
                        "@NativeStaticFileRoute native registration failed: " + method
                );
            }
            return nativeId;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("@NativeStaticFileRoute startup registration failed: " + method, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("@NativeStaticFileRoute handler is not accessible: " + method, e);
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

    private static boolean pathContainsVariable(String path, String variableName) {
        return path != null && variableName != null && path.contains("{" + variableName + "}");
    }

    private static boolean validDoubleRange(double defaultValue, double min, double max) {
        return Double.isFinite(defaultValue)
                && Double.isFinite(min)
                && Double.isFinite(max)
                && min <= defaultValue
                && defaultValue <= max;
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

    private static boolean isDirectInt(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 3
                && parameterTypes[0] == java.nio.ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == int.class;
    }

    private static boolean isDirectLong(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 3
                && parameterTypes[0] == java.nio.ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == long.class;
    }

    private static boolean isDirectBoolean(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 3
                && parameterTypes[0] == java.nio.ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == boolean.class;
    }

    private static boolean isDirectDouble(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 3
                && parameterTypes[0] == java.nio.ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == double.class;
    }

    private static boolean isDirectShort(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 3
                && parameterTypes[0] == java.nio.ByteBuffer.class
                && parameterTypes[1] == int.class
                && parameterTypes[2] == short.class;
    }

    private static boolean isDirectBodylessOutput(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        return parameterTypes.length == 2
                && parameterTypes[0] == java.nio.ByteBuffer.class
                && parameterTypes[1] == int.class;
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

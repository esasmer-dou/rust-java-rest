package com.reactor.rust.bridge;

import com.reactor.rust.annotations.*;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.http.FileResponse;
import com.reactor.rust.http.JsonProducerResponse;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.json.JsonBodyProducer;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.metrics.Metrics;
import com.reactor.rust.startup.StartupIndex;
import com.reactor.rust.startup.StartupTimeline;
import com.reactor.rust.startup.StartupMode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.Set;
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
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("routes.scan_register")) {
            HandlerRegistry registry = HandlerRegistry.getInstance();
            List<Object> handlers = registry.getHandlers();
            List<RequestGuardFactory> guardFactories = loadRequestGuardFactories();

            List<RouteDef> routes = new ArrayList<>();
            RoutePlanRegistry routePlans = RoutePlanRegistry.getInstance();
            routePlans.clear();
            routePlans.configureFromProperties();

            for (Object bean : handlers) {
                scanHandler(bean, routes, guardFactories);
            }

            validateUniqueRoutes(routes);
            validateRouteIndex(routes);
            registry.freeze();
            routePlans.freeze();
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
    }

    static void validateUniqueRoutes(List<RouteDef> routes) {
        Set<String> routeKeys = new HashSet<>(Math.max(16, routes.size() * 2));
        for (RouteDef route : routes) {
            String key = route.httpMethod.toUpperCase(Locale.ROOT) + " " + route.path;
            if (!routeKeys.add(key)) {
                throw new IllegalStateException("Duplicate HTTP route: " + key);
            }
        }
    }

    private static void validateRouteIndex(List<RouteDef> routes) {
        StartupIndex.IndexResult index = StartupIndex.routeKeys();
        boolean required = PropertiesLoader.getBoolean("reactor.startup.route-index.required", false);
        boolean validate = PropertiesLoader.getBoolean("reactor.startup.route-index.validate", false);
        if (!index.present()) {
            if (required) {
                throw new IllegalStateException("Required startup route index is missing: "
                        + StartupIndex.ROUTES_RESOURCE);
            }
            return;
        }
        Metrics.getInstance().setGauge("reactor.startup.route_index.routes", index.entries().size());
        if (!validate && !required) {
            FrameworkLogger.info("[RouteScanner] Route index detected: routes=" + index.entries().size());
            return;
        }

        Set<String> actual = new HashSet<>();
        for (RouteDef route : routes) {
            actual.add((route.httpMethod + " " + route.path).toUpperCase(java.util.Locale.ROOT));
        }
        Set<String> expectedRoutes = new HashSet<>();
        for (String route : index.entries()) {
            expectedRoutes.add(route.toUpperCase(java.util.Locale.ROOT));
        }
        List<String> missing = new ArrayList<>();
        for (String expected : index.entries()) {
            if (!actual.contains(expected.toUpperCase(java.util.Locale.ROOT))) {
                missing.add(expected);
            }
        }
        List<String> unexpected = new ArrayList<>();
        for (String route : actual) {
            if (!expectedRoutes.contains(route)) {
                unexpected.add(route);
            }
        }
        Metrics.getInstance().setGauge("reactor.startup.route_index.missing", missing.size());
        Metrics.getInstance().setGauge("reactor.startup.route_index.unexpected", unexpected.size());
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            String message = "Route index mismatch; missing routes=" + missing
                    + " unexpected routes=" + unexpected;
            if (required) {
                throw new IllegalStateException(message);
            }
            FrameworkLogger.warn("[RouteScanner] " + message);
        }
    }

    /**
     * Scan a single handler for route annotations
     */
    private static void scanHandler(
            Object bean,
            List<RouteDef> routes,
            List<RequestGuardFactory> guardFactories) {
        Class<?> clazz = bean.getClass();

        // Get base path from class-level @RequestMapping
        String basePath = "";
        RequestMapping classMapping = clazz.getAnnotation(RequestMapping.class);
        if (classMapping != null) {
            basePath = classMapping.value();
        } else {
            RestController controller = clazz.getAnnotation(RestController.class);
            if (controller != null) {
                basePath = controller.value();
            }
        }
        if (!basePath.isEmpty()) {
            if (!basePath.isEmpty() && !basePath.startsWith("/")) {
                basePath = "/" + basePath;
            }
            if (basePath.endsWith("/")) {
                basePath = basePath.substring(0, basePath.length() - 1);
            }
        }

        Method[] generatedMethods = GeneratedRouteInvokers.routeMethods(clazz);
        Method[] routeCandidates;
        if (generatedMethods.length > 0) {
            routeCandidates = generatedMethods;
            Metrics.getInstance().increment("reactor.startup.generated_route_owners");
            Metrics.getInstance().increment("reactor.startup.generated_route_methods", generatedMethods.length);
        } else {
            if (StartupMode.isAot()
                    || PropertiesLoader.getBoolean("reactor.startup.generated-routes.required", false)) {
                throw new IllegalStateException(
                        "Build-time route metadata is required but missing for " + clazz.getName());
            }
            routeCandidates = clazz.getDeclaredMethods();
            Metrics.getInstance().increment("reactor.startup.reflection_route_owners");
        }

        for (Method method : routeCandidates) {
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
            boolean directIntSignature = isDirectInt(method) || isDirectScalarIntProducer(method);
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
            GeneratedPrimitiveBinding generatedBinding = GeneratedPrimitiveBindings.find(method);
            boolean generatedQuery = generatedBinding != null
                    && generatedBinding.source() == GeneratedPrimitiveBinding.Source.QUERY;
            boolean generatedPath = generatedBinding != null
                    && generatedBinding.source() == GeneratedPrimitiveBinding.Source.PATH;
            boolean directQueryInt = directQueryIntAnnotation != null
                    || generatedKind(generatedBinding, generatedQuery, GeneratedPrimitiveBinding.Kind.INT);
            boolean directQueryLong = directQueryLongAnnotation != null
                    || generatedKind(generatedBinding, generatedQuery, GeneratedPrimitiveBinding.Kind.LONG);
            boolean directQueryBoolean = directQueryBooleanAnnotation != null
                    || generatedKind(generatedBinding, generatedQuery, GeneratedPrimitiveBinding.Kind.BOOLEAN);
            boolean directQueryDouble = directQueryDoubleAnnotation != null
                    || generatedKind(generatedBinding, generatedQuery, GeneratedPrimitiveBinding.Kind.DOUBLE);
            boolean directQueryShort = directQueryShortAnnotation != null
                    || generatedKind(generatedBinding, generatedQuery, GeneratedPrimitiveBinding.Kind.SHORT);
            boolean directPathInt = directPathIntAnnotation != null
                    || generatedKind(generatedBinding, generatedPath, GeneratedPrimitiveBinding.Kind.INT);
            boolean directPathLong = directPathLongAnnotation != null
                    || generatedKind(generatedBinding, generatedPath, GeneratedPrimitiveBinding.Kind.LONG);
            boolean directPathBoolean = directPathBooleanAnnotation != null
                    || generatedKind(generatedBinding, generatedPath, GeneratedPrimitiveBinding.Kind.BOOLEAN);
            boolean directPathDouble = directPathDoubleAnnotation != null
                    || generatedKind(generatedBinding, generatedPath, GeneratedPrimitiveBinding.Kind.DOUBLE);
            boolean directPathShort = directPathShortAnnotation != null
                    || generatedKind(generatedBinding, generatedPath, GeneratedPrimitiveBinding.Kind.SHORT);
            boolean directPrimitiveOutput = (directQueryInt && isDirectInt(method))
                    || (directQueryLong && isDirectLong(method))
                    || (directQueryBoolean && isDirectBoolean(method))
                    || (directQueryDouble && isDirectDouble(method))
                    || (directQueryShort && isDirectShort(method))
                    || (directPathInt && isDirectInt(method))
                    || (directPathLong && isDirectLong(method))
                    || (directPathBoolean && isDirectBoolean(method))
                    || (directPathDouble && isDirectDouble(method))
                    || (directPathShort && isDirectShort(method));
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
                        "@DirectQueryInt requires handler signature (ByteBuffer out, int offset, int value) "
                                + "or JsonProducerResponse/JsonBodyProducer/RawResponse handler(int value): " + method
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
                        "@DirectPathInt requires handler signature (ByteBuffer out, int offset, int value) "
                                + "or JsonProducerResponse/JsonBodyProducer/RawResponse handler(int value): " + method
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
            String generatedName = generatedBinding == null ? "" : generatedBinding.name();
            String directQueryIntName = directQueryIntAnnotation != null ? directQueryIntAnnotation.value()
                    : directQueryInt ? generatedName : "";
            String directQueryLongName = directQueryLongAnnotation != null ? directQueryLongAnnotation.value()
                    : directQueryLong ? generatedName : "";
            String directQueryBooleanName = directQueryBooleanAnnotation != null ? directQueryBooleanAnnotation.value()
                    : directQueryBoolean ? generatedName : "";
            String directQueryDoubleName = directQueryDoubleAnnotation != null ? directQueryDoubleAnnotation.value()
                    : directQueryDouble ? generatedName : "";
            String directQueryShortName = directQueryShortAnnotation != null ? directQueryShortAnnotation.value()
                    : directQueryShort ? generatedName : "";
            String directPathIntName = directPathIntAnnotation != null ? directPathIntAnnotation.value()
                    : directPathInt ? generatedName : "";
            String directPathLongName = directPathLongAnnotation != null ? directPathLongAnnotation.value()
                    : directPathLong ? generatedName : "";
            String directPathBooleanName = directPathBooleanAnnotation != null ? directPathBooleanAnnotation.value()
                    : directPathBoolean ? generatedName : "";
            String directPathDoubleName = directPathDoubleAnnotation != null ? directPathDoubleAnnotation.value()
                    : directPathDouble ? generatedName : "";
            String directPathShortName = directPathShortAnnotation != null ? directPathShortAnnotation.value()
                    : directPathShort ? generatedName : "";
            boolean asyncRoute = CompletionStage.class.isAssignableFrom(method.getReturnType());
            int nativeStaticResponseId = nativeStaticResponseId(bean, method, routeInfo, asyncRoute);
            int nativeStaticFileResponseId = nativeStaticFileResponseId(bean, method, routeInfo, asyncRoute);
            if (nativeStaticResponseId > 0 && nativeStaticFileResponseId > 0) {
                throw new IllegalArgumentException(
                        "A route cannot be both @NativeStaticRoute and @NativeStaticFileRoute: " + method
                );
            }
            RequestGuard requestGuard = requestGuard(guardFactories, bean.getClass(), method);
            if (requestGuard != null) {
                validateGuardCompatibleRoute(
                        method,
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
                        nativeStaticFileResponseId
                );
                HandlerRegistry.getInstance().attachGuard(handlerId, requestGuard);
            }
            RouteWorkload workload = effectiveRouteWorkload(bean.getClass(), method);
            boolean benchmarkOnly = method.isAnnotationPresent(BenchmarkOnlyRoute.class);
            RouteWorkload.Type workloadType = routeWorkloadType(
                    workload,
                    routeInfo,
                    nativeStaticResponseId,
                    nativeStaticFileResponseId
            );
            String workloadBudget = routeBudgetKey(workload, workloadType);
            RouteAdmissionConfig admission = routeAdmissionConfig(method, routeInfo, workloadType, workloadBudget);
            JniQueueAdmissionConfig jniAdmission = jniQueueAdmissionConfig(method, routeInfo, workloadType, workloadBudget);

            RouteDef route = new RouteDef(
                    routeInfo.httpMethod,
                    routeInfo.path,
                    handlerId,
                    routeInfo.requestType.getName(),
                    routeInfo.responseType.getName(),
                    bodyless,
                    legacyV4 || directNeedsPathParams || (metadata.needsPathParams && !generatedPath),
                    legacyV4 || directNeedsQueryParams || (metadata.needsQueryParams && !generatedQuery),
                    requestGuard != null || legacyV4 || directNeedsHeaders || metadata.needsHeaders,
                    asyncRoute,
                    routeInfo.maxRequestBodyBytes,
                    routeInfo.maxResponseBodyBytes,
                    directQueryIntName,
                    directQueryIntAnnotation != null ? directQueryIntAnnotation.defaultValue()
                            : generatedIntDefault(generatedBinding),
                    directQueryIntAnnotation != null ? directQueryIntAnnotation.min() : Integer.MIN_VALUE,
                    directQueryIntAnnotation != null ? directQueryIntAnnotation.max() : Integer.MAX_VALUE,
                    directQueryLongName,
                    directQueryLongAnnotation != null ? directQueryLongAnnotation.defaultValue()
                            : generatedLongDefault(generatedBinding),
                    directQueryLongAnnotation != null ? directQueryLongAnnotation.min() : Long.MIN_VALUE,
                    directQueryLongAnnotation != null ? directQueryLongAnnotation.max() : Long.MAX_VALUE,
                    directQueryBooleanName,
                    directQueryBooleanAnnotation != null ? directQueryBooleanAnnotation.defaultValue()
                            : generatedBooleanDefault(generatedBinding),
                    directQueryDoubleName,
                    directQueryDoubleAnnotation != null ? directQueryDoubleAnnotation.defaultValue()
                            : generatedDoubleDefault(generatedBinding),
                    directQueryDoubleAnnotation != null ? directQueryDoubleAnnotation.min() : -Double.MAX_VALUE,
                    directQueryDoubleAnnotation != null ? directQueryDoubleAnnotation.max() : Double.MAX_VALUE,
                    directQueryShortName,
                    directQueryShortAnnotation != null ? directQueryShortAnnotation.defaultValue()
                            : generatedShortDefault(generatedBinding),
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
                    generatedBinding == null ? 0 : generatedBinding.mode().nativeValue(),
                    directBodylessOutput,
                    nativeStaticResponseId,
                    nativeStaticFileResponseId,
                    admission.maxConcurrent,
                    admission.queueTimeoutMs,
                    jniAdmission.maxPending,
                    jniAdmission.queueTimeoutMs
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
                    directPrimitiveOutput,
                    nativeStaticResponseId,
                    nativeStaticFileResponseId,
                    workloadType.name(),
                    workloadBudget,
                    benchmarkOnly,
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

    private static List<RequestGuardFactory> loadRequestGuardFactories() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = RouteScanner.class.getClassLoader();
        List<RequestGuardFactory> factories = ServiceLoader.load(RequestGuardFactory.class, loader).stream()
                .map(ServiceLoader.Provider::get)
                .sorted(java.util.Comparator.comparingInt(RequestGuardFactory::order))
                .toList();
        if (!factories.isEmpty()) {
            Metrics.getInstance().setGauge("reactor.startup.request_guard_factories", factories.size());
        }
        return factories;
    }

    static RequestGuard requestGuard(
            List<RequestGuardFactory> factories,
            Class<?> owner,
            Method method) {
        if (factories == null || factories.isEmpty()) return null;
        ArrayList<RequestGuard> guards = new ArrayList<>(factories.size());
        for (RequestGuardFactory factory : factories) {
            RequestGuard guard = factory.create(owner, method);
            if (guard != null) guards.add(guard);
        }
        if (guards.isEmpty()) return null;
        if (guards.size() == 1) return guards.get(0);
        return new CompositeRequestGuard(guards.toArray(RequestGuard[]::new));
    }

    static void validateGuardCompatibleRoute(
            Method method,
            boolean directQueryInt,
            boolean directQueryLong,
            boolean directQueryBoolean,
            boolean directQueryDouble,
            boolean directQueryShort,
            boolean directPathInt,
            boolean directPathLong,
            boolean directPathBoolean,
            boolean directPathDouble,
            boolean directPathShort,
            boolean directBodylessOutput,
            int nativeStaticResponseId,
            int nativeStaticFileResponseId) {
        boolean specializedWithoutHeaders = directQueryInt
                || directQueryLong
                || directQueryBoolean
                || directQueryDouble
                || directQueryShort
                || directPathInt
                || directPathLong
                || directPathBoolean
                || directPathDouble
                || directPathShort
                || directBodylessOutput
                || nativeStaticResponseId > 0
                || nativeStaticFileResponseId > 0;
        if (specializedWithoutHeaders) {
            throw new IllegalStateException(
                    "Guarded route requires request headers but its specialized native path does not carry them: "
                            + method + ". Use a normal generated handler route or remove the route guard."
            );
        }
    }

    private static final class CompositeRequestGuard implements RequestGuard {
        private final RequestGuard[] guards;
        private final ThreadLocal<Integer> entered = ThreadLocal.withInitial(() -> 0);

        private CompositeRequestGuard(RequestGuard[] guards) {
            this.guards = guards;
        }

        @Override
        public void before(RequestGuardContext request) {
            int completed = 0;
            try {
                for (; completed < guards.length; completed++) {
                    guards[completed].before(request);
                }
                entered.set(completed);
            } catch (Throwable failure) {
                for (int index = completed - 1; index >= 0; index--) {
                    try {
                        guards[index].after(failure);
                    } catch (Throwable cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                entered.remove();
                throw failure;
            }
        }

        @Override
        public void after() {
            after(null);
        }

        @Override
        public void after(Throwable invocationFailure) {
            int completed = entered.get();
            entered.remove();
            Throwable failure = null;
            for (int index = completed - 1; index >= 0; index--) {
                try {
                    guards[index].after(invocationFailure);
                } catch (Throwable cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure instanceof RuntimeException runtimeException) throw runtimeException;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("Request guard cleanup failed", failure);
        }

        @Override
        public <T> java.util.concurrent.CompletionStage<T> afterAsync(
                java.util.concurrent.CompletionStage<T> stage) {
            int completed = entered.get();
            entered.remove();
            java.util.concurrent.CompletionStage<T> result = stage;
            for (int index = completed - 1; index >= 0; index--) {
                try {
                    result = guards[index].afterAsync(result);
                } catch (Throwable failure) {
                    for (int remaining = index - 1; remaining >= 0; remaining--) {
                        try {
                            guards[remaining].after(failure);
                        } catch (Throwable cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                    return java.util.concurrent.CompletableFuture.failedFuture(failure);
                }
            }
            return result;
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
            return inferredRoute(method,
                    rustRoute.method(),
                    rustRoute.path(),
                    rustRoute.requestType(),
                    rustRoute.responseType()
            );
        }

        // @GetMapping
        GetMapping getMapping = method.getAnnotation(GetMapping.class);
        if (getMapping != null) {
            return inferredRoute(method,
                    "GET",
                    buildPath(basePath, getMapping.value()),
                    getMapping.requestType(),
                    getMapping.responseType()
            );
        }

        // @PostMapping
        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        if (postMapping != null) {
            return inferredRoute(method,
                    "POST",
                    buildPath(basePath, postMapping.value()),
                    postMapping.requestType(),
                    postMapping.responseType()
            );
        }

        // @PutMapping
        PutMapping putMapping = method.getAnnotation(PutMapping.class);
        if (putMapping != null) {
            return inferredRoute(method,
                    "PUT",
                    buildPath(basePath, putMapping.value()),
                    putMapping.requestType(),
                    putMapping.responseType()
            );
        }

        // @DeleteMapping
        DeleteMapping deleteMapping = method.getAnnotation(DeleteMapping.class);
        if (deleteMapping != null) {
            return inferredRoute(method,
                    "DELETE",
                    buildPath(basePath, deleteMapping.value()),
                    deleteMapping.requestType(),
                    deleteMapping.responseType()
            );
        }

        // @PatchMapping
        PatchMapping patchMapping = method.getAnnotation(PatchMapping.class);
        if (patchMapping != null) {
            return inferredRoute(method,
                    "PATCH",
                    buildPath(basePath, patchMapping.value()),
                    patchMapping.requestType(),
                    patchMapping.responseType()
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

    private static RouteInfo inferredRoute(
            Method method,
            String httpMethod,
            String path,
            Class<?> declaredRequestType,
            Class<?> declaredResponseType) {
        return withLimits(method, new RouteInfo(
                httpMethod,
                path,
                inferRequestType(method, declaredRequestType),
                inferResponseType(method, declaredResponseType)
        ));
    }

    private static Class<?> inferRequestType(Method method, Class<?> declaredType) {
        if (!isVoidRequestType(declaredType)) {
            return declaredType;
        }
        for (java.lang.reflect.Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(RequestBody.class)) {
                return parameter.getType();
            }
        }
        return Void.class;
    }

    private static Class<?> inferResponseType(Method method, Class<?> declaredType) {
        if (declaredType != Void.class && declaredType != void.class) {
            return declaredType;
        }
        return rawResponseClass(method.getGenericReturnType());
    }

    private static Class<?> rawResponseClass(Type type) {
        if (type instanceof Class<?> rawClass) {
            return rawClass == void.class ? Void.class : box(rawClass);
        }
        if (!(type instanceof ParameterizedType parameterized)) {
            return Object.class;
        }
        Type rawType = parameterized.getRawType();
        if (rawType instanceof Class<?> rawClass
                && (CompletionStage.class.isAssignableFrom(rawClass)
                || com.reactor.rust.http.ResponseEntity.class.isAssignableFrom(rawClass))) {
            Type[] arguments = parameterized.getActualTypeArguments();
            return arguments.length == 1 ? rawResponseClass(arguments[0]) : Object.class;
        }
        return rawType instanceof Class<?> rawClass ? rawClass : Object.class;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == float.class) return Float.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private static boolean isVoidRequestType(Class<?> requestType) {
        return requestType == Void.class || requestType == void.class;
    }

    private static boolean generatedKind(
            GeneratedPrimitiveBinding binding,
            boolean sourceMatches,
            GeneratedPrimitiveBinding.Kind kind) {
        return sourceMatches && binding.kind() == kind;
    }

    private static int generatedIntDefault(GeneratedPrimitiveBinding binding) {
        return binding == null || binding.defaultValue().isEmpty()
                ? 0
                : Integer.parseInt(binding.defaultValue());
    }

    private static long generatedLongDefault(GeneratedPrimitiveBinding binding) {
        return binding == null || binding.defaultValue().isEmpty()
                ? 0L
                : Long.parseLong(binding.defaultValue());
    }

    private static boolean generatedBooleanDefault(GeneratedPrimitiveBinding binding) {
        return binding != null && !binding.defaultValue().isEmpty()
                && Boolean.parseBoolean(binding.defaultValue());
    }

    private static double generatedDoubleDefault(GeneratedPrimitiveBinding binding) {
        return binding == null || binding.defaultValue().isEmpty()
                ? 0.0d
                : Double.parseDouble(binding.defaultValue());
    }

    private static short generatedShortDefault(GeneratedPrimitiveBinding binding) {
        return binding == null || binding.defaultValue().isEmpty()
                ? (short) 0
                : Short.parseShort(binding.defaultValue());
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

    private static boolean isDirectScalarIntProducer(Method method) {
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

    private static RouteWorkload.Type routeWorkloadType(
            RouteWorkload workload,
            RouteInfo routeInfo,
            int nativeStaticResponseId,
            int nativeStaticFileResponseId
    ) {
        if (workload != null) {
            return workload.value();
        }
        if (nativeStaticFileResponseId > 0 || FileResponse.class.isAssignableFrom(routeInfo.responseType)) {
            return RouteWorkload.Type.FILE_STREAM;
        }
        if (nativeStaticResponseId > 0 || RawResponse.class.isAssignableFrom(routeInfo.responseType)) {
            return RouteWorkload.Type.RAW_STATIC;
        }
        return RouteWorkload.Type.STANDARD;
    }

    static RouteWorkload effectiveRouteWorkload(Class<?> handlerType, Method method) {
        RouteWorkload methodWorkload = method.getAnnotation(RouteWorkload.class);
        return methodWorkload != null ? methodWorkload : handlerType.getAnnotation(RouteWorkload.class);
    }

    private static String routeBudgetKey(RouteWorkload workload, RouteWorkload.Type workloadType) {
        if (workload != null && !workload.budget().isBlank()) {
            return configKey(workload.budget());
        }
        if (workloadType == RouteWorkload.Type.STANDARD) {
            return "";
        }
        return workloadKey(workloadType);
    }

    private static RouteAdmissionConfig routeAdmissionConfig(
            Method method,
            RouteInfo routeInfo,
            RouteWorkload.Type workloadType,
            String workloadBudget
    ) {
        if (!PropertiesLoader.getBoolean("reactor.rust.route-admission.enabled", true)) {
            return RouteAdmissionConfig.DISABLED;
        }

        RouteAdmission annotation = method.getAnnotation(RouteAdmission.class);
        int maxConcurrent = annotation != null ? annotation.maxConcurrent() : 0;
        int queueTimeoutMs = annotation != null ? annotation.queueTimeoutMs() : 0;

        if (maxConcurrent < 0) {
            maxConcurrent = PropertiesLoader.getInt(
                    "reactor.rust.route-admission.default-max-concurrent",
                    0
            );
        }
        if (queueTimeoutMs < 0) {
            queueTimeoutMs = PropertiesLoader.getInt(
                    "reactor.rust.route-admission.default-queue-timeout-ms",
                    0
            );
        }

        String workloadPrefix = "reactor.rust.route-workload." + workloadKey(workloadType)
                + ".route-admission";
        maxConcurrent = getOptionalInt(workloadPrefix + ".max-concurrent", maxConcurrent);
        queueTimeoutMs = getOptionalInt(workloadPrefix + ".queue-timeout-ms", queueTimeoutMs);

        if (workloadBudget != null && !workloadBudget.isBlank()) {
            String budgetPrefix = "reactor.rust.route-budget." + workloadBudget + ".route-admission";
            maxConcurrent = getOptionalInt(budgetPrefix + ".max-concurrent", maxConcurrent);
            queueTimeoutMs = getOptionalInt(budgetPrefix + ".queue-timeout-ms", queueTimeoutMs);
        }

        String prefix = "reactor.rust.route-admission." + routeAdmissionKey(routeInfo);
        maxConcurrent = PropertiesLoader.getInt(prefix + ".max-concurrent", maxConcurrent);
        queueTimeoutMs = PropertiesLoader.getInt(prefix + ".queue-timeout-ms", queueTimeoutMs);

        maxConcurrent = Math.max(0, maxConcurrent);
        queueTimeoutMs = Math.max(0, queueTimeoutMs);
        if (maxConcurrent == 0) {
            queueTimeoutMs = 0;
        }
        return new RouteAdmissionConfig(maxConcurrent, queueTimeoutMs);
    }

    private static JniQueueAdmissionConfig jniQueueAdmissionConfig(
            Method method,
            RouteInfo routeInfo,
            RouteWorkload.Type workloadType,
            String workloadBudget
    ) {
        if (!PropertiesLoader.getBoolean("reactor.rust.jni-admission.enabled", true)) {
            return JniQueueAdmissionConfig.DISABLED;
        }

        JniQueueAdmission annotation = method.getAnnotation(JniQueueAdmission.class);
        int maxPending = annotation != null ? annotation.maxPending() : 0;
        int queueTimeoutMs = annotation != null ? annotation.queueTimeoutMs() : 0;

        if (maxPending < 0) {
            maxPending = PropertiesLoader.getInt(
                    "reactor.rust.jni-admission.default-max-pending",
                    0
            );
        }
        if (queueTimeoutMs < 0) {
            queueTimeoutMs = PropertiesLoader.getInt(
                    "reactor.rust.jni-admission.default-queue-timeout-ms",
                    0
            );
        }

        String workloadPrefix = "reactor.rust.route-workload." + workloadKey(workloadType)
                + ".jni-admission";
        maxPending = getOptionalInt(workloadPrefix + ".max-pending", maxPending);
        queueTimeoutMs = getOptionalInt(workloadPrefix + ".queue-timeout-ms", queueTimeoutMs);

        if (workloadBudget != null && !workloadBudget.isBlank()) {
            String budgetPrefix = "reactor.rust.route-budget." + workloadBudget + ".jni-admission";
            maxPending = getOptionalInt(budgetPrefix + ".max-pending", maxPending);
            queueTimeoutMs = getOptionalInt(budgetPrefix + ".queue-timeout-ms", queueTimeoutMs);
        }

        String prefix = "reactor.rust.jni-admission." + routeAdmissionKey(routeInfo);
        maxPending = PropertiesLoader.getInt(prefix + ".max-pending", maxPending);
        queueTimeoutMs = PropertiesLoader.getInt(prefix + ".queue-timeout-ms", queueTimeoutMs);

        maxPending = Math.max(0, maxPending);
        queueTimeoutMs = Math.max(0, queueTimeoutMs);
        if (maxPending == 0) {
            queueTimeoutMs = 0;
        }
        return new JniQueueAdmissionConfig(maxPending, queueTimeoutMs);
    }

    private static int getOptionalInt(String key, int fallback) {
        String value = PropertiesLoader.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return PropertiesLoader.getInt(key, fallback);
    }

    private static String workloadKey(RouteWorkload.Type workloadType) {
        return workloadType.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String configKey(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        return normalized.isEmpty() ? "route" : normalized;
    }

    private static String routeAdmissionKey(RouteInfo routeInfo) {
        String raw = (routeInfo.httpMethod + "." + routeInfo.path)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", ".");
        raw = raw.replaceAll("^\\.+|\\.+$", "");
        return raw.isEmpty() ? "route" : raw;
    }

    private static final class RouteAdmissionConfig {
        static final RouteAdmissionConfig DISABLED = new RouteAdmissionConfig(0, 0);

        final int maxConcurrent;
        final int queueTimeoutMs;

        RouteAdmissionConfig(int maxConcurrent, int queueTimeoutMs) {
            this.maxConcurrent = maxConcurrent;
            this.queueTimeoutMs = queueTimeoutMs;
        }
    }

    private static final class JniQueueAdmissionConfig {
        static final JniQueueAdmissionConfig DISABLED = new JniQueueAdmissionConfig(0, 0);

        final int maxPending;
        final int queueTimeoutMs;

        JniQueueAdmissionConfig(int maxPending, int queueTimeoutMs) {
            this.maxPending = maxPending;
            this.queueTimeoutMs = queueTimeoutMs;
        }
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

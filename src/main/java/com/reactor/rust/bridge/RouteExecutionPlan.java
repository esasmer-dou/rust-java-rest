package com.reactor.rust.bridge;

import com.reactor.rust.http.DirectJsonResponse;
import com.reactor.rust.http.FileResponse;
import com.reactor.rust.http.JsonProducerResponse;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.json.JsonBodyProducer;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Locale;

/**
 * Startup-time execution plan for a route.
 *
 * <p>This is intentionally not a per-request abstraction. It exists to make
 * fast-path and legacy-path decisions visible before production traffic starts.</p>
 */
public final class RouteExecutionPlan {

    public enum Strategy {
        NATIVE_STATIC_RESPONSE(true),
        NATIVE_STATIC_FILE(true),
        DIRECT_QUERY_INT(true),
        DIRECT_QUERY_LONG(true),
        DIRECT_QUERY_BOOLEAN(true),
        DIRECT_QUERY_DOUBLE(true),
        DIRECT_QUERY_SHORT(true),
        DIRECT_PATH_INT(true),
        DIRECT_PATH_LONG(true),
        DIRECT_PATH_BOOLEAN(true),
        DIRECT_PATH_DOUBLE(true),
        DIRECT_PATH_SHORT(true),
        DIRECT_BUFFER(true),
        DIRECT_BUFFER_WITH_IMPLICIT_RAW_METADATA(true),
        DIRECT_BODYLESS_OUTPUT(true),
        DIRECT_JSON_RESPONSE(true),
        EXACT_ANNOTATED(true),
        EXACT_BODYLESS(true),
        ASYNC_COMPLETION_STAGE(true),
        LEGACY_RAW_V4(false);

        private final boolean optimized;

        Strategy(boolean optimized) {
            this.optimized = optimized;
        }

        public boolean optimized() {
            return optimized;
        }
    }

    public final String httpMethod;
    public final String path;
    public final int handlerId;
    public final String handlerClass;
    public final String methodName;
    public final String requestType;
    public final String responseType;
    public final String workload;
    public final String workloadBudget;
    public final Strategy strategy;
    public final String reason;
    public final boolean bodyless;
    public final boolean needsPathParams;
    public final boolean needsQueryParams;
    public final boolean needsHeaders;
    public final boolean asyncRoute;
    public final boolean directQueryInt;
    public final boolean directQueryLong;
    public final boolean directQueryBoolean;
    public final boolean directQueryDouble;
    public final boolean directQueryShort;
    public final boolean directPathInt;
    public final boolean directPathLong;
    public final boolean directPathBoolean;
    public final boolean directPathDouble;
    public final boolean directPathShort;
    public final boolean directBuffer;
    public final boolean directBodylessOutput;
    public final boolean directJsonResponse;
    public final boolean directPrimitiveOutput;
    public final int nativeStaticResponseId;
    public final int nativeStaticFileResponseId;
    public final int admissionMaxConcurrent;
    public final int admissionQueueTimeoutMs;
    public final int jniAdmissionMaxPending;
    public final int jniAdmissionQueueTimeoutMs;
    public final boolean legacyV4;
    public final boolean compiledInvoker;
    public final boolean exactInvoker;
    public final boolean benchmarkOnly;

    private RouteExecutionPlan(
            String httpMethod,
            String path,
            int handlerId,
            String handlerClass,
            String methodName,
            String requestType,
            String responseType,
            String workload,
            String workloadBudget,
            Strategy strategy,
            String reason,
            boolean bodyless,
            boolean needsPathParams,
            boolean needsQueryParams,
            boolean needsHeaders,
            boolean asyncRoute,
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
            boolean directBuffer,
            boolean directBodylessOutput,
            boolean directJsonResponse,
            boolean directPrimitiveOutput,
            int nativeStaticResponseId,
            int nativeStaticFileResponseId,
            int admissionMaxConcurrent,
            int admissionQueueTimeoutMs,
            int jniAdmissionMaxPending,
            int jniAdmissionQueueTimeoutMs,
            boolean legacyV4,
            boolean compiledInvoker,
            boolean exactInvoker,
            boolean benchmarkOnly
    ) {
        this.httpMethod = httpMethod;
        this.path = path;
        this.handlerId = handlerId;
        this.handlerClass = handlerClass;
        this.methodName = methodName;
        this.requestType = requestType;
        this.responseType = responseType;
        this.workload = workload == null || workload.isBlank() ? "STANDARD" : workload;
        this.workloadBudget = workloadBudget == null ? "" : workloadBudget;
        this.strategy = strategy;
        this.reason = reason;
        this.bodyless = bodyless;
        this.needsPathParams = needsPathParams;
        this.needsQueryParams = needsQueryParams;
        this.needsHeaders = needsHeaders;
        this.asyncRoute = asyncRoute;
        this.directQueryInt = directQueryInt;
        this.directQueryLong = directQueryLong;
        this.directQueryBoolean = directQueryBoolean;
        this.directQueryDouble = directQueryDouble;
        this.directQueryShort = directQueryShort;
        this.directPathInt = directPathInt;
        this.directPathLong = directPathLong;
        this.directPathBoolean = directPathBoolean;
        this.directPathDouble = directPathDouble;
        this.directPathShort = directPathShort;
        this.directBuffer = directBuffer;
        this.directBodylessOutput = directBodylessOutput;
        this.directJsonResponse = directJsonResponse;
        this.directPrimitiveOutput = directPrimitiveOutput;
        this.nativeStaticResponseId = nativeStaticResponseId;
        this.nativeStaticFileResponseId = nativeStaticFileResponseId;
        this.admissionMaxConcurrent = admissionMaxConcurrent;
        this.admissionQueueTimeoutMs = admissionQueueTimeoutMs;
        this.jniAdmissionMaxPending = jniAdmissionMaxPending;
        this.jniAdmissionQueueTimeoutMs = jniAdmissionQueueTimeoutMs;
        this.legacyV4 = legacyV4;
        this.compiledInvoker = compiledInvoker;
        this.exactInvoker = exactInvoker;
        this.benchmarkOnly = benchmarkOnly;
    }

    static RouteExecutionPlan from(
            RouteDef route,
            Object bean,
            Method method,
            boolean legacyV4,
            boolean directV5,
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
            boolean implicitRawMetadata,
            boolean exactInvoker
    ) {
        return from(route, bean, method, legacyV4, directV5,
                directQueryInt, directQueryLong, directQueryBoolean,
                directQueryDouble, directQueryShort,
                directPathInt, directPathLong, directPathBoolean,
                directPathDouble, directPathShort,
                directBodylessOutput, false, 0, 0, "STANDARD", "", false, implicitRawMetadata, exactInvoker);
    }

    static RouteExecutionPlan from(
            RouteDef route,
            Object bean,
            Method method,
            boolean legacyV4,
            boolean directV5,
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
            boolean directPrimitiveOutput,
            int nativeStaticResponseId,
            int nativeStaticFileResponseId,
            String workload,
            String workloadBudget,
            boolean benchmarkOnly,
            boolean implicitRawMetadata,
            boolean exactInvoker
    ) {
        Strategy strategy;
        String reason;
        boolean directJsonResponse = returnsDirectJsonResponse(method);

        if (nativeStaticResponseId > 0) {
            strategy = Strategy.NATIVE_STATIC_RESPONSE;
            reason = "rust_serves_registered_native_response_without_java_handler";
        } else if (nativeStaticFileResponseId > 0) {
            strategy = Strategy.NATIVE_STATIC_FILE;
            reason = "rust_streams_registered_file_without_java_handler";
        } else if (legacyV4) {
            strategy = Strategy.LEGACY_RAW_V4;
            reason = "legacy_raw_v4_signature";
        } else if (directQueryInt) {
            strategy = Strategy.DIRECT_QUERY_INT;
            reason = "rust_parses_query_int_and_java_writes_direct_response";
        } else if (directQueryLong) {
            strategy = Strategy.DIRECT_QUERY_LONG;
            reason = "rust_parses_query_long_and_java_writes_direct_response";
        } else if (directQueryBoolean) {
            strategy = Strategy.DIRECT_QUERY_BOOLEAN;
            reason = "rust_parses_query_boolean_and_java_writes_direct_response";
        } else if (directQueryDouble) {
            strategy = Strategy.DIRECT_QUERY_DOUBLE;
            reason = "rust_parses_query_double_and_java_writes_direct_response";
        } else if (directQueryShort) {
            strategy = Strategy.DIRECT_QUERY_SHORT;
            reason = "rust_parses_query_short_and_java_writes_direct_response";
        } else if (directPathInt) {
            strategy = Strategy.DIRECT_PATH_INT;
            reason = "rust_parses_path_int_and_java_writes_direct_response";
        } else if (directPathLong) {
            strategy = Strategy.DIRECT_PATH_LONG;
            reason = "rust_parses_path_long_and_java_writes_direct_response";
        } else if (directPathBoolean) {
            strategy = Strategy.DIRECT_PATH_BOOLEAN;
            reason = "rust_parses_path_boolean_and_java_writes_direct_response";
        } else if (directPathDouble) {
            strategy = Strategy.DIRECT_PATH_DOUBLE;
            reason = "rust_parses_path_double_and_java_writes_direct_response";
        } else if (directPathShort) {
            strategy = Strategy.DIRECT_PATH_SHORT;
            reason = "rust_parses_path_short_and_java_writes_direct_response";
        } else if (directV5 && implicitRawMetadata) {
            strategy = Strategy.DIRECT_BUFFER_WITH_IMPLICIT_RAW_METADATA;
            reason = "direct_v5_without_raw_request_data_annotation_keeps_legacy_path_query_header_strings";
        } else if (directV5) {
            strategy = Strategy.DIRECT_BUFFER;
            reason = "java_receives_direct_body_buffer";
        } else if (directBodylessOutput) {
            strategy = Strategy.DIRECT_BODYLESS_OUTPUT;
            reason = "bodyless_route_writes_direct_response_without_request_metadata";
        } else if (directJsonResponse) {
            strategy = Strategy.DIRECT_JSON_RESPONSE;
            reason = "handler_returns_direct_json_response_writer_bypasses_dsl_json";
        } else if (route.asyncRoute) {
            strategy = Strategy.ASYNC_COMPLETION_STAGE;
            reason = "completion_stage_route";
        } else if (route.bodyless) {
            strategy = Strategy.EXACT_BODYLESS;
            reason = "bodyless_exact_method_handle";
        } else {
            strategy = Strategy.EXACT_ANNOTATED;
            reason = "exact_method_handle_with_cached_metadata";
        }

        boolean compiledInvoker = !legacyV4
                && !directV5
                && !directQueryInt
                && !directQueryLong
                && !directQueryBoolean
                && !directQueryDouble
                && !directQueryShort
                && !directPathInt
                && !directPathLong
                && !directPathBoolean
                && !directPathDouble
                && !directPathShort
                && !directBodylessOutput
                && nativeStaticResponseId <= 0
                && nativeStaticFileResponseId <= 0;

        return new RouteExecutionPlan(
                route.httpMethod,
                route.path,
                route.handlerId,
                bean.getClass().getName(),
                method.getName(),
                route.requestType,
                route.responseType,
                workload,
                workloadBudget,
                strategy,
                reason,
                route.bodyless,
                route.needsPathParams,
                route.needsQueryParams,
                route.needsHeaders,
                route.asyncRoute,
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
                directV5,
                directBodylessOutput,
                directJsonResponse,
                directPrimitiveOutput,
                nativeStaticResponseId,
                nativeStaticFileResponseId,
                route.admissionMaxConcurrent,
                route.admissionQueueTimeoutMs,
                route.jniAdmissionMaxPending,
                route.jniAdmissionQueueTimeoutMs,
                legacyV4,
                compiledInvoker,
                compiledInvoker && exactInvoker,
                benchmarkOnly
        );
    }

    public boolean optimized() {
        return strategy.optimized();
    }

    public boolean implicitRawMetadata() {
        return strategy == Strategy.DIRECT_BUFFER_WITH_IMPLICIT_RAW_METADATA;
    }

    public boolean productionRoute() {
        return !benchmarkOnly;
    }

    public boolean heavyJsonObjectGraph() {
        if (!"HEAVY_JSON".equals(workload)) {
            return false;
        }
        if (nativeStaticResponseId > 0
                || nativeStaticFileResponseId > 0
                || directJsonResponse
                || directBuffer
                || directPrimitiveOutput
                || directBodylessOutput
                || RawResponse.class.getName().equals(responseType)
                || FileResponse.class.getName().equals(responseType)) {
            return false;
        }
        return switch (strategy) {
            case NATIVE_STATIC_RESPONSE, NATIVE_STATIC_FILE, DIRECT_BUFFER,
                    DIRECT_BUFFER_WITH_IMPLICIT_RAW_METADATA, DIRECT_BODYLESS_OUTPUT,
                    DIRECT_JSON_RESPONSE -> false;
            default -> true;
        };
    }

    public String routeKey() {
        return httpMethod.toUpperCase(Locale.ROOT) + " " + path;
    }

    String toLogLine(long invocations) {
        return "[reactor-route-plan] " + routeKey()
                + " handler=" + handlerClass + "#" + methodName
                + " strategy=" + strategy
                + " workload=" + workload
                + (workloadBudget.isBlank() ? "" : " budget=" + workloadBudget)
                + " optimized=" + optimized()
                + " invocations=" + invocations
                + " needs={path=" + needsPathParams
                + ",query=" + needsQueryParams
                + ",headers=" + needsHeaders
                + ",bodyless=" + bodyless + "}"
                + " compiledInvoker=" + compiledInvoker
                + " exactInvoker=" + exactInvoker
                + " benchmarkOnly=" + benchmarkOnly
                + " heavyJsonObjectGraph=" + heavyJsonObjectGraph()
                + " directBodylessOutput=" + directBodylessOutput
                + " directPrimitiveOutput=" + directPrimitiveOutput
                + " directJsonResponse=" + directJsonResponse
                + " nativeStaticResponse=" + (nativeStaticResponseId > 0)
                + " nativeStaticFile=" + (nativeStaticFileResponseId > 0)
                + " admission={maxConcurrent=" + admissionMaxConcurrent
                + ",queueTimeoutMs=" + admissionQueueTimeoutMs + "}"
                + " jniAdmission={maxPending=" + jniAdmissionMaxPending
                + ",queueTimeoutMs=" + jniAdmissionQueueTimeoutMs + "}"
                + " reason=" + reason;
    }

    String toJson(long invocations) {
        return new StringBuilder(384)
                .append('{')
                .append("\"method\":").append(json(httpMethod)).append(',')
                .append("\"path\":").append(json(path)).append(',')
                .append("\"handler_id\":").append(handlerId).append(',')
                .append("\"handler\":").append(json(handlerClass + "#" + methodName)).append(',')
                .append("\"request_type\":").append(json(requestType)).append(',')
                .append("\"response_type\":").append(json(responseType)).append(',')
                .append("\"workload\":").append(json(workload)).append(',')
                .append("\"workload_budget\":").append(json(workloadBudget)).append(',')
                .append("\"strategy\":").append(json(strategy.name())).append(',')
                .append("\"optimized\":").append(optimized()).append(',')
                .append("\"benchmark_only\":").append(benchmarkOnly).append(',')
                .append("\"production_route\":").append(productionRoute()).append(',')
                .append("\"heavy_json_object_graph\":").append(heavyJsonObjectGraph()).append(',')
                .append("\"reason\":").append(json(reason)).append(',')
                .append("\"invocations\":").append(invocations).append(',')
                .append("\"bodyless\":").append(bodyless).append(',')
                .append("\"needs_path_params\":").append(needsPathParams).append(',')
                .append("\"needs_query_params\":").append(needsQueryParams).append(',')
                .append("\"needs_headers\":").append(needsHeaders).append(',')
                .append("\"async\":").append(asyncRoute).append(',')
                .append("\"direct_query_int\":").append(directQueryInt).append(',')
                .append("\"direct_query_long\":").append(directQueryLong).append(',')
                .append("\"direct_query_boolean\":").append(directQueryBoolean).append(',')
                .append("\"direct_query_double\":").append(directQueryDouble).append(',')
                .append("\"direct_query_short\":").append(directQueryShort).append(',')
                .append("\"direct_path_int\":").append(directPathInt).append(',')
                .append("\"direct_path_long\":").append(directPathLong).append(',')
                .append("\"direct_path_boolean\":").append(directPathBoolean).append(',')
                .append("\"direct_path_double\":").append(directPathDouble).append(',')
                .append("\"direct_path_short\":").append(directPathShort).append(',')
                .append("\"direct_buffer\":").append(directBuffer).append(',')
                .append("\"direct_bodyless_output\":").append(directBodylessOutput).append(',')
                .append("\"direct_primitive_output\":").append(directPrimitiveOutput).append(',')
                .append("\"direct_json_response\":").append(directJsonResponse).append(',')
                .append("\"native_static_response\":").append(nativeStaticResponseId > 0).append(',')
                .append("\"native_static_response_id\":").append(nativeStaticResponseId).append(',')
                .append("\"native_static_file\":").append(nativeStaticFileResponseId > 0).append(',')
                .append("\"native_static_file_response_id\":").append(nativeStaticFileResponseId).append(',')
                .append("\"route_admission_enabled\":").append(admissionMaxConcurrent > 0).append(',')
                .append("\"route_admission_max_concurrent\":").append(admissionMaxConcurrent).append(',')
                .append("\"route_admission_queue_timeout_ms\":").append(admissionQueueTimeoutMs).append(',')
                .append("\"jni_admission_enabled\":").append(jniAdmissionMaxPending > 0).append(',')
                .append("\"jni_admission_max_pending\":").append(jniAdmissionMaxPending).append(',')
                .append("\"jni_admission_queue_timeout_ms\":").append(jniAdmissionQueueTimeoutMs).append(',')
                .append("\"legacy_v4\":").append(legacyV4).append(',')
                .append("\"compiled_invoker\":").append(compiledInvoker).append(',')
                .append("\"exact_invoker\":").append(exactInvoker)
                .append('}')
                .toString();
    }

    private static boolean returnsDirectJsonResponse(Method method) {
        if (DirectJsonResponse.class.isAssignableFrom(method.getReturnType())
                || JsonProducerResponse.class.isAssignableFrom(method.getReturnType())
                || JsonBodyProducer.class.isAssignableFrom(method.getReturnType())) {
            return true;
        }
        Type genericReturnType = method.getGenericReturnType();
        if (genericReturnType instanceof ParameterizedType parameterizedType) {
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                if (argument == DirectJsonResponse.class
                        || argument == JsonProducerResponse.class
                        || argument == JsonBodyProducer.class) {
                    return true;
                }
                if (argument instanceof ParameterizedType nested
                        && (nested.getRawType() == DirectJsonResponse.class
                        || nested.getRawType() == JsonProducerResponse.class
                        || nested.getRawType() == JsonBodyProducer.class)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        escaped.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append("\\u");
                        String hex = Integer.toHexString(ch);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            escaped.append('0');
                        }
                        escaped.append(hex);
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        escaped.append('"');
        return escaped.toString();
    }
}

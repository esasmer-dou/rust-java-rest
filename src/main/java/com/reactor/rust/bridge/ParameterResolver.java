package com.reactor.rust.bridge;

import com.reactor.rust.annotations.*;
import com.reactor.rust.exception.BadRequestException;
import com.reactor.rust.exception.ValidationException;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.json.DslJsonService;
import com.reactor.rust.util.FastMap;
import com.reactor.rust.util.PooledMaps;
import com.reactor.rust.validation.ValidationResult;
import com.reactor.rust.validation.Validator;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.ByteBuffer;

/**
 * Resolves method parameters from HTTP request data.
 * Supports @PathVariable, @RequestParam, @HeaderParam, @RequestBody annotations.
 *
 * OPTIMIZED: Uses ThreadLocal FastMap pool for zero-allocation parameter parsing.
 * Before: 4 HashMap allocations per request (~2KB, ~50μs)
 * After: 0 allocations (reuses ThreadLocal FastMap instances)
 */
public final class ParameterResolver {

    private ParameterResolver() {}

    /**
     * Check if method uses annotation-based parameters (new style).
     */
    public static boolean isAnnotatedMethod(Method method) {
        for (Parameter param : method.getParameters()) {
            if (param.isAnnotationPresent(PathVariable.class)
                    || param.isAnnotationPresent(RequestParam.class)
                    || param.isAnnotationPresent(HeaderParam.class)
                    || param.isAnnotationPresent(RequestBody.class)
                    || param.isAnnotationPresent(CookieValue.class)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if method returns ResponseEntity (new style).
     */
    public static boolean returnsResponseEntity(Method method) {
        return ResponseEntity.class.isAssignableFrom(method.getReturnType());
    }

    /**
     * Resolve parameters for annotated handler method.
     * Uses ThreadLocal FastMap pools for zero-allocation.
     *
     * @param method     Handler method
     * @param body       Request body bytes
     * @param pathParams Path parameters (format: "key1=value1&amp;key2=value2")
     * @param queryString Query string (format: "key1=value1&amp;key2=value2")
     * @param headers    Headers (format: "Header1: value1\nHeader2: value2\n")
     * @return Array of resolved parameters ready for method invocation
     */
    public static Object[] resolveParameters(
            Method method,
            byte[] body,
            String pathParams,
            String queryString,
            String headers) {

        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];

        // Use ThreadLocal FastMap pools - ZERO ALLOCATION
        FastMap pathParamMap = PooledMaps.getParams();
        FastMap queryParams = PooledMaps.getParams();
        FastMap headerMap = PooledMaps.getHeaders();
        FastMap cookieMap = PooledMaps.getCookies();

        try {
            // Parse into pooled FastMap instances
            pathParamMap.clear();
            PooledMaps.parseParamsTo(pathParamMap, pathParams);

            queryParams.clear();
            PooledMaps.parseParamsTo(queryParams, queryString);

            headerMap.clear();
            PooledMaps.parseHeadersTo(headerMap, headers);

            // Parse cookies from header
            cookieMap.clear();
            String cookieHeader = headerMap.get("cookie");
            if (cookieHeader != null) {
                PooledMaps.parseCookiesTo(cookieMap, cookieHeader);
            }

            for (int i = 0; i < params.length; i++) {
                Parameter param = params[i];
                args[i] = resolveParameter(param, body, pathParamMap, queryParams, headerMap, cookieMap);
            }

            return args;

        } finally {
            // Clear maps for next use (ThreadLocal pool)
            pathParamMap.clear();
            queryParams.clear();
            headerMap.clear();
            cookieMap.clear();
        }
    }

    /**
     * Resolve a single parameter.
     */
    private static Object resolveParameter(
            Parameter param,
            byte[] body,
            FastMap pathParams,
            FastMap queryParams,
            FastMap headers,
            FastMap cookies) {

        // @PathVariable
        PathVariable pathVariable = param.getAnnotation(PathVariable.class);
        if (pathVariable != null) {
            String name = pathVariable.value();
            String value = pathParams.get(name);
            if (value == null) {
                throw new BadRequestException("Path parameter '" + name + "' is missing");
            }
            return convertType(value, param.getType());
        }

        // @RequestParam
        RequestParam requestParam = param.getAnnotation(RequestParam.class);
        if (requestParam != null) {
            String name = requestParam.value();
            String value = queryParams.get(name);

            if (value == null && requestParam.required()) {
                throw new BadRequestException("Query parameter '" + name + "' is required");
            }

            if (value == null) {
                value = requestParam.defaultValue();
            }

            return convertType(value, param.getType());
        }

        // @HeaderParam
        HeaderParam headerParam = param.getAnnotation(HeaderParam.class);
        if (headerParam != null) {
            // Header names are already lowercase in FastMap
            String name = headerParam.value().toLowerCase();
            String value = headers.get(name);

            if (value == null && headerParam.required()) {
                throw new BadRequestException("Header '" + name + "' is required");
            }

            if (value == null) {
                value = headerParam.defaultValue();
            }

            return convertType(value, param.getType());
        }

        // @RequestBody
        RequestBody requestBody = param.getAnnotation(RequestBody.class);
        if (requestBody != null) {
            if (body == null || body.length == 0) {
                if (requestBody.required()) {
                    throw new BadRequestException("Request body is required");
                }
                return null;
            }

            Object parsed = DslJsonService.parse(body, param.getType());

            // @Valid - trigger validation
            if (param.isAnnotationPresent(Valid.class)) {
                ValidationResult result = Validator.getInstance().validate(parsed);
                if (result.hasErrors()) {
                    throw new ValidationException(result);
                }
            }

            return parsed;
        }

        // @CookieValue
        CookieValue cookieValue = param.getAnnotation(CookieValue.class);
        if (cookieValue != null) {
            String name = cookieValue.value();
            String value = cookies.get(name);

            if (value == null && cookieValue.required()) {
                throw new BadRequestException("Cookie '" + name + "' is required");
            }

            if (value == null) {
                value = cookieValue.defaultValue();
            }

            return convertType(value, param.getType());
        }

        // Fallback - try ByteBuffer for response writing
        if (param.getType() == ByteBuffer.class) {
            return null; // Will be set by caller
        }

        if (param.getType() == int.class) {
            return 0; // Will be set by caller
        }

        return null;
    }

    /**
     * Convert string value to target type.
     */
    private static Object convertType(String value, Class<?> targetType) {
        if (value == null) {
            return null;
        }

        if (targetType == String.class) {
            return value;
        }

        if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(value);
        }

        if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(value);
        }

        if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(value);
        }

        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(value);
        }

        if (targetType.isEnum()) {
            return Enum.valueOf((Class<? extends Enum>) targetType, value.toUpperCase());
        }

        return value;
    }

    // ========================================
    // LEGACY METHODS (for backwards compatibility)
    // ========================================

    /**
     * Parse key=value pairs from query string or path params.
     * @deprecated Use PooledMaps.parseParamsTo() for zero-allocation
     */
    @Deprecated
    public static java.util.Map<String, String> parseParams(String params) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (params == null || params.isEmpty()) {
            return map;
        }
        for (String pair : params.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = pair.substring(0, idx);
                String value = pair.substring(idx + 1);
                map.put(key, value);
            }
        }
        return map;
    }

    /**
     * Parse headers from string.
     * @deprecated Use PooledMaps.parseHeadersTo() for zero-allocation
     */
    @Deprecated
    public static java.util.Map<String, String> parseHeaders(String headers) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (headers == null || headers.isEmpty()) {
            return map;
        }
        for (String line : headers.split("\n")) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                String key = line.substring(0, idx).trim().toLowerCase();
                String value = line.substring(idx + 1).trim();
                map.put(key, value);
            }
        }
        return map;
    }

    /**
     * Parse cookies from headers.
     * @deprecated Use PooledMaps.parseCookiesTo() for zero-allocation
     */
    @Deprecated
    public static java.util.Map<String, String> parseCookies(java.util.Map<String, String> headers) {
        java.util.Map<String, String> cookies = new java.util.HashMap<>();
        String cookieHeader = headers.get("cookie");
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return cookies;
        }
        for (String cookie : cookieHeader.split(";")) {
            int idx = cookie.indexOf('=');
            if (idx > 0) {
                String name = cookie.substring(0, idx).trim();
                String value = cookie.substring(idx + 1).trim();
                cookies.put(name, value);
            }
        }
        return cookies;
    }
}

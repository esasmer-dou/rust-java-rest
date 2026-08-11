package com.reactor.rust.bridge;

import com.reactor.rust.json.DslJsonService;
import com.reactor.rust.exception.BadRequestException;
import com.reactor.rust.exception.ValidationException;
import com.reactor.rust.util.RequestValueMap;
import com.reactor.rust.util.UrlCodec;
import com.reactor.rust.validation.DTOValidator;
import com.reactor.rust.validation.ValidationResult;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Startup-compiled invocation plan for annotated handlers.
 *
 * <p>This is not bytecode generation. It removes per-request parameter-type
 * branching by compiling each annotated parameter into a resolver once at startup.
 * Real annotation-processor generated invokers can later replace this SPI without
 * changing handler APIs.</p>
 */
final class CompiledRouteInvoker {

    private static final byte[] EMPTY_BYTES = new byte[0];

    private final MethodHandle handle;
    private final MethodHandle exactHandle;
    private final Object bean;
    private final GeneratedRouteInvoker generatedInvoker;
    private final ArgumentResolver[] resolvers;

    private CompiledRouteInvoker(
            MethodHandle handle,
            Object bean,
            GeneratedRouteInvoker generatedInvoker,
            ArgumentResolver[] resolvers) {
        this.handle = handle;
        this.exactHandle = generatedInvoker == null ? adaptExactHandle(handle, resolvers.length) : null;
        this.bean = bean;
        this.generatedInvoker = generatedInvoker;
        this.resolvers = resolvers;
    }

    static CompiledRouteInvoker compile(
            MethodHandle handle,
            MethodMetadata metadata,
            Object bean,
            GeneratedRouteInvoker generatedInvoker) {
        MethodMetadata.ParamInfo[] infos = metadata.paramInfos;
        ArgumentResolver[] resolvers = new ArgumentResolver[infos.length];
        for (int i = 0; i < infos.length; i++) {
            resolvers[i] = resolverFor(infos[i]);
        }
        if (generatedInvoker != null && generatedInvoker.arity() != resolvers.length) {
            throw new IllegalStateException("Generated route invoker arity mismatch");
        }
        return new CompiledRouteInvoker(handle, bean, generatedInvoker, resolvers);
    }

    int arity() {
        return resolvers.length;
    }

    boolean usesExactAdapter() {
        return generatedInvoker != null || exactHandle != null;
    }

    boolean usesGeneratedInvoker() {
        return generatedInvoker != null;
    }

    boolean acceptsSingleRawValue() {
        return resolvers.length == 1 && resolvers[0] instanceof SingleValueResolver;
    }

    Object invokeSingleRawValue(String value) throws Throwable {
        if (!acceptsSingleRawValue()) {
            throw new IllegalStateException("Single raw value fast path is not supported for this route");
        }
        SingleValueResolver resolver = (SingleValueResolver) resolvers[0];
        return invokeResolved(resolver.value(value));
    }

    Object invokeInt(int value) throws Throwable {
        return generatedInvoker != null ? generatedInvoker.invokeInt(bean, value) : invokeResolved(value);
    }

    Object invokeLong(long value) throws Throwable {
        return generatedInvoker != null ? generatedInvoker.invokeLong(bean, value) : invokeResolved(value);
    }

    Object invokeBoolean(boolean value) throws Throwable {
        return generatedInvoker != null ? generatedInvoker.invokeBoolean(bean, value) : invokeResolved(value);
    }

    Object invokeDouble(double value) throws Throwable {
        return generatedInvoker != null ? generatedInvoker.invokeDouble(bean, value) : invokeResolved(value);
    }

    Object invokeShort(short value) throws Throwable {
        return generatedInvoker != null ? generatedInvoker.invokeShort(bean, value) : invokeResolved(value);
    }

    Object invoke(byte[] body, RequestValueMap params, RequestValueMap headers) throws Throwable {
        return switch (resolvers.length) {
            case 0 -> invokeResolved();
            case 1 -> invokeResolved(resolvers[0].resolve(body, params, headers));
            case 2 -> invokeResolved(
                    resolvers[0].resolve(body, params, headers),
                    resolvers[1].resolve(body, params, headers)
            );
            case 3 -> invokeResolved(
                    resolvers[0].resolve(body, params, headers),
                    resolvers[1].resolve(body, params, headers),
                    resolvers[2].resolve(body, params, headers)
            );
            case 4 -> invokeResolved(
                    resolvers[0].resolve(body, params, headers),
                    resolvers[1].resolve(body, params, headers),
                    resolvers[2].resolve(body, params, headers),
                    resolvers[3].resolve(body, params, headers)
            );
            case 5 -> invokeResolved(
                    resolvers[0].resolve(body, params, headers),
                    resolvers[1].resolve(body, params, headers),
                    resolvers[2].resolve(body, params, headers),
                    resolvers[3].resolve(body, params, headers),
                    resolvers[4].resolve(body, params, headers)
            );
            case 6 -> invokeResolved(
                    resolvers[0].resolve(body, params, headers),
                    resolvers[1].resolve(body, params, headers),
                    resolvers[2].resolve(body, params, headers),
                    resolvers[3].resolve(body, params, headers),
                    resolvers[4].resolve(body, params, headers),
                    resolvers[5].resolve(body, params, headers)
            );
            case 7 -> invokeResolved(
                    resolvers[0].resolve(body, params, headers),
                    resolvers[1].resolve(body, params, headers),
                    resolvers[2].resolve(body, params, headers),
                    resolvers[3].resolve(body, params, headers),
                    resolvers[4].resolve(body, params, headers),
                    resolvers[5].resolve(body, params, headers),
                    resolvers[6].resolve(body, params, headers)
            );
            case 8 -> invokeResolved(
                    resolvers[0].resolve(body, params, headers),
                    resolvers[1].resolve(body, params, headers),
                    resolvers[2].resolve(body, params, headers),
                    resolvers[3].resolve(body, params, headers),
                    resolvers[4].resolve(body, params, headers),
                    resolvers[5].resolve(body, params, headers),
                    resolvers[6].resolve(body, params, headers),
                    resolvers[7].resolve(body, params, headers)
            );
            default -> throw new IllegalStateException("Unsupported annotated parameter count: " + resolvers.length);
        };
    }

    Object invokeDirect(ByteBuffer body, int bodyLen, RequestValueMap params, RequestValueMap headers) throws Throwable {
        return switch (resolvers.length) {
            case 0 -> invokeResolved();
            case 1 -> invokeResolved(resolvers[0].resolveDirect(body, bodyLen, params, headers));
            case 2 -> invokeResolved(
                    resolvers[0].resolveDirect(body, bodyLen, params, headers),
                    resolvers[1].resolveDirect(body, bodyLen, params, headers)
            );
            case 3 -> invokeResolved(
                    resolvers[0].resolveDirect(body, bodyLen, params, headers),
                    resolvers[1].resolveDirect(body, bodyLen, params, headers),
                    resolvers[2].resolveDirect(body, bodyLen, params, headers)
            );
            case 4 -> invokeResolved(
                    resolvers[0].resolveDirect(body, bodyLen, params, headers),
                    resolvers[1].resolveDirect(body, bodyLen, params, headers),
                    resolvers[2].resolveDirect(body, bodyLen, params, headers),
                    resolvers[3].resolveDirect(body, bodyLen, params, headers)
            );
            case 5 -> invokeResolved(
                    resolvers[0].resolveDirect(body, bodyLen, params, headers),
                    resolvers[1].resolveDirect(body, bodyLen, params, headers),
                    resolvers[2].resolveDirect(body, bodyLen, params, headers),
                    resolvers[3].resolveDirect(body, bodyLen, params, headers),
                    resolvers[4].resolveDirect(body, bodyLen, params, headers)
            );
            case 6 -> invokeResolved(
                    resolvers[0].resolveDirect(body, bodyLen, params, headers),
                    resolvers[1].resolveDirect(body, bodyLen, params, headers),
                    resolvers[2].resolveDirect(body, bodyLen, params, headers),
                    resolvers[3].resolveDirect(body, bodyLen, params, headers),
                    resolvers[4].resolveDirect(body, bodyLen, params, headers),
                    resolvers[5].resolveDirect(body, bodyLen, params, headers)
            );
            case 7 -> invokeResolved(
                    resolvers[0].resolveDirect(body, bodyLen, params, headers),
                    resolvers[1].resolveDirect(body, bodyLen, params, headers),
                    resolvers[2].resolveDirect(body, bodyLen, params, headers),
                    resolvers[3].resolveDirect(body, bodyLen, params, headers),
                    resolvers[4].resolveDirect(body, bodyLen, params, headers),
                    resolvers[5].resolveDirect(body, bodyLen, params, headers),
                    resolvers[6].resolveDirect(body, bodyLen, params, headers)
            );
            case 8 -> invokeResolved(
                    resolvers[0].resolveDirect(body, bodyLen, params, headers),
                    resolvers[1].resolveDirect(body, bodyLen, params, headers),
                    resolvers[2].resolveDirect(body, bodyLen, params, headers),
                    resolvers[3].resolveDirect(body, bodyLen, params, headers),
                    resolvers[4].resolveDirect(body, bodyLen, params, headers),
                    resolvers[5].resolveDirect(body, bodyLen, params, headers),
                    resolvers[6].resolveDirect(body, bodyLen, params, headers),
                    resolvers[7].resolveDirect(body, bodyLen, params, headers)
            );
            default -> throw new IllegalStateException("Unsupported annotated parameter count: " + resolvers.length);
        };
    }

    private Object invokeResolved() throws Throwable {
        if (generatedInvoker != null) {
            return generatedInvoker.invoke0(bean);
        }
        if (exactHandle != null) {
            return (Object) exactHandle.invokeExact();
        }
        return handle.invoke();
    }

    private Object invokeResolved(Object arg0) throws Throwable {
        if (generatedInvoker != null) {
            return generatedInvoker.invoke1(bean, arg0);
        }
        if (exactHandle != null) {
            return (Object) exactHandle.invokeExact(arg0);
        }
        return handle.invoke(arg0);
    }

    private Object invokeResolved(Object arg0, Object arg1) throws Throwable {
        if (generatedInvoker != null) {
            return generatedInvoker.invoke2(bean, arg0, arg1);
        }
        if (exactHandle != null) {
            return (Object) exactHandle.invokeExact(arg0, arg1);
        }
        return handle.invoke(arg0, arg1);
    }

    private Object invokeResolved(Object arg0, Object arg1, Object arg2) throws Throwable {
        if (generatedInvoker != null) {
            return generatedInvoker.invoke3(bean, arg0, arg1, arg2);
        }
        if (exactHandle != null) {
            return (Object) exactHandle.invokeExact(arg0, arg1, arg2);
        }
        return handle.invoke(arg0, arg1, arg2);
    }

    private Object invokeResolved(Object arg0, Object arg1, Object arg2, Object arg3) throws Throwable {
        if (generatedInvoker != null) {
            return generatedInvoker.invoke4(bean, arg0, arg1, arg2, arg3);
        }
        if (exactHandle != null) {
            return (Object) exactHandle.invokeExact(arg0, arg1, arg2, arg3);
        }
        return handle.invoke(arg0, arg1, arg2, arg3);
    }

    private Object invokeResolved(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4) throws Throwable {
        if (generatedInvoker != null) {
            return generatedInvoker.invoke5(bean, arg0, arg1, arg2, arg3, arg4);
        }
        if (exactHandle != null) {
            return (Object) exactHandle.invokeExact(arg0, arg1, arg2, arg3, arg4);
        }
        return handle.invoke(arg0, arg1, arg2, arg3, arg4);
    }

    private Object invokeResolved(
            Object arg0,
            Object arg1,
            Object arg2,
            Object arg3,
            Object arg4,
            Object arg5
    ) throws Throwable {
        if (generatedInvoker != null) {
            return generatedInvoker.invoke6(bean, arg0, arg1, arg2, arg3, arg4, arg5);
        }
        if (exactHandle != null) {
            return (Object) exactHandle.invokeExact(arg0, arg1, arg2, arg3, arg4, arg5);
        }
        return handle.invoke(arg0, arg1, arg2, arg3, arg4, arg5);
    }

    private Object invokeResolved(
            Object arg0,
            Object arg1,
            Object arg2,
            Object arg3,
            Object arg4,
            Object arg5,
            Object arg6
    ) throws Throwable {
        if (generatedInvoker != null) {
            return generatedInvoker.invoke7(bean, arg0, arg1, arg2, arg3, arg4, arg5, arg6);
        }
        if (exactHandle != null) {
            return (Object) exactHandle.invokeExact(arg0, arg1, arg2, arg3, arg4, arg5, arg6);
        }
        return handle.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6);
    }

    private Object invokeResolved(
            Object arg0,
            Object arg1,
            Object arg2,
            Object arg3,
            Object arg4,
            Object arg5,
            Object arg6,
            Object arg7
    ) throws Throwable {
        if (generatedInvoker != null) {
            return generatedInvoker.invoke8(bean, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
        }
        if (exactHandle != null) {
            return (Object) exactHandle.invokeExact(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
        }
        return handle.invoke(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
    }

    private static MethodHandle adaptExactHandle(MethodHandle handle, int arity) {
        try {
            Class<?>[] params = new Class<?>[arity];
            for (int i = 0; i < arity; i++) {
                params[i] = Object.class;
            }
            return handle.asType(MethodType.methodType(Object.class, params));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static ArgumentResolver resolverFor(MethodMetadata.ParamInfo info) {
        return switch (info.paramType) {
            case PATH_VARIABLE, REQUEST_PARAM ->
                    new ParamResolver(info.name, converterFor(info.type), info.defaultValue, info.required);
            case HEADER_PARAM ->
                    new HeaderResolver(
                            info.name.toLowerCase(Locale.ROOT),
                            converterFor(info.type),
                            info.defaultValue,
                            info.required
                    );
            case COOKIE_VALUE ->
                    new CookieResolver(info.name, converterFor(info.type), info.defaultValue, info.required);
            case REQUEST_BODY -> info.validate
                    ? new ValidatingBodyResolver(new BodyResolver(info.type, info.required))
                    : new BodyResolver(info.type, info.required);
            case LEGACY_BUFFER -> NullResolver.INSTANCE;
            case LEGACY_INT -> ZeroIntResolver.INSTANCE;
            default -> NullResolver.INSTANCE;
        };
    }

    private interface ArgumentResolver {
        Object resolve(byte[] body, RequestValueMap params, RequestValueMap headers);

        Object resolveDirect(ByteBuffer body, int bodyLen, RequestValueMap params, RequestValueMap headers);
    }

    private interface SingleValueResolver {
        Object value(String rawValue);
    }

    private interface ValueConverter {
        Object convert(String value);
    }

    private record ParamResolver(String name, ValueConverter converter, String defaultValue, boolean required)
            implements ArgumentResolver, SingleValueResolver {
        @Override
        public Object resolve(byte[] body, RequestValueMap params, RequestValueMap headers) {
            return value(params);
        }

        @Override
        public Object resolveDirect(ByteBuffer body, int bodyLen, RequestValueMap params, RequestValueMap headers) {
            return value(params);
        }

        private Object value(RequestValueMap params) {
            String value = params.get(name);
            if (value == null && defaultValue != null) {
                value = defaultValue;
            }
            if (value == null && required) {
                throw new BadRequestException("Parameter '" + name + "' is required");
            }
            return converter.convert(value);
        }

        @Override
        public Object value(String rawValue) {
            String value = rawValue;
            if (value == null && defaultValue != null) {
                value = defaultValue;
            }
            if (value == null && required) {
                throw new BadRequestException("Parameter '" + name + "' is required");
            }
            return converter.convert(value);
        }
    }

    private record HeaderResolver(String name, ValueConverter converter, String defaultValue, boolean required)
            implements ArgumentResolver, SingleValueResolver {
        @Override
        public Object resolve(byte[] body, RequestValueMap params, RequestValueMap headers) {
            return value(headers);
        }

        @Override
        public Object resolveDirect(ByteBuffer body, int bodyLen, RequestValueMap params, RequestValueMap headers) {
            return value(headers);
        }

        private Object value(RequestValueMap headers) {
            String value = headers.get(name);
            if (value == null && defaultValue != null) {
                value = defaultValue;
            }
            if (value == null && required) {
                throw new BadRequestException("Header '" + name + "' is required");
            }
            return converter.convert(value);
        }

        @Override
        public Object value(String rawValue) {
            String value = rawValue;
            if (value == null && defaultValue != null) {
                value = defaultValue;
            }
            if (value == null && required) {
                throw new BadRequestException("Header '" + name + "' is required");
            }
            return converter.convert(value);
        }
    }

    private record CookieResolver(String name, ValueConverter converter, String defaultValue, boolean required)
            implements ArgumentResolver, SingleValueResolver {
        @Override
        public Object resolve(byte[] body, RequestValueMap params, RequestValueMap headers) {
            return value(headers);
        }

        @Override
        public Object resolveDirect(ByteBuffer body, int bodyLen, RequestValueMap params, RequestValueMap headers) {
            return value(headers);
        }

        private Object value(RequestValueMap headers) {
            String value = findCookieValue(headers.get("cookie"), name);
            if (value == null && defaultValue != null) {
                value = defaultValue;
            }
            if (value == null && required) {
                throw new BadRequestException("Cookie '" + name + "' is required");
            }
            return converter.convert(value);
        }

        @Override
        public Object value(String rawValue) {
            String value = findCookieValue(rawValue, name);
            if (value == null && defaultValue != null) {
                value = defaultValue;
            }
            if (value == null && required) {
                throw new BadRequestException("Cookie '" + name + "' is required");
            }
            return converter.convert(value);
        }
    }

    private record BodyResolver(Class<?> targetType, boolean required) implements ArgumentResolver {
        @Override
        public Object resolve(byte[] body, RequestValueMap params, RequestValueMap headers) {
            if (body == null || body.length == 0) {
                requireBody();
                return null;
            }
            if (targetType == byte[].class) {
                return body;
            }
            if (targetType == ByteBuffer.class) {
                return ByteBuffer.wrap(body);
            }
            return DslJsonService.parse(body, targetType);
        }

        @Override
        public Object resolveDirect(ByteBuffer body, int bodyLen, RequestValueMap params, RequestValueMap headers) {
            if (body == null || bodyLen <= 0) {
                requireBody();
                return null;
            }
            if (targetType == ByteBuffer.class) {
                return duplicateBody(body, bodyLen);
            }
            if (targetType == byte[].class) {
                return toByteArray(body, bodyLen);
            }
            return DslJsonService.parse(body, bodyLen, targetType);
        }

        private void requireBody() {
            if (required) {
                throw new BadRequestException("Request body is required");
            }
        }
    }

    private record ValidatingBodyResolver(BodyResolver delegate) implements ArgumentResolver {
        @Override
        public Object resolve(byte[] body, RequestValueMap params, RequestValueMap headers) {
            return validate(delegate.resolve(body, params, headers));
        }

        @Override
        public Object resolveDirect(
                ByteBuffer body,
                int bodyLen,
                RequestValueMap params,
                RequestValueMap headers) {
            return validate(delegate.resolveDirect(body, bodyLen, params, headers));
        }

        private static Object validate(Object value) {
            if (value == null) {
                return null;
            }
            ValidationResult result = DTOValidator.getInstance().validate(value);
            if (result.hasErrors()) {
                throw new ValidationException(result);
            }
            return value;
        }
    }

    private enum NullResolver implements ArgumentResolver {
        INSTANCE;

        @Override
        public Object resolve(byte[] body, RequestValueMap params, RequestValueMap headers) {
            return null;
        }

        @Override
        public Object resolveDirect(ByteBuffer body, int bodyLen, RequestValueMap params, RequestValueMap headers) {
            return null;
        }
    }

    private enum ZeroIntResolver implements ArgumentResolver {
        INSTANCE;

        @Override
        public Object resolve(byte[] body, RequestValueMap params, RequestValueMap headers) {
            return 0;
        }

        @Override
        public Object resolveDirect(ByteBuffer body, int bodyLen, RequestValueMap params, RequestValueMap headers) {
            return 0;
        }
    }

    private static int safeBodyLength(ByteBuffer body, int length) {
        if (body == null || length <= 0) {
            return 0;
        }
        return Math.min(length, body.capacity());
    }

    private static ByteBuffer duplicateBody(ByteBuffer body, int length) {
        if (body == null || length <= 0) {
            return null;
        }

        ByteBuffer duplicate = body.duplicate();
        duplicate.position(0);
        duplicate.limit(safeBodyLength(body, length));
        return duplicate;
    }

    private static byte[] toByteArray(ByteBuffer body, int length) {
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ValueConverter converterFor(Class<?> targetType) {
        if (targetType == String.class) {
            return StringConverter.INSTANCE;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return IntConverter.INSTANCE;
        }
        if (targetType == long.class || targetType == Long.class) {
            return LongConverter.INSTANCE;
        }
        if (targetType == short.class || targetType == Short.class) {
            return ShortConverter.INSTANCE;
        }
        if (targetType == double.class || targetType == Double.class) {
            return DoubleConverter.INSTANCE;
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return BooleanConverter.INSTANCE;
        }
        if (targetType.isEnum()) {
            return new EnumConverter((Class<? extends Enum>) targetType);
        }

        return StringConverter.INSTANCE;
    }

    private enum StringConverter implements ValueConverter {
        INSTANCE;

        @Override
        public Object convert(String value) {
            return value;
        }
    }

    private enum IntConverter implements ValueConverter {
        INSTANCE;

        @Override
        public Object convert(String value) {
            return value == null ? null : parseNumber("integer", value, Integer::parseInt);
        }
    }

    private enum LongConverter implements ValueConverter {
        INSTANCE;

        @Override
        public Object convert(String value) {
            return value == null ? null : parseNumber("long", value, Long::parseLong);
        }
    }

    private enum ShortConverter implements ValueConverter {
        INSTANCE;

        @Override
        public Object convert(String value) {
            return value == null ? null : parseNumber("short", value, Short::parseShort);
        }
    }

    private enum DoubleConverter implements ValueConverter {
        INSTANCE;

        @Override
        public Object convert(String value) {
            return value == null ? null : parseNumber("double", value, Double::parseDouble);
        }
    }

    private enum BooleanConverter implements ValueConverter {
        INSTANCE;

        @Override
        public Object convert(String value) {
            if (value == null) {
                return null;
            }
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            throw new BadRequestException("Invalid boolean parameter");
        }
    }

    private static final class EnumConverter implements ValueConverter {
        private final Class<? extends Enum> enumType;
        private final Enum<?>[] constants;
        private final String[] names;

        private EnumConverter(Class<? extends Enum> enumType) {
            this.enumType = enumType;
            this.constants = enumType.getEnumConstants();
            this.names = new String[constants.length];
            for (int i = 0; i < constants.length; i++) {
                names[i] = constants[i].name();
            }
        }

        @Override
        public Object convert(String value) {
            if (value == null) {
                return null;
            }
            for (int i = 0; i < names.length; i++) {
                if (names[i].equalsIgnoreCase(value)) {
                    return constants[i];
                }
            }
            throw new BadRequestException("Invalid " + enumType.getSimpleName() + " parameter");
        }
    }

    private static <T> T parseNumber(String type, String value, NumberParser<T> parser) {
        try {
            return parser.parse(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid " + type + " parameter", e);
        }
    }

    @FunctionalInterface
    private interface NumberParser<T> {
        T parse(String value);
    }

    private static String findCookieValue(String cookieHeader, String name) {
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
}

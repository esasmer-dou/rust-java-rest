package com.reactor.rust.exception;

import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.json.DslJsonService;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for global exception handlers.
 * Scans beans for @ExceptionHandler methods and dispatches exceptions.
 */
public final class ExceptionHandlerRegistry {

    private static final ExceptionHandlerRegistry INSTANCE = new ExceptionHandlerRegistry();

    // Exception class -> Handler method info
    private final Map<Class<? extends Throwable>, HandlerMethod> handlers = new ConcurrentHashMap<>();

    // Fallback handlers for exception hierarchies
    private final List<HandlerMethod> globalHandlers = new ArrayList<>();

    private ExceptionHandlerRegistry() {}

    public static ExceptionHandlerRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Handler method info.
     */
    private static class HandlerMethod {
        final Object bean;
        final Method method;
        final Class<? extends Throwable> exceptionType;

        HandlerMethod(Object bean, Method method, Class<? extends Throwable> exceptionType) {
            this.bean = bean;
            this.method = method;
            this.exceptionType = exceptionType;
        }
    }

    /**
     * Scan and register all @ExceptionHandler methods from beans.
     */
    public void scanAndRegister() {
        for (Object bean : BeanContainer.getInstance().getBeansOfType(Object.class)) {
            registerExceptionHandlers(bean);
        }
        System.out.println("[ExceptionHandlerRegistry] Registered " + handlers.size() + " exception handlers");
    }

    /**
     * Register exception handlers from a bean.
     */
    private void registerExceptionHandlers(Object bean) {
        Class<?> clazz = bean.getClass();

        for (Method method : clazz.getDeclaredMethods()) {
            ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
            if (annotation == null) {
                continue;
            }

            method.setAccessible(true);

            // Get exception types from annotation or method parameter
            Class<? extends Throwable>[] exceptionTypes = annotation.value();

            if (exceptionTypes.length == 0) {
                // Infer from method parameter
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length > 0 && Throwable.class.isAssignableFrom(paramTypes[0])) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Throwable> exType = (Class<? extends Throwable>) paramTypes[0];
                    exceptionTypes = new Class[]{exType};
                } else {
                    // No specific exception - handle all
                    globalHandlers.add(new HandlerMethod(bean, method, Throwable.class));
                    continue;
                }
            }

            // Register each exception type
            for (Class<? extends Throwable> exType : exceptionTypes) {
                HandlerMethod handler = new HandlerMethod(bean, method, exType);
                handlers.put(exType, handler);
                System.out.println("[ExceptionHandlerRegistry] Registered handler for: " + exType.getName());
            }
        }
    }

    /**
     * Handle an exception by finding and invoking the appropriate handler.
     *
     * @param e The exception to handle
     * @return Response object or null if no handler found
     */
    public Object handleException(Throwable e) {
        // Try exact match first
        HandlerMethod handler = handlers.get(e.getClass());

        // Try parent classes
        if (handler == null) {
            Class<?> exClass = e.getClass();
            while (exClass != null && exClass != Throwable.class) {
                handler = handlers.get(exClass);
                if (handler != null) {
                    break;
                }
                exClass = exClass.getSuperclass();
            }
        }

        // Try global handlers
        if (handler == null && !globalHandlers.isEmpty()) {
            handler = globalHandlers.get(0);
        }

        if (handler == null) {
            return null; // No handler found
        }

        try {
            // Invoke handler
            Object result;
            if (handler.method.getParameterCount() > 0) {
                result = handler.method.invoke(handler.bean, e);
            } else {
                result = handler.method.invoke(handler.bean);
            }

            return result;

        } catch (Exception invokeError) {
            System.err.println("[ExceptionHandlerRegistry] Error invoking handler: " + invokeError.getMessage());
            return new ErrorResponse(500, "Internal server error");
        }
    }

    /**
     * Handle exception and return as error response JSON.
     */
    public String handleExceptionAsJson(Throwable e) {
        Object result = handleException(e);

        if (result == null) {
            // Default error response
            ErrorResponse defaultError = new ErrorResponse(500, e.getMessage() != null ? e.getMessage() : "Internal Server Error");
            return toJson(defaultError);
        }

        if (result instanceof ErrorResponse er) {
            return toJson(er);
        }

        if (result instanceof ResponseEntity<?> re) {
            Object body = re.getBody();
            if (body instanceof ErrorResponse er) {
                return toJson(er);
            }
            return toJson(body);
        }

        // Try to serialize as JSON
        if (result instanceof String s) {
            return s;
        }

        return toJson(result);
    }

    private String toJson(Object obj) {
        try {
            byte[] bytes = DslJsonService.serialize(obj);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "{\"code\":500,\"message\":\"Serialization error\"}";
        }
    }

    /**
     * Check if an exception has a registered handler.
     */
    public boolean hasHandler(Class<? extends Throwable> exceptionType) {
        return handlers.containsKey(exceptionType) || !globalHandlers.isEmpty();
    }
}

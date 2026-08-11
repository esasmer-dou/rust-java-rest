package com.reactor.rust.websocket;

import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.util.UrlCodec;
import com.reactor.rust.websocket.annotation.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for WebSocket handlers.
 * Scans for @WebSocket annotated classes and manages their lifecycle.
 */
public final class WebSocketRegistry {

    private static volatile WebSocketRegistry active;

    private static final class CompatibilityHolder {
        private static final WebSocketRegistry INSTANCE = new WebSocketRegistry();
    }

    public static WebSocketRegistry getInstance() {
        WebSocketRegistry current = active;
        return current != null ? current : CompatibilityHolder.INSTANCE;
    }

    public static WebSocketRegistry create() {
        return new WebSocketRegistry();
    }

    public static void activate(WebSocketRegistry registry) {
        active = Objects.requireNonNull(registry, "registry");
    }

    public static void deactivate(WebSocketRegistry registry) {
        if (active == registry) {
            active = null;
        }
    }

    // path -> handler info
    private final Map<String, WebSocketHandlerInfo> handlers = new ConcurrentHashMap<>();

    // session id -> session
    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private WebSocketRegistry() {}

    /**
     * Register a WebSocket handler.
     */
    public void register(Object handler) {
        Class<?> clazz = handler.getClass();
        WebSocket wsAnnotation = clazz.getAnnotation(WebSocket.class);

        if (wsAnnotation == null) {
            throw new IllegalArgumentException("Handler must be annotated with @WebSocket");
        }

        String path = wsAnnotation.value();
        WebSocketHandlerInfo info = new WebSocketHandlerInfo(handler, clazz);

        WebSocketHandlerInfo previous = handlers.putIfAbsent(path, info);
        if (previous != null && previous.bean != handler) {
            throw new IllegalStateException("Duplicate WebSocket route: " + path
                    + " handlers=" + previous.clazz.getName() + "," + clazz.getName());
        }
        debugLog("[WebSocketRegistry] Registered handler: " + path + " -> " + clazz.getName());
    }

    /**
     * Scan and register all @WebSocket beans from container.
     */
    public void scanAndRegister() {
        scanAndRegister(BeanContainer.getInstance());
    }

    public void scanAndRegister(BeanContainer container) {
        for (Object bean : container.getBeansOfType(Object.class)) {
            if (bean.getClass().isAnnotationPresent(WebSocket.class)) {
                register(bean);
            }
        }
    }

    /**
     * Get handler for path.
     */
    public WebSocketHandlerInfo getHandler(String path) {
        return handlers.get(path);
    }

    /**
     * Get all registered handler paths.
     */
    public java.util.Set<String> getHandlerPaths() {
        return handlers.keySet();
    }

    /**
     * Called when a new WebSocket connection is opened (from Rust).
     */
    public void onOpen(long sessionId, String path, String pathParams, String queryParams) {
        WebSocketHandlerInfo handler = handlers.get(path);
        if (handler == null) {
            debugError("[WebSocketRegistry] No handler for path: " + path);
            return;
        }

        // Use the sessionId from Rust, not generate a new one
        WebSocketSession session = new WebSocketSession(
                sessionId,
                path,
                parseParams(pathParams, false),
                parseParams(queryParams, true)
        );
        sessions.put(sessionId, session);

        if (handler.onOpen != null) {
            try {
                handler.onOpen.invoke(session);
            } catch (Throwable e) {
                handleCallbackFailure("onOpen", e);
            }
        }
    }

    /**
     * Called when a WebSocket message is received (from Rust).
     */
    public void onMessage(long sessionId, String message) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) return;

        WebSocketHandlerInfo handler = handlers.get(session.getPath());
        if (handler == null) return;

        if (handler.onMessage != null) {
            try {
                handler.onMessage.invoke(session, message);
            } catch (Throwable e) {
                handleCallbackFailure("onMessage", e);
            }
        }
    }

    /**
     * Called when a binary WebSocket message is received (from Rust).
     */
    public void onBinary(long sessionId, byte[] data) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) return;

        WebSocketHandlerInfo handler = handlers.get(session.getPath());
        if (handler == null) return;

        if (handler.onBinary != null) {
            try {
                handler.onBinary.invoke(session, data);
            } catch (Throwable e) {
                handleCallbackFailure("onBinary", e);
            }
        }
    }

    /**
     * Called when a WebSocket connection is closed (from Rust).
     */
    public void onClose(long sessionId) {
        WebSocketSession session = sessions.remove(sessionId);
        if (session == null) return;
        session.markClosed();

        WebSocketHandlerInfo handler = handlers.get(session.getPath());
        if (handler == null) return;

        if (handler.onClose != null) {
            try {
                handler.onClose.invoke(session);
            } catch (Throwable e) {
                handleCallbackFailure("onClose", e);
            }
        }
    }

    /**
     * Called when a WebSocket error occurs (from Rust).
     */
    public void onError(long sessionId, String errorMessage) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) return;

        WebSocketHandlerInfo handler = handlers.get(session.getPath());
        if (handler == null) return;

        if (handler.onError != null) {
            try {
                handler.onError.invoke(session, errorMessage);
            } catch (Throwable e) {
                handleCallbackFailure("onError", e);
            }
        }
    }

    /**
     * Get active session by ID.
     */
    public WebSocketSession getSession(long sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Get all active sessions.
     */
    public Map<Long, WebSocketSession> getAllSessions() {
        return Collections.unmodifiableMap(sessions);
    }

    /**
     * Get all session IDs.
     */
    public Set<Long> getSessionIds() {
        return Collections.unmodifiableSet(sessions.keySet());
    }

    /**
     * Get session count.
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /**
     * Parse key=value params.
     */
    private Map<String, String> parseParams(String params, boolean plusAsSpace) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        int start = 0;
        while (start < params.length()) {
            int end = params.indexOf('&', start);
            if (end < 0) {
                end = params.length();
            }
            int idx = params.indexOf('=', start);
            if (idx > start && idx < end) {
                map.put(
                        UrlCodec.decodeComponent(params.substring(start, idx), plusAsSpace),
                        UrlCodec.decodeComponent(params.substring(idx + 1, end), plusAsSpace)
                );
            }
            start = end + 1;
        }
        return map;
    }

    private static void debugLog(String message) {
        if (isDebugEnabled()) {
            FrameworkLogger.debug(message);
        }
    }

    private static void debugError(String message) {
        FrameworkLogger.error(message);
    }

    private static void handleCallbackFailure(String callback, Throwable failure) {
        if (failure instanceof VirtualMachineError virtualMachineError) {
            throw virtualMachineError;
        }
        if (failure instanceof ThreadDeath threadDeath) {
            throw threadDeath;
        }
        debugError("[WebSocketRegistry] Error in " + callback + ": " + failure.getMessage());
    }

    private static boolean isDebugEnabled() {
        return Boolean.getBoolean("reactor.rust.java.debug") || FrameworkLogger.isDebugEnabled();
    }

    /**
     * Handler info - stores methods for lifecycle callbacks.
     */
    public static class WebSocketHandlerInfo {
        public final Object bean;
        public final Class<?> clazz;
        public Method onOpenMethod;
        public Method onMessageMethod;
        public Method onBinaryMethod;
        public Method onCloseMethod;
        public Method onErrorMethod;

        private SessionCallback onOpen;
        private TextCallback onMessage;
        private BinaryCallback onBinary;
        private SessionCallback onClose;
        private TextCallback onError;

        public WebSocketHandlerInfo(Object bean, Class<?> clazz) {
            this.bean = bean;
            this.clazz = clazz;
            scanMethods();
        }

        private void scanMethods() {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(OnOpen.class)) {
                    requireUnset(onOpenMethod, OnOpen.class);
                    onOpenMethod = method;
                    onOpen = sessionCallback(method);
                }
                if (method.isAnnotationPresent(OnMessage.class)) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length == 2 && params[1] == byte[].class) {
                        requireUnset(onBinaryMethod, OnMessage.class);
                        onBinaryMethod = method;
                        onBinary = binaryCallback(method);
                    } else {
                        requireUnset(onMessageMethod, OnMessage.class);
                        onMessageMethod = method;
                        onMessage = textCallback(method);
                    }
                }
                if (method.isAnnotationPresent(OnClose.class)) {
                    requireUnset(onCloseMethod, OnClose.class);
                    onCloseMethod = method;
                    onClose = sessionCallback(method);
                }
                if (method.isAnnotationPresent(OnError.class)) {
                    requireUnset(onErrorMethod, OnError.class);
                    onErrorMethod = method;
                    onError = textCallback(method);
                }
            }
        }

        private SessionCallback sessionCallback(Method method) {
            MethodHandle handle = callbackHandle(
                    method,
                    MethodType.methodType(void.class, WebSocketSession.class));
            return session -> {
                handle.invokeExact(session);
            };
        }

        private TextCallback textCallback(Method method) {
            MethodHandle handle = callbackHandle(
                    method,
                    MethodType.methodType(void.class, WebSocketSession.class, String.class));
            return (session, value) -> {
                handle.invokeExact(session, value);
            };
        }

        private BinaryCallback binaryCallback(Method method) {
            MethodHandle handle = callbackHandle(
                    method,
                    MethodType.methodType(void.class, WebSocketSession.class, byte[].class));
            return (session, value) -> {
                handle.invokeExact(session, value);
            };
        }

        private MethodHandle callbackHandle(Method method, MethodType expectedType) {
            if (method.getReturnType() != void.class
                    || !Arrays.equals(method.getParameterTypes(), expectedType.parameterArray())) {
                throw new IllegalArgumentException(
                        "Invalid WebSocket callback signature for " + method.toGenericString()
                                + "; expected " + expectedType);
            }
            try {
                return MethodHandles.privateLookupIn(clazz, MethodHandles.lookup())
                        .unreflect(method)
                        .bindTo(bean)
                        .asType(expectedType);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException(
                        "WebSocket callback is not accessible: " + method.toGenericString(),
                        e);
            }
        }

        private void requireUnset(Method current, Class<?> annotationType) {
            if (current != null) {
                throw new IllegalArgumentException(
                        "Duplicate @" + annotationType.getSimpleName() + " callback in " + clazz.getName());
            }
        }
    }

    @FunctionalInterface
    private interface SessionCallback {
        void invoke(WebSocketSession session) throws Throwable;
    }

    @FunctionalInterface
    private interface TextCallback {
        void invoke(WebSocketSession session, String value) throws Throwable;
    }

    @FunctionalInterface
    private interface BinaryCallback {
        void invoke(WebSocketSession session, byte[] value) throws Throwable;
    }
}

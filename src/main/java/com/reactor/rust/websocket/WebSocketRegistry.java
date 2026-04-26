package com.reactor.rust.websocket;

import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.websocket.annotation.*;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for WebSocket handlers.
 * Scans for @WebSocket annotated classes and manages their lifecycle.
 */
public final class WebSocketRegistry {

    private static final WebSocketRegistry INSTANCE = new WebSocketRegistry();

    public static WebSocketRegistry getInstance() {
        return INSTANCE;
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

        handlers.put(path, info);
        debugLog("[WebSocketRegistry] Registered handler: " + path + " -> " + clazz.getName());
    }

    /**
     * Scan and register all @WebSocket beans from container.
     */
    public void scanAndRegister() {
        for (Object bean : BeanContainer.getInstance().getBeansOfType(Object.class)) {
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
                parseParams(pathParams),
                parseParams(queryParams)
        );
        sessions.put(sessionId, session);

        if (handler.onOpenMethod != null) {
            try {
                handler.onOpenMethod.invoke(handler.bean, session);
            } catch (Exception e) {
                debugError("[WebSocketRegistry] Error in onOpen: " + e.getMessage());
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

        if (handler.onMessageMethod != null) {
            try {
                handler.onMessageMethod.invoke(handler.bean, session, message);
            } catch (Exception e) {
                debugError("[WebSocketRegistry] Error in onMessage: " + e.getMessage());
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

        if (handler.onBinaryMethod != null) {
            try {
                handler.onBinaryMethod.invoke(handler.bean, session, data);
            } catch (Exception e) {
                debugError("[WebSocketRegistry] Error in onBinary: " + e.getMessage());
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

        if (handler.onCloseMethod != null) {
            try {
                handler.onCloseMethod.invoke(handler.bean, session);
            } catch (Exception e) {
                debugError("[WebSocketRegistry] Error in onClose: " + e.getMessage());
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

        if (handler.onErrorMethod != null) {
            try {
                handler.onErrorMethod.invoke(handler.bean, session, errorMessage);
            } catch (Exception e) {
                debugError("[WebSocketRegistry] Error in onError: " + e.getMessage());
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
    private Map<String, String> parseParams(String params) {
        Map<String, String> map = new ConcurrentHashMap<>();
        if (params == null || params.isEmpty()) {
            return map;
        }
        for (String pair : params.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                map.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return map;
    }

    private static void debugLog(String message) {
        if (NativeBridge.isDebugLoggingEnabled()) {
            FrameworkLogger.debug(message);
        }
    }

    private static void debugError(String message) {
        if (NativeBridge.isDebugLoggingEnabled()) {
            FrameworkLogger.debugError(message);
        }
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

        public WebSocketHandlerInfo(Object bean, Class<?> clazz) {
            this.bean = bean;
            this.clazz = clazz;
            scanMethods();
        }

        private void scanMethods() {
            for (Method method : clazz.getDeclaredMethods()) {
                method.setAccessible(true);

                if (method.isAnnotationPresent(OnOpen.class)) {
                    onOpenMethod = method;
                }
                if (method.isAnnotationPresent(OnMessage.class)) {
                    // Check if it's binary or text
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length >= 2 && params[1] == byte[].class) {
                        onBinaryMethod = method;
                    } else {
                        onMessageMethod = method;
                    }
                }
                if (method.isAnnotationPresent(OnClose.class)) {
                    onCloseMethod = method;
                }
                if (method.isAnnotationPresent(OnError.class)) {
                    onErrorMethod = method;
                }
            }
        }
    }
}

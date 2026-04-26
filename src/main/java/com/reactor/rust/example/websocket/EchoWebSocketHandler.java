package com.reactor.rust.example.websocket;

import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.websocket.WebSocketSession;
import com.reactor.rust.websocket.annotation.*;

/**
 * Example WebSocket Handler - Echo Server
 *
 * Demonstrates @WebSocket annotation usage.
 */
@Component
@WebSocket("/ws/echo")
public class EchoWebSocketHandler {

    @OnOpen
    public void onOpen(WebSocketSession session) {
        FrameworkLogger.debug("[WebSocket] Session opened: " + session.getId());
        session.sendText("{\"type\":\"connected\",\"sessionId\":" + session.getId() + "}");
    }

    @OnMessage
    public void onMessage(WebSocketSession session, String message) {
        FrameworkLogger.debug("[WebSocket] Received from " + session.getId() + ": " + message);
        // Echo back with prefix
        session.sendText("{\"type\":\"echo\",\"message\":\"" + escapeJson(message) + "\"}");
    }

    @OnClose
    public void onClose(WebSocketSession session) {
        FrameworkLogger.debug("[WebSocket] Session closed: " + session.getId());
    }

    @OnError
    public void onError(WebSocketSession session, String error) {
        FrameworkLogger.debugError("[WebSocket] Error on session " + session.getId() + ": " + error);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}

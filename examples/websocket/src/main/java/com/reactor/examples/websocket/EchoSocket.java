package com.reactor.examples.websocket;

import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.websocket.WebSocketSession;
import com.reactor.rust.websocket.annotation.OnClose;
import com.reactor.rust.websocket.annotation.OnMessage;
import com.reactor.rust.websocket.annotation.OnOpen;
import com.reactor.rust.websocket.annotation.WebSocket;

@Component
@WebSocket("/ws/echo")
public final class EchoSocket {

    @OnOpen
    public void open(WebSocketSession session) {
        session.sendText("{\"type\":\"connected\"}");
    }

    @OnMessage
    public void message(WebSocketSession session, String message) {
        session.sendText("{\"type\":\"echo\",\"message\":\"" + escape(message) + "\"}");
    }

    @OnClose
    public void close(WebSocketSession session) {
        // Session cleanup belongs here when the application stores per-session state.
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}

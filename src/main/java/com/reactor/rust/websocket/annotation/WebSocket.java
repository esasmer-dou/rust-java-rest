package com.reactor.rust.websocket.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a WebSocket handler.
 *
 * Example:
 * @WebSocket("/ws/chat")
 * public class ChatWebSocketHandler {
 *     @OnOpen
 *     public void onOpen(WebSocketSession session) { }
 *
 *     @OnMessage
 *     public void onMessage(WebSocketSession session, String message) { }
 *
 *     @OnClose
 *     public void onClose(WebSocketSession session) { }
 * }
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WebSocket {
    /**
     * WebSocket endpoint path.
     */
    String value();
}

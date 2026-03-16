package com.reactor.rust.example.websocket;

import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.websocket.WebSocketSession;
import com.reactor.rust.websocket.annotation.*;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Example WebSocket Handler - Simple Chat Room
 *
 * Demonstrates broadcasting messages to all connected clients.
 */
@Component
@WebSocket("/ws/chat/{roomId}")
public class ChatWebSocketHandler {

    // Store all sessions by room
    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(WebSocketSession session) {
        String roomId = session.getPathParams().get("roomId");
        if (roomId == null) roomId = "default";

        // Add session to room
        rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);

        System.out.println("[Chat] User joined room: " + roomId + " (sessionId: " + session.getId() + ")");

        // Broadcast join message
        broadcast(roomId, "{\"type\":\"join\",\"sessionId\":" + session.getId() + ",\"room\":\"" + roomId + "\"}");
    }

    @OnMessage
    public void onMessage(WebSocketSession session, String message) {
        String roomId = session.getPathParams().get("roomId");
        if (roomId == null) roomId = "default";

        System.out.println("[Chat] Message in room " + roomId + ": " + message);

        // Broadcast to all users in room
        broadcast(roomId, "{\"type\":\"message\",\"sessionId\":" + session.getId() + ",\"text\":\"" + escapeJson(message) + "\"}");
    }

    @OnClose
    public void onClose(WebSocketSession session) {
        String roomId = session.getPathParams().get("roomId");
        if (roomId == null) roomId = "default";

        // Remove from room
        Set<WebSocketSession> roomSessions = rooms.get(roomId);
        if (roomSessions != null) {
            roomSessions.remove(session);
        }

        System.out.println("[Chat] User left room: " + roomId + " (sessionId: " + session.getId() + ")");

        // Broadcast leave message
        broadcast(roomId, "{\"type\":\"leave\",\"sessionId\":" + session.getId() + "}");
    }

    @OnError
    public void onError(WebSocketSession session, String error) {
        System.err.println("[Chat] Error: " + error);
    }

    /**
     * Broadcast message to all sessions in room.
     */
    private void broadcast(String roomId, String message) {
        Set<WebSocketSession> roomSessions = rooms.get(roomId);
        if (roomSessions != null) {
            for (WebSocketSession s : roomSessions) {
                try {
                    s.sendText(message);
                } catch (Exception e) {
                    // Session might be closed
                }
            }
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}

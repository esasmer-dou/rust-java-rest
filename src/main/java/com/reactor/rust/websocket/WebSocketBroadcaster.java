package com.reactor.rust.websocket;

import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.logging.FrameworkLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Predicate;

/**
 * WebSocket broadcast and room management.
 * Allows sending messages to all sessions or specific rooms.
 */
public final class WebSocketBroadcaster {

    private static final WebSocketBroadcaster INSTANCE = new WebSocketBroadcaster();

    public static WebSocketBroadcaster getInstance() {
        return INSTANCE;
    }

    // Room name -> session IDs
    private final Map<String, Set<Long>> rooms = new ConcurrentHashMap<>();

    // Session ID -> room names (for quick lookup when session closes)
    private final Map<Long, Set<String>> sessionRooms = new ConcurrentHashMap<>();

    private WebSocketBroadcaster() {}

    /**
     * Join a session to a room.
     *
     * @param sessionId The session ID
     * @param roomName The room name
     */
    public void joinRoom(long sessionId, String roomName) {
        rooms.computeIfAbsent(roomName, k -> new CopyOnWriteArraySet<>()).add(sessionId);
        sessionRooms.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>()).add(roomName);
    }

    /**
     * Remove a session from a room.
     *
     * @param sessionId The session ID
     * @param roomName The room name
     */
    public void leaveRoom(long sessionId, String roomName) {
        Set<Long> roomSessions = rooms.get(roomName);
        if (roomSessions != null) {
            roomSessions.remove(sessionId);
            if (roomSessions.isEmpty()) {
                rooms.remove(roomName);
            }
        }

        Set<String> roomsForSession = sessionRooms.get(sessionId);
        if (roomsForSession != null) {
            roomsForSession.remove(roomName);
        }
    }

    /**
     * Remove a session from all rooms (call when session closes).
     *
     * @param sessionId The session ID
     */
    public void removeFromAllRooms(long sessionId) {
        Set<String> roomsForSession = sessionRooms.remove(sessionId);
        if (roomsForSession != null) {
            for (String room : roomsForSession) {
                Set<Long> roomSessions = rooms.get(room);
                if (roomSessions != null) {
                    roomSessions.remove(sessionId);
                    if (roomSessions.isEmpty()) {
                        rooms.remove(room);
                    }
                }
            }
        }
    }

    /**
     * Get all sessions in a room.
     *
     * @param roomName The room name
     * @return Set of session IDs in the room
     */
    public Set<Long> getSessionsInRoom(String roomName) {
        return Collections.unmodifiableSet(rooms.getOrDefault(roomName, Set.of()));
    }

    /**
     * Get all rooms a session belongs to.
     *
     * @param sessionId The session ID
     * @return Set of room names
     */
    public Set<String> getRoomsForSession(long sessionId) {
        return Collections.unmodifiableSet(sessionRooms.getOrDefault(sessionId, Set.of()));
    }

    /**
     * Get room count.
     */
    public int getRoomCount() {
        return rooms.size();
    }

    /**
     * Get session count in a room.
     */
    public int getSessionCountInRoom(String roomName) {
        Set<Long> sessions = rooms.get(roomName);
        return sessions != null ? sessions.size() : 0;
    }

    /**
     * Get all room names.
     */
    public Set<String> getRoomNames() {
        return Collections.unmodifiableSet(rooms.keySet());
    }

    // ========== Broadcast Methods ==========

    /**
     * Broadcast text message to all connected sessions.
     *
     * @param message The message to broadcast
     * @return Number of sessions the message was sent to
     */
    public int broadcast(String message) {
        return broadcast(message, (WebSocketSession) null);
    }

    /**
     * Broadcast text message to all connected sessions except the sender.
     *
     * @param message The message to broadcast
     * @param excludeSession Session to exclude (typically the sender)
     * @return Number of sessions the message was sent to
     */
    public int broadcast(String message, WebSocketSession excludeSession) {
        long excludeId = excludeSession != null ? excludeSession.getId() : -1;
        return broadcast(message, excludeId);
    }

    /**
     * Broadcast text message to all connected sessions except the sender.
     *
     * @param message The message to broadcast
     * @param excludeSessionId Session ID to exclude
     * @return Number of sessions the message was sent to
     */
    public int broadcast(String message, long excludeSessionId) {
        int count = 0;
        WebSocketRegistry registry = WebSocketRegistry.getInstance();

        for (Long sessionId : registry.getSessionIds()) {
            if (sessionId != excludeSessionId) {
                WebSocketSession session = registry.getSession(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        session.sendText(message);
                        count++;
                    } catch (Exception e) {
                        debugError("[WebSocketBroadcaster] Error sending to session " + sessionId + ": " + e.getMessage());
                    }
                }
            }
        }
        return count;
    }

    /**
     * Broadcast text message to all sessions in a room.
     *
     * @param roomName The room name
     * @param message The message to broadcast
     * @return Number of sessions the message was sent to
     */
    public int broadcastToRoom(String roomName, String message) {
        return broadcastToRoom(roomName, message, -1);
    }

    /**
     * Broadcast text message to all sessions in a room except sender.
     *
     * @param roomName The room name
     * @param message The message to broadcast
     * @param excludeSession Session to exclude
     * @return Number of sessions the message was sent to
     */
    public int broadcastToRoom(String roomName, String message, WebSocketSession excludeSession) {
        long excludeId = excludeSession != null ? excludeSession.getId() : -1;
        return broadcastToRoom(roomName, message, excludeId);
    }

    /**
     * Broadcast text message to all sessions in a room except sender.
     *
     * @param roomName The room name
     * @param message The message to broadcast
     * @param excludeSessionId Session ID to exclude
     * @return Number of sessions the message was sent to
     */
    public int broadcastToRoom(String roomName, String message, long excludeSessionId) {
        Set<Long> roomSessions = rooms.get(roomName);
        if (roomSessions == null || roomSessions.isEmpty()) {
            return 0;
        }

        int count = 0;
        WebSocketRegistry registry = WebSocketRegistry.getInstance();

        for (Long sessionId : roomSessions) {
            if (sessionId != excludeSessionId) {
                WebSocketSession session = registry.getSession(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        session.sendText(message);
                        count++;
                    } catch (Exception e) {
                        debugError("[WebSocketBroadcaster] Error sending to session " + sessionId + ": " + e.getMessage());
                    }
                }
            }
        }
        return count;
    }

    /**
     * Broadcast binary message to all sessions.
     *
     * @param data The binary data to broadcast
     * @return Number of sessions the message was sent to
     */
    public int broadcastBinary(byte[] data) {
        return broadcastBinary(data, -1);
    }

    /**
     * Broadcast binary message to all sessions except sender.
     *
     * @param data The binary data to broadcast
     * @param excludeSessionId Session ID to exclude
     * @return Number of sessions the message was sent to
     */
    public int broadcastBinary(byte[] data, long excludeSessionId) {
        int count = 0;
        WebSocketRegistry registry = WebSocketRegistry.getInstance();

        for (Long sessionId : registry.getSessionIds()) {
            if (sessionId != excludeSessionId) {
                WebSocketSession session = registry.getSession(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        session.sendBinary(data);
                        count++;
                    } catch (Exception e) {
                        debugError("[WebSocketBroadcaster] Error sending to session " + sessionId + ": " + e.getMessage());
                    }
                }
            }
        }
        return count;
    }

    /**
     * Broadcast binary message to all sessions in a room.
     *
     * @param roomName The room name
     * @param data The binary data to broadcast
     * @return Number of sessions the message was sent to
     */
    public int broadcastBinaryToRoom(String roomName, byte[] data) {
        return broadcastBinaryToRoom(roomName, data, -1);
    }

    /**
     * Broadcast binary message to all sessions in a room except sender.
     *
     * @param roomName The room name
     * @param data The binary data to broadcast
     * @param excludeSessionId Session ID to exclude
     * @return Number of sessions the message was sent to
     */
    public int broadcastBinaryToRoom(String roomName, byte[] data, long excludeSessionId) {
        Set<Long> roomSessions = rooms.get(roomName);
        if (roomSessions == null || roomSessions.isEmpty()) {
            return 0;
        }

        int count = 0;
        WebSocketRegistry registry = WebSocketRegistry.getInstance();

        for (Long sessionId : roomSessions) {
            if (sessionId != excludeSessionId) {
                WebSocketSession session = registry.getSession(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        session.sendBinary(data);
                        count++;
                    } catch (Exception e) {
                        debugError("[WebSocketBroadcaster] Error sending to session " + sessionId + ": " + e.getMessage());
                    }
                }
            }
        }
        return count;
    }

    /**
     * Send message to specific sessions matching a predicate.
     *
     * @param message The message to send
     * @param filter Predicate to filter sessions
     * @return Number of sessions the message was sent to
     */
    public int broadcastFiltered(String message, Predicate<WebSocketSession> filter) {
        int count = 0;
        WebSocketRegistry registry = WebSocketRegistry.getInstance();

        for (WebSocketSession session : registry.getAllSessions().values()) {
            if (session != null && session.isOpen() && filter.test(session)) {
                try {
                    session.sendText(message);
                    count++;
                } catch (Exception e) {
                    debugError("[WebSocketBroadcaster] Error sending to session " + session.getId() + ": " + e.getMessage());
                }
            }
        }
        return count;
    }

    private static void debugError(String message) {
        if (NativeBridge.isDebugLoggingEnabled()) {
            FrameworkLogger.debugError(message);
        }
    }

    /**
     * Get total number of connected sessions.
     */
    public int getConnectedSessionCount() {
        return WebSocketRegistry.getInstance().getSessionCount();
    }

    /**
     * Get all connected session IDs.
     */
    public Set<Long> getConnectedSessionIds() {
        return WebSocketRegistry.getInstance().getSessionIds();
    }
}

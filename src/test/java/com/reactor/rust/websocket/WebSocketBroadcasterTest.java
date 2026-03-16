package com.reactor.rust.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WebSocketBroadcaster room management functionality.
 */
class WebSocketBroadcasterTest {

    private WebSocketBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = WebSocketBroadcaster.getInstance();
        // Clean up any existing rooms
        for (String room : broadcaster.getRoomNames()) {
            for (Long sessionId : Set.copyOf(broadcaster.getSessionsInRoom(room))) {
                broadcaster.leaveRoom(sessionId, room);
            }
        }
    }

    @Test
    @DisplayName("Join session to room")
    void testJoinRoom() {
        long sessionId = 1001L;
        String roomName = "chat-room-1";

        broadcaster.joinRoom(sessionId, roomName);

        assertTrue(broadcaster.getSessionsInRoom(roomName).contains(sessionId));
        assertTrue(broadcaster.getRoomsForSession(sessionId).contains(roomName));
        assertEquals(1, broadcaster.getSessionCountInRoom(roomName));
    }

    @Test
    @DisplayName("Leave session from room")
    void testLeaveRoom() {
        long sessionId = 1001L;
        String roomName = "chat-room-1";

        broadcaster.joinRoom(sessionId, roomName);
        broadcaster.leaveRoom(sessionId, roomName);

        assertFalse(broadcaster.getSessionsInRoom(roomName).contains(sessionId));
        assertFalse(broadcaster.getRoomsForSession(sessionId).contains(roomName));
        assertEquals(0, broadcaster.getSessionCountInRoom(roomName));
    }

    @Test
    @DisplayName("Session can join multiple rooms")
    void testJoinMultipleRooms() {
        long sessionId = 1001L;
        String room1 = "room-1";
        String room2 = "room-2";
        String room3 = "room-3";

        broadcaster.joinRoom(sessionId, room1);
        broadcaster.joinRoom(sessionId, room2);
        broadcaster.joinRoom(sessionId, room3);

        Set<String> rooms = broadcaster.getRoomsForSession(sessionId);
        assertEquals(3, rooms.size());
        assertTrue(rooms.contains(room1));
        assertTrue(rooms.contains(room2));
        assertTrue(rooms.contains(room3));
    }

    @Test
    @DisplayName("Remove session from all rooms")
    void testRemoveFromAllRooms() {
        long sessionId = 1001L;

        broadcaster.joinRoom(sessionId, "room-1");
        broadcaster.joinRoom(sessionId, "room-2");
        broadcaster.joinRoom(sessionId, "room-3");

        broadcaster.removeFromAllRooms(sessionId);

        assertTrue(broadcaster.getRoomsForSession(sessionId).isEmpty());
        assertEquals(0, broadcaster.getSessionCountInRoom("room-1"));
        assertEquals(0, broadcaster.getSessionCountInRoom("room-2"));
        assertEquals(0, broadcaster.getSessionCountInRoom("room-3"));
    }

    @Test
    @DisplayName("Multiple sessions in same room")
    void testMultipleSessionsInRoom() {
        String roomName = "chat-room";

        broadcaster.joinRoom(1001L, roomName);
        broadcaster.joinRoom(1002L, roomName);
        broadcaster.joinRoom(1003L, roomName);

        assertEquals(3, broadcaster.getSessionCountInRoom(roomName));
        assertTrue(broadcaster.getSessionsInRoom(roomName).contains(1001L));
        assertTrue(broadcaster.getSessionsInRoom(roomName).contains(1002L));
        assertTrue(broadcaster.getSessionsInRoom(roomName).contains(1003L));
    }

    @Test
    @DisplayName("Get all room names")
    void testGetRoomNames() {
        broadcaster.joinRoom(1001L, "room-a");
        broadcaster.joinRoom(1002L, "room-b");
        broadcaster.joinRoom(1003L, "room-c");

        Set<String> rooms = broadcaster.getRoomNames();
        assertTrue(rooms.contains("room-a"));
        assertTrue(rooms.contains("room-b"));
        assertTrue(rooms.contains("room-c"));
    }

    @Test
    @DisplayName("Empty room is removed")
    void testEmptyRoomIsRemoved() {
        String roomName = "temp-room";
        long sessionId = 1001L;

        broadcaster.joinRoom(sessionId, roomName);
        assertTrue(broadcaster.getRoomNames().contains(roomName));

        broadcaster.leaveRoom(sessionId, roomName);
        assertFalse(broadcaster.getRoomNames().contains(roomName));
    }

    @Test
    @DisplayName("Get sessions for non-existent room")
    void testGetSessionsForNonExistentRoom() {
        Set<Long> sessions = broadcaster.getSessionsInRoom("non-existent");

        assertNotNull(sessions);
        assertTrue(sessions.isEmpty());
    }

    @Test
    @DisplayName("Get rooms for non-existent session")
    void testGetRoomsForNonExistentSession() {
        Set<String> rooms = broadcaster.getRoomsForSession(99999L);

        assertNotNull(rooms);
        assertTrue(rooms.isEmpty());
    }

    @Test
    @DisplayName("Get room count")
    void testGetRoomCount() {
        broadcaster.joinRoom(1001L, "room-1");
        broadcaster.joinRoom(1002L, "room-2");

        assertTrue(broadcaster.getRoomCount() >= 2);
    }

    @Test
    @DisplayName("Singleton instance")
    void testSingletonInstance() {
        WebSocketBroadcaster instance1 = WebSocketBroadcaster.getInstance();
        WebSocketBroadcaster instance2 = WebSocketBroadcaster.getInstance();

        assertSame(instance1, instance2);
    }

    @Test
    @DisplayName("Leave room for session not in room does not throw")
    void testLeaveRoomNotMember() {
        assertDoesNotThrow(() -> broadcaster.leaveRoom(99999L, "some-room"));
    }

    @Test
    @DisplayName("Join same session to same room twice")
    void testJoinSameRoomTwice() {
        long sessionId = 1001L;
        String roomName = "room";

        broadcaster.joinRoom(sessionId, roomName);
        broadcaster.joinRoom(sessionId, roomName);

        assertEquals(1, broadcaster.getSessionCountInRoom(roomName));
    }
}

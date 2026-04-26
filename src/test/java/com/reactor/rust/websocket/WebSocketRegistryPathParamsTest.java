package com.reactor.rust.websocket;

import com.reactor.rust.websocket.annotation.OnOpen;
import com.reactor.rust.websocket.annotation.WebSocket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketRegistryPathParamsTest {

    @WebSocket("/test/ws/{roomId}")
    static class TestWebSocketHandler {
        WebSocketSession openedSession;

        @OnOpen
        public void onOpen(WebSocketSession session) {
            openedSession = session;
        }
    }

    @Test
    void onOpenCarriesPathAndQueryParamsFromNativeHandshake() {
        WebSocketRegistry registry = WebSocketRegistry.getInstance();
        TestWebSocketHandler handler = new TestWebSocketHandler();

        registry.register(handler);
        registry.onOpen(987654321L, "/test/ws/{roomId}", "roomId=alpha", "token=abc&limit=5");

        assertEquals(987654321L, handler.openedSession.getId());
        assertEquals("/test/ws/{roomId}", handler.openedSession.getPath());
        assertEquals("alpha", handler.openedSession.getPathParams().get("roomId"));
        assertEquals("abc", handler.openedSession.getQueryParams().get("token"));
        assertEquals("5", handler.openedSession.getQueryParams().get("limit"));

        registry.onClose(987654321L);
    }
}

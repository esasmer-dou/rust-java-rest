package com.reactor.rust.websocket;

import com.reactor.rust.websocket.annotation.OnClose;
import com.reactor.rust.websocket.annotation.OnError;
import com.reactor.rust.websocket.annotation.OnMessage;
import com.reactor.rust.websocket.annotation.OnOpen;
import com.reactor.rust.websocket.annotation.WebSocket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketRegistryCallbackTest {

    @WebSocket("/test/ws/callbacks")
    static final class CallbackHandler {
        private WebSocketSession session;
        private String text;
        private byte[] binary;
        private String error;
        private boolean closed;

        @OnOpen
        private void open(WebSocketSession value) {
            session = value;
        }

        @OnMessage
        private void text(WebSocketSession ignored, String value) {
            text = value;
        }

        @OnMessage
        private void binary(WebSocketSession ignored, byte[] value) {
            binary = value;
        }

        @OnError
        private void error(WebSocketSession ignored, String value) {
            error = value;
        }

        @OnClose
        private void close(WebSocketSession value) {
            session = value;
            closed = true;
        }
    }

    @WebSocket("/test/ws/invalid")
    static final class InvalidHandler {
        @OnOpen
        private String open(WebSocketSession ignored) {
            return "invalid";
        }
    }

    @WebSocket("/test/ws/duplicate")
    static final class DuplicateHandler {
        @OnOpen
        private void first(WebSocketSession ignored) {
        }

        @OnOpen
        private void second(WebSocketSession ignored) {
        }
    }

    @WebSocket("/test/ws/duplicate-path")
    static final class FirstPathHandler {
    }

    @WebSocket("/test/ws/duplicate-path")
    static final class SecondPathHandler {
    }

    @WebSocket("/test/ws/fatal")
    static final class FatalHandler {
        @OnOpen
        private void open(WebSocketSession ignored) {
            throw new StackOverflowError("fatal-callback");
        }
    }

    @Test
    void invokesPrecompiledLifecycleCallbacks() {
        WebSocketRegistry registry = WebSocketRegistry.getInstance();
        CallbackHandler handler = new CallbackHandler();
        registry.register(handler);

        long sessionId = 987654322L;
        registry.onOpen(sessionId, "/test/ws/callbacks", "", "");
        registry.onMessage(sessionId, "hello");
        registry.onBinary(sessionId, new byte[] {1, 2, 3});
        registry.onError(sessionId, "network");

        assertEquals(sessionId, handler.session.getId());
        assertEquals("hello", handler.text);
        assertArrayEquals(new byte[] {1, 2, 3}, handler.binary);
        assertEquals("network", handler.error);

        registry.onClose(sessionId);
        assertFalse(handler.session.isOpen());
        assertTrue(handler.closed);
        assertNull(registry.getSession(sessionId));
    }

    @Test
    void rejectsInvalidAndDuplicateCallbacksAtStartup() {
        WebSocketRegistry registry = WebSocketRegistry.getInstance();
        assertThrows(IllegalArgumentException.class, () -> registry.register(new InvalidHandler()));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new DuplicateHandler()));
    }

    @Test
    void rejectsDuplicatePathsInsteadOfSilentlyReplacingHandler() {
        WebSocketRegistry registry = WebSocketRegistry.getInstance();
        registry.register(new FirstPathHandler());

        assertThrows(IllegalStateException.class, () -> registry.register(new SecondPathHandler()));
        assertEquals(FirstPathHandler.class, registry.getHandler("/test/ws/duplicate-path").clazz);
    }

    @Test
    void doesNotSwallowJvmFatalCallbackFailures() {
        WebSocketRegistry registry = WebSocketRegistry.getInstance();
        registry.register(new FatalHandler());
        long sessionId = 987654323L;

        assertThrows(StackOverflowError.class, () ->
                registry.onOpen(sessionId, "/test/ws/fatal", "", ""));
        registry.onClose(sessionId);
    }
}

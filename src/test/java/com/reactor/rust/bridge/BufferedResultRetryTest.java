package com.reactor.rust.bridge;

import com.reactor.rust.exception.ExceptionHandlerRegistry;
import com.reactor.rust.http.RawResponse;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class BufferedResultRetryTest {
    private static final int FLAG = 1 << 30;

    static class Handler {
        final AtomicInteger calls = new AtomicInteger();
        final byte[] payload = ("{\"value\":\"" + "x".repeat(20_000) + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        public RawResponse annotated() {
            calls.incrementAndGet();
            return RawResponse.json(payload);
        }

        public RawResponse direct(ByteBuffer out, int offset) {
            return annotated();
        }

        public int directWriter(ByteBuffer out, int offset) {
            calls.incrementAndGet();
            return -20_000;
        }

        public RawResponse failure() {
            calls.incrementAndGet();
            throw new IllegalStateException("test failure");
        }

        public CompletableFuture<RawResponse> asyncFailure() {
            return CompletableFuture.failedFuture(new IllegalStateException("async failure"));
        }
    }

    private static HandlerRegistry registry() {
        return HandlerRegistry.create(ExceptionHandlerRegistry.getInstance());
    }

    private static int invoke(HandlerRegistry registry, int id, int capacity) {
        return registry.invokeBuffered(id, ByteBuffer.allocateDirect(capacity), 0, null, "", "", "");
    }

    @Test
    void annotatedAndDirectMaterializedResultsDoNotReplayBusinessLogic() throws Exception {
        for (String method : new String[]{"annotated", "direct"}) {
            HandlerRegistry registry = registry();
            assertTrue(registry.enableBufferedResultRetry(1));
            Handler handler = new Handler();
            int id = registry.registerHandler(handler,
                    method.equals("annotated") ? Handler.class.getMethod(method)
                            : Handler.class.getMethod(method, ByteBuffer.class, int.class),
                    Void.class, RawResponse.class);
            int first = invoke(registry, id, 4096);
            assertTrue(first < -FLAG);
            int needed = -first & ~FLAG;
            // A second undersized continuation must retain the same result again.
            assertEquals(first, registry.retryBufferedResult(id, ByteBuffer.allocateDirect(8192), 0));
            ByteBuffer out = ByteBuffer.allocateDirect(needed);
            assertEquals(needed, registry.retryBufferedResult(id, out, 0));
            assertEquals(1, handler.calls.get());
            int headers = out.getInt(10);
            int body = out.getInt(14);
            byte[] bytes = new byte[body];
            out.position(18 + headers);
            out.get(bytes);
            assertArrayEquals(handler.payload, bytes);
            assertThrows(IllegalStateException.class,
                    () -> registry.retryBufferedResult(id, out, 0));
        }
    }

    @Test
    void abortClearsResultAndCannotContaminateNextRequest() throws Exception {
        HandlerRegistry registry = registry();
        registry.enableBufferedResultRetry(1);
        Handler handler = new Handler();
        int id = registry.registerHandler(handler, Handler.class.getMethod("annotated"), Void.class, RawResponse.class);
        invoke(registry, id, 4096);
        assertEquals(0, registry.retryBufferedResult(id, null, 0));
        assertThrows(IllegalStateException.class,
                () -> registry.retryBufferedResult(id, ByteBuffer.allocateDirect(30000), 0));
        assertTrue(invoke(registry, id, 30000) > 0);
        assertEquals(2, handler.calls.get());
    }

    @Test
    void mismatchedHandlerClearsPendingResult() throws Exception {
        HandlerRegistry registry = registry();
        registry.enableBufferedResultRetry(1);
        Handler handler = new Handler();
        int id = registry.registerHandler(handler, Handler.class.getMethod("annotated"), Void.class, RawResponse.class);
        invoke(registry, id, 4096);
        assertThrows(IllegalStateException.class,
                () -> registry.retryBufferedResult(id + 1, ByteBuffer.allocateDirect(30000), 0));
        assertThrows(IllegalStateException.class,
                () -> registry.retryBufferedResult(id, ByteBuffer.allocateDirect(30000), 0));
    }

    @Test
    void unnegotiatedRuntimeAndIntegerWriterKeepLegacySizeContract() throws Exception {
        Handler handler = new Handler();
        HandlerRegistry legacy = registry();
        assertFalse(legacy.enableBufferedResultRetry(2));
        int id = legacy.registerHandler(handler, Handler.class.getMethod("annotated"), Void.class, RawResponse.class);
        int written = invoke(legacy, id, 4096);
        assertTrue(written < 0 && written > -FLAG);
        HandlerRegistry enabled = registry();
        enabled.enableBufferedResultRetry(1);
        int direct = enabled.registerHandler(handler,
                Handler.class.getMethod("directWriter", ByteBuffer.class, int.class), Void.class, Integer.class);
        assertEquals(-20_000, invoke(enabled, direct, 4096));
    }

    @Test
    void requestGuardAndExceptionMapperAreNotReplayed() throws Exception {
        Handler handler = new Handler();
        AtomicInteger mapped = new AtomicInteger();
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        ExceptionHandlerRegistry exceptions = ExceptionHandlerRegistry.create();
        exceptions.registerGenerated(handler, IllegalStateException.class, (bean, error) -> {
            mapped.incrementAndGet();
            return RawResponse.json(handler.payload);
        });
        HandlerRegistry registry = HandlerRegistry.create(exceptions);
        registry.enableBufferedResultRetry(1);
        int id = registry.registerHandler(handler, Handler.class.getMethod("failure"), Void.class, RawResponse.class);
        registry.attachGuard(id, new RequestGuard() {
            public void before(RequestGuardContext request) { before.incrementAndGet(); }
            public void after(Throwable failure) { after.incrementAndGet(); }
        });
        int first = invoke(registry, id, 4096);
        assertTrue(first < -FLAG);
        ByteBuffer out = ByteBuffer.allocateDirect(-first & ~FLAG);
        assertTrue(registry.retryBufferedResult(id, out, 0) > 0);
        assertEquals(500, out.getShort(8));
        assertEquals(1, handler.calls.get());
        assertEquals(1, mapped.get());
        assertEquals(1, before.get());
        assertEquals(1, after.get());
    }

    @Test
    void asynchronousErrorEncodingDoesNotLeaveSynchronousRetryState() throws Exception {
        Handler handler = new Handler();
        ExceptionHandlerRegistry exceptions = ExceptionHandlerRegistry.create();
        exceptions.registerGenerated(handler, IllegalStateException.class,
                (bean, error) -> RawResponse.json(handler.payload));
        HandlerRegistry registry = HandlerRegistry.create(exceptions);
        registry.enableBufferedResultRetry(1);
        int id = registry.registerHandler(handler, Handler.class.getMethod("asyncFailure"), Void.class, RawResponse.class);
        HandlerRegistry.AsyncResponseFrame frame = registry.invokeAsyncFrame(id, null, "", "", "").join();
        try {
            assertTrue(frame.length() > 20_000);
            assertEquals(500, frame.buffer().getShort(8));
            assertThrows(IllegalStateException.class,
                    () -> registry.retryBufferedResult(id, ByteBuffer.allocateDirect(30000), 0));
        } finally {
            registry.releaseAsyncResponseFrame(frame);
        }
    }
}

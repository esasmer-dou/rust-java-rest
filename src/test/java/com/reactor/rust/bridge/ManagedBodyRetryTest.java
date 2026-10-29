package com.reactor.rust.bridge;

import com.reactor.rust.exception.ExceptionHandlerRegistry;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.json.ManagedJsonBodyProducer;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedBodyRetryTest {
    private static final int FLAG = 1 << 30;
    public static final class Body implements ManagedJsonBodyProducer {
        final AtomicInteger releases = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        final boolean fail;
        Body(boolean fail) { this.fail = fail; }
        public int write(ByteBuffer out, int offset) {
            try {
                if (closed.get()) throw new IllegalStateException("already closed");
                if (fail) throw new IllegalArgumentException("serialization failure");
                if (out.capacity() - offset < 20_000) return -20_000;
                for (int i = 0; i < 20_000; i++) out.put(offset + i, (byte) 'x');
                close();
                return 20_000;
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        public void close() { if (closed.compareAndSet(false, true)) releases.incrementAndGet(); }
    }
    public static final class Handler {
        final Body body;
        final AtomicInteger calls = new AtomicInteger();
        Handler(boolean fail) { body = new Body(fail); }
        public ResponseEntity<?> get() { calls.incrementAndGet(); return ResponseEntity.ok(body); }
        public CompletableFuture<ResponseEntity<?>> async() { return CompletableFuture.completedFuture(get()); }
        public CompletableFuture<ResponseEntity<?>> asyncFailure() {
            return CompletableFuture.failedFuture(new IllegalArgumentException("separate async failure"));
        }
    }

    @Test
    void undersizedRetriesKeepOwnershipAndWriteOnceBeforeRelease() throws Exception {
        var registry = HandlerRegistry.create(ExceptionHandlerRegistry.create());
        registry.enableBufferedResultRetry(1);
        var handler = new Handler(false);
        int id = registry.registerHandler(handler, Handler.class.getMethod("get"), Void.class, Void.class);
        int first = registry.invokeBuffered(id, ByteBuffer.allocateDirect(1), 0, null, "", "", "");
        assertTrue(first < -FLAG); assertEquals(0, handler.body.releases.get());
        assertEquals(first, registry.retryBufferedResult(id, ByteBuffer.allocateDirect(8192), 0));
        assertEquals(0, handler.body.releases.get());
        var out = ByteBuffer.allocateDirect(-first & ~FLAG);
        assertEquals(out.capacity(), registry.retryBufferedResult(id, out, 0));
        assertEquals(1, handler.calls.get()); assertEquals(1, handler.body.releases.get());
        assertEquals(20_000, out.getInt(14));
    }

    @Test
    void abortAndWrongHandlerReleaseRetainedOwnership() throws Exception {
        for (boolean mismatch : new boolean[]{false, true}) {
            var registry = HandlerRegistry.create(ExceptionHandlerRegistry.create());
            registry.enableBufferedResultRetry(1);
            var handler = new Handler(false);
            int id = registry.registerHandler(handler, Handler.class.getMethod("get"), Void.class, Void.class);
            registry.invokeBuffered(id, ByteBuffer.allocateDirect(4096), 0, null, "", "", "");
            if (mismatch) assertThrows(IllegalStateException.class,
                    () -> registry.retryBufferedResult(id + 1, ByteBuffer.allocateDirect(30000), 0));
            else assertEquals(0, registry.retryBufferedResult(id, null, 0));
            assertEquals(1, handler.body.releases.get());
        }
    }

    @Test
    void failedSerializationAndAsyncGrowthReleaseOwnership() throws Exception {
        for (boolean fail : new boolean[]{false, true}) {
            var registry = HandlerRegistry.create(ExceptionHandlerRegistry.create());
            var handler = new Handler(fail);
            int id = registry.registerHandler(handler, Handler.class.getMethod("async"), Void.class, Void.class);
            var frame = registry.invokeAsyncFrame(id, null, "", "", "").join();
            try {
                assertEquals(fail ? 500 : 200, frame.buffer().getShort(8));
                assertEquals(1, handler.body.releases.get()); assertEquals(1, handler.calls.get());
            } finally { registry.releaseAsyncResponseFrame(frame); }
        }
    }

    @Test
    void guardFailureAfterAnUndersizedWriteDiscardsTheOriginalOwnedResult() throws Exception {
        var registry = HandlerRegistry.create(ExceptionHandlerRegistry.create());
        registry.enableBufferedResultRetry(1);
        var handler = new Handler(false);
        int id = registry.registerHandler(handler, Handler.class.getMethod("get"), Void.class, Void.class);
        registry.attachGuard(id, new RequestGuard() {
            public void before(RequestGuardContext context) {}
            public void after(Throwable failure) { throw new IllegalArgumentException("guard rejected response"); }
        });
        registry.invokeBuffered(id, ByteBuffer.allocateDirect(4096), 0, null, "", "", "");
        assertEquals(1, handler.body.releases.get());
    }

    @Test
    void asynchronousErrorDoesNotDiscardAnUnrelatedSynchronousOwnedRetry() throws Exception {
        var registry = HandlerRegistry.create(ExceptionHandlerRegistry.create());
        registry.enableBufferedResultRetry(1);
        var handler = new Handler(false);
        int id = registry.registerHandler(handler, Handler.class.getMethod("get"), Void.class, Void.class);
        int async = registry.registerHandler(handler, Handler.class.getMethod("asyncFailure"), Void.class, Void.class);
        int first = registry.invokeBuffered(id, ByteBuffer.allocateDirect(4096), 0, null, "", "", "");
        var error = registry.invokeAsyncFrame(async, null, "", "", "").join();
        try { assertEquals(500, error.buffer().getShort(8)); assertEquals(0, handler.body.releases.get()); }
        finally { registry.releaseAsyncResponseFrame(error); }
        assertTrue(registry.retryBufferedResult(id, ByteBuffer.allocateDirect(-first & ~FLAG), 0) > 0);
        assertEquals(1, handler.body.releases.get());
    }

    @Test
    void olderNativeRuntimeFailsClosedInsteadOfReplayingAnOwnedBusinessResult() throws Exception {
        var registry = HandlerRegistry.create(ExceptionHandlerRegistry.create());
        var handler = new Handler(false);
        int id = registry.registerHandler(handler, Handler.class.getMethod("get"), Void.class, Void.class);
        var out = ByteBuffer.allocateDirect(4096);
        assertTrue(registry.invokeBuffered(id, out, 0, null, "", "", "") > 0);
        assertEquals(500, out.getShort(8));
        assertEquals(1, handler.body.releases.get()); assertEquals(1, handler.calls.get());
    }

    @Test
    void asynchronousExceptionHandlerIsInvokedOnceAndItsOwnedBodyIsReleased() throws Exception {
        for (boolean fail : new boolean[]{false, true}) {
            var errors = ExceptionHandlerRegistry.create();
            var produced = new java.util.ArrayList<Body>();
            errors.registerGenerated(new Object(), IllegalArgumentException.class, (owner, error) -> {
                var body = new Body(fail);
                produced.add(body);
                return ResponseEntity.status(com.reactor.rust.http.HttpStatus.SERVICE_UNAVAILABLE, body);
            });
            var registry = HandlerRegistry.create(errors);
            var handler = new Handler(false);
            int id = registry.registerHandler(handler, Handler.class.getMethod("asyncFailure"), Void.class, Void.class);
            var frame = registry.invokeAsyncFrame(id, null, "", "", "").join();
            try {
                assertEquals(fail ? 500 : 503, frame.buffer().getShort(8));
                assertEquals(1, produced.size(), "Buffer growth must not replay an exception handler");
                assertEquals(1, produced.get(0).releases.get());
            } finally { registry.releaseAsyncResponseFrame(frame); }
        }
    }
}

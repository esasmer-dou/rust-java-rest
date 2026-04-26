package com.reactor.rust.bridge;

import com.reactor.rust.annotations.RequestBody;
import com.reactor.rust.annotations.HeaderParam;
import com.reactor.rust.annotations.PathVariable;
import com.reactor.rust.annotations.RequestParam;
import com.reactor.rust.http.FileResponse;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerRegistryNativeFrameTest {

    private static final byte[] FRAME_MAGIC =
            new byte[] {'R', 'J', 'R', 'S', 'P', 'V', '1', '!'};
    private static final byte[] FILE_FRAME_MAGIC =
            new byte[] {'R', 'J', 'F', 'I', 'L', 'E', '1', '!'};

    static class LegacyHandler {
        public ResponseEntity<String> notFound(
                ByteBuffer out,
                int offset,
                byte[] inBytes,
                String pathParams,
                String queryString,
                String headers
        ) {
            return ResponseEntity.notFound("missing").header("X-Test", "1");
        }
    }

    static class ModernHandler {
        public ResponseEntity<String> created() {
            return ResponseEntity.created("created");
        }
    }

    static class DirectBodyHandler {
        public ResponseEntity<Integer> bodySize(@RequestBody byte[] body) {
            return ResponseEntity.ok(body.length);
        }
    }

    static class MixedAnnotatedHandler {
        public ResponseEntity<String> combine(
                @PathVariable("id") int id,
                @RequestParam("name") String name,
                @HeaderParam("X-Trace") String trace
        ) {
            return ResponseEntity.ok(id + ":" + name + ":" + trace);
        }
    }

    static class TooManyAnnotatedParamsHandler {
        public ResponseEntity<String> tooMany(
                @RequestParam("p1") String p1,
                @RequestParam("p2") String p2,
                @RequestParam("p3") String p3,
                @RequestParam("p4") String p4,
                @RequestParam("p5") String p5,
                @RequestParam("p6") String p6,
                @RequestParam("p7") String p7,
                @RequestParam("p8") String p8,
                @RequestParam("p9") String p9
        ) {
            return ResponseEntity.ok(p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9);
        }
    }

    static class LocaleSensitiveHeaderHandler {
        public ResponseEntity<String> requestId(@HeaderParam("X-Request-ID") String requestId) {
            return ResponseEntity.ok(requestId);
        }
    }

    static class LargeResponseHandler {
        public ResponseEntity<String> large() {
            return ResponseEntity.ok("0123456789".repeat(12)).header("X-Large", "1");
        }
    }

    static class FileHandler {
        private final Path path;

        FileHandler(Path path) {
            this.path = path;
        }

        public FileResponse directFile() {
            return FileResponse.of(path, "text/plain").header("X-File", "direct");
        }

        public ResponseEntity<FileResponse> entityFile() {
            return ResponseEntity.ok(FileResponse.download(path, "export.txt", "text/plain"))
                    .header("X-Entity", "1");
        }
    }

    static class RawHandler {
        public RawResponse metricsText() {
            return RawResponse.text("metric 1\n", "text/plain");
        }

        public ResponseEntity<RawResponse> entityMetricsText() {
            return ResponseEntity.ok(RawResponse.text("entity_metric 2\n", "text/plain"))
                    .header("X-Entity", "1");
        }
    }

    @Test
    void responseEntityWritesNativeFrameWithStatusHeadersAndBody() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        LegacyHandler handler = new LegacyHandler();
        Method method = LegacyHandler.class.getDeclaredMethod(
                "notFound",
                ByteBuffer.class,
                int.class,
                byte[].class,
                String.class,
                String.class,
                String.class
        );

        int handlerId = registry.registerHandler(handler, method, byte[].class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        assertTrue(written > 18);

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        assertArrayEquals(FRAME_MAGIC, Arrays.copyOfRange(frameBytes, 0, 8));

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(404, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        assertTrue(headersLen > 0);
        assertTrue(bodyLen > 0);

        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("X-Test: 1"));
        assertEquals("\"missing\"", encodedBody);
    }

    @Test
    void noArgModernResponseEntityDoesNotFallBackToLegacyV4Invoke() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        ModernHandler handler = new ModernHandler();
        Method method = ModernHandler.class.getDeclaredMethod("created");

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        assertArrayEquals(FRAME_MAGIC, Arrays.copyOfRange(frameBytes, 0, 8));

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(201, frame.getShort() & 0xFFFF);
    }

    @Test
    void directBodyRequestAvoidsJniByteArrayEntryPoint() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        DirectBodyHandler handler = new DirectBodyHandler();
        Method method = DirectBodyHandler.class.getDeclaredMethod("bodySize", byte[].class);

        int handlerId = registry.registerHandler(handler, method, byte[].class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);
        ByteBuffer body = ByteBuffer.allocateDirect(3);
        body.put(new byte[] {1, 2, 3});

        int written = registry.invokeBufferedDirect(handlerId, out, 0, body, 3, "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        assertArrayEquals(FRAME_MAGIC, Arrays.copyOfRange(frameBytes, 0, 8));

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);
        assertEquals("3", encodedBody);
    }

    @Test
    void annotatedParamsUseExactMethodHandleInvocationPath() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        MixedAnnotatedHandler handler = new MixedAnnotatedHandler();
        Method method = MixedAnnotatedHandler.class.getDeclaredMethod(
                "combine",
                int.class,
                String.class,
                String.class
        );

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(
                handlerId,
                out,
                0,
                new byte[0],
                "id=42",
                "name=mustafa",
                "X-Trace: abc-123\n"
        );

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        assertArrayEquals(FRAME_MAGIC, Arrays.copyOfRange(frameBytes, 0, 8));

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);
        assertEquals("\"42:mustafa:abc-123\"", encodedBody);
    }

    @Test
    void tooManyAnnotatedParamsAreRejectedAtRegistration() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        TooManyAnnotatedParamsHandler handler = new TooManyAnnotatedParamsHandler();
        Method method = TooManyAnnotatedParamsHandler.class.getDeclaredMethod(
                "tooMany",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> registry.registerHandler(handler, method, Void.class, ResponseEntity.class)
        );
        assertTrue(error.getMessage().contains("max supported for exact MethodHandle invocation"));
    }

    @Test
    void headerLookupIsLocaleIndependent() throws Exception {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            HandlerRegistry registry = HandlerRegistry.getInstance();
            LocaleSensitiveHeaderHandler handler = new LocaleSensitiveHeaderHandler();
            Method method = LocaleSensitiveHeaderHandler.class.getDeclaredMethod("requestId", String.class);

            int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
            ByteBuffer out = ByteBuffer.allocate(1024);

            int written = registry.invokeBuffered(
                    handlerId,
                    out,
                    0,
                    new byte[0],
                    "",
                    "",
                    "x-request-id: smoke\n"
            );

            byte[] frameBytes = new byte[written];
            out.position(0);
            out.get(frameBytes);

            ByteBuffer frame = ByteBuffer.wrap(frameBytes);
            frame.position(8);
            assertEquals(200, frame.getShort() & 0xFFFF);

            int headersLen = frame.getInt();
            int bodyLen = frame.getInt();
            String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);
            assertEquals("\"smoke\"", encodedBody);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void responseEntityOverflowReturnsRequiredFrameSizeForNativeRetry() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        LargeResponseHandler handler = new LargeResponseHandler();
        Method method = LargeResponseHandler.class.getDeclaredMethod("large");

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer small = ByteBuffer.allocate(32);

        int required = registry.invokeBuffered(handlerId, small, 0, new byte[0], "", "", "");

        assertTrue(required < 0);
        required = -required;
        assertTrue(required > small.capacity());

        ByteBuffer retry = ByteBuffer.allocate(required);
        int written = registry.invokeBuffered(handlerId, retry, 0, new byte[0], "", "", "");

        assertEquals(required, written);

        byte[] frameBytes = new byte[written];
        retry.position(0);
        retry.get(frameBytes);

        assertArrayEquals(FRAME_MAGIC, Arrays.copyOfRange(frameBytes, 0, 8));

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("X-Large: 1"));
        assertTrue(encodedBody.contains("0123456789"));
    }

    @Test
    void directFileResponseWritesFileFrameWithoutBodySerialization(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("direct.txt"), "file-body", StandardCharsets.UTF_8);
        HandlerRegistry registry = HandlerRegistry.getInstance();
        FileHandler handler = new FileHandler(file);
        Method method = FileHandler.class.getDeclaredMethod("directFile");

        int handlerId = registry.registerHandler(handler, method, Void.class, FileResponse.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        assertArrayEquals(FILE_FRAME_MAGIC, Arrays.copyOfRange(frameBytes, 0, 8));

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int pathLen = frame.getInt();
        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);
        String encodedPath = new String(frameBytes, 18 + headersLen, pathLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("Content-Type: text/plain"));
        assertTrue(encodedHeaders.contains("X-File: direct"));
        assertEquals(file.toAbsolutePath().normalize().toString(), encodedPath);
        assertEquals(18 + headersLen + pathLen, written);
    }

    @Test
    void responseEntityFileResponseMergesHeadersIntoFileFrame(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("entity.txt"), "file-body", StandardCharsets.UTF_8);
        HandlerRegistry registry = HandlerRegistry.getInstance();
        FileHandler handler = new FileHandler(file);
        Method method = FileHandler.class.getDeclaredMethod("entityFile");

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        assertArrayEquals(FILE_FRAME_MAGIC, Arrays.copyOfRange(frameBytes, 0, 8));

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int pathLen = frame.getInt();
        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);
        String encodedPath = new String(frameBytes, 18 + headersLen, pathLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("X-Entity: 1"));
        assertTrue(encodedHeaders.contains("Content-Type: text/plain"));
        assertTrue(encodedHeaders.contains("Content-Disposition: attachment; filename=\"export.txt\""));
        assertEquals(file.toAbsolutePath().normalize().toString(), encodedPath);
    }

    @Test
    void rawResponseWritesTextFrameWithoutJsonSerialization() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        RawHandler handler = new RawHandler();
        Method method = RawHandler.class.getDeclaredMethod("metricsText");

        int handlerId = registry.registerHandler(handler, method, Void.class, RawResponse.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        assertArrayEquals(FRAME_MAGIC, Arrays.copyOfRange(frameBytes, 0, 8));

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("Content-Type: text/plain"));
        assertEquals("metric 1\n", encodedBody);
    }

    @Test
    void responseEntityRawResponseMergesHeadersIntoTextFrame() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        RawHandler handler = new RawHandler();
        Method method = RawHandler.class.getDeclaredMethod("entityMetricsText");

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        assertArrayEquals(FRAME_MAGIC, Arrays.copyOfRange(frameBytes, 0, 8));

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("X-Entity: 1"));
        assertTrue(encodedHeaders.contains("Content-Type: text/plain"));
        assertEquals("entity_metric 2\n", encodedBody);
    }
}

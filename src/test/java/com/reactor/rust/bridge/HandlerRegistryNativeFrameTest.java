package com.reactor.rust.bridge;

import com.reactor.rust.annotations.ContentType;
import com.reactor.rust.annotations.DirectPathBoolean;
import com.reactor.rust.annotations.DirectPathDouble;
import com.reactor.rust.annotations.DirectPathInt;
import com.reactor.rust.annotations.DirectPathLong;
import com.reactor.rust.annotations.DirectPathShort;
import com.reactor.rust.annotations.DirectQueryBoolean;
import com.reactor.rust.annotations.DirectQueryDouble;
import com.reactor.rust.annotations.DirectQueryInt;
import com.reactor.rust.annotations.DirectQueryLong;
import com.reactor.rust.annotations.DirectQueryShort;
import com.reactor.rust.annotations.RequestBody;
import com.reactor.rust.annotations.CookieValue;
import com.reactor.rust.annotations.HeaderParam;
import com.reactor.rust.annotations.PathVariable;
import com.reactor.rust.annotations.RequestParam;
import com.reactor.rust.http.DirectJsonResponse;
import com.reactor.rust.http.FileResponse;
import com.reactor.rust.http.JsonProducerResponse;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.json.DirectJsonWriter;
import com.reactor.rust.json.JsonBodyProducer;
import com.reactor.rust.json.JsonBufferWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerRegistryNativeFrameTest {

    private static final byte[] FRAME_MAGIC =
            new byte[] {'R', 'J', 'R', 'S', 'P', 'V', '1', '!'};
    private static final byte[] FILE_FRAME_MAGIC =
            new byte[] {'R', 'J', 'F', 'I', 'L', 'E', '1', '!'};

    private static String frameBody(ByteBuffer out, int written) {
        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);
        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        frame.getShort();
        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        return new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);
    }

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

        public CompletableFuture<ResponseEntity<String>> asyncCreated() {
            return CompletableFuture.completedFuture(ResponseEntity.created("async-created"));
        }

        public CompletableFuture<ResponseEntity<String>> asyncName(@RequestParam("name") String name) {
            return CompletableFuture.completedFuture(ResponseEntity.ok("async:" + name));
        }
    }

    static class DirectBodyHandler {
        public ResponseEntity<Integer> bodySize(@RequestBody byte[] body) {
            return ResponseEntity.ok(body.length);
        }
    }

    static class DirectPrimitiveHandler {
        @DirectPathInt("id")
        public ResponseEntity<String> byPathInt(ByteBuffer out, int offset, int id) {
            return ResponseEntity.ok("path-int:" + id);
        }

        @DirectPathLong("id")
        public ResponseEntity<String> byPathLong(ByteBuffer out, int offset, long id) {
            return ResponseEntity.ok("path-long:" + id);
        }

        @DirectPathBoolean("active")
        public ResponseEntity<String> byPathBoolean(ByteBuffer out, int offset, boolean active) {
            return ResponseEntity.ok("path-bool:" + active);
        }

        @DirectPathDouble("amount")
        public ResponseEntity<String> byPathDouble(ByteBuffer out, int offset, double amount) {
            return ResponseEntity.ok("path-double:" + amount);
        }

        @DirectPathShort("code")
        public ResponseEntity<String> byPathShort(ByteBuffer out, int offset, short code) {
            return ResponseEntity.ok("path-short:" + code);
        }

        @DirectQueryLong("id")
        public ResponseEntity<String> byLong(ByteBuffer out, int offset, long id) {
            return ResponseEntity.ok("long:" + id);
        }

        @DirectQueryBoolean("active")
        public ResponseEntity<String> byBoolean(ByteBuffer out, int offset, boolean active) {
            return ResponseEntity.ok("bool:" + active);
        }

        @DirectQueryDouble("amount")
        public ResponseEntity<String> byDouble(ByteBuffer out, int offset, double amount) {
            return ResponseEntity.ok("double:" + amount);
        }

        @DirectQueryShort("code")
        public ResponseEntity<String> byShort(ByteBuffer out, int offset, short code) {
            return ResponseEntity.ok("short:" + code);
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

    static class SingleAnnotatedHandler {
        public ResponseEntity<String> byPath(@PathVariable("id") String id) {
            return ResponseEntity.ok("path:" + id);
        }

        public ResponseEntity<String> byQuery(@RequestParam("name") String name) {
            return ResponseEntity.ok("query:" + name);
        }

        public ResponseEntity<String> byHeader(@HeaderParam("X-Trace") String trace) {
            return ResponseEntity.ok("header:" + trace);
        }

        public ResponseEntity<String> byCookie(@CookieValue("city") String city) {
            return ResponseEntity.ok("cookie:" + city);
        }
    }

    static class TurkishRequestParamHandler {
        public ResponseEntity<String> combine(
                @PathVariable("city") String city,
                @PathVariable("slug") String slug,
                @RequestParam("name") String name,
                @RequestParam("note") String note
        ) {
            return ResponseEntity.ok(city + "|" + slug + "|" + name + "|" + note);
        }
    }

    static class TurkishCookieHandler {
        public ResponseEntity<String> cookie(@CookieValue("city") String city) {
            return ResponseEntity.ok(city);
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

    enum StatusFilter {
        ACTIVE,
        PASSIVE
    }

    static class EnumQueryHandler {
        public ResponseEntity<String> filter(@RequestParam("status") StatusFilter status) {
            return ResponseEntity.ok(status.name());
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

    record DirectCity(String city, int plate) {}

    enum DirectCityJsonWriter implements DirectJsonWriter<DirectCity> {
        INSTANCE;

        @Override
        public int write(DirectCity value, ByteBuffer out, int offset) {
            JsonBufferWriter json = JsonBufferWriter.reusable(out, offset);
            if (value == null) {
                return json.nullValue().result();
            }
            return json.beginObject()
                    .fieldString("city", value.city())
                    .comma()
                    .fieldInt("plate", value.plate())
                    .endObject()
                    .result();
        }
    }

    static class DirectJsonResponseHandler {
        public DirectJsonResponse<DirectCity> city() {
            return DirectJsonResponse.ok(new DirectCity("İstanbul", 34), DirectCityJsonWriter.INSTANCE)
                    .header("X-Direct", "1");
        }

        public ResponseEntity<DirectJsonResponse<DirectCity>> entityCity() {
            return ResponseEntity
                    .accepted(DirectJsonResponse.ok(new DirectCity("Ankara", 6), DirectCityJsonWriter.INSTANCE)
                            .header("X-Direct", "1"))
                    .header("X-Entity", "1");
        }
    }

    static class JsonProducerResponseHandler {
        private static final byte[] ITEM_PREFIX = "item-".getBytes(StandardCharsets.US_ASCII);

        public JsonProducerResponse city() {
            return JsonProducerResponse.ok((out, offset) -> JsonBufferWriter.reusable(out, offset)
                            .beginObject()
                            .fieldString("city", "İstanbul")
                            .comma()
                            .fieldInt("plate", 34)
                            .endObject()
                            .result())
                    .header("X-Producer", "1");
        }

        public ResponseEntity<JsonProducerResponse> entityCity() {
            return ResponseEntity
                    .accepted(JsonProducerResponse.ok((out, offset) -> JsonBufferWriter.reusable(out, offset)
                                    .beginObject()
                                    .fieldString("city", "Ankara")
                                    .comma()
                                    .fieldInt("plate", 6)
                                    .endObject()
                                    .result())
                            .header("X-Producer", "1"))
                    .header("X-Entity", "1");
        }

        @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
        public JsonProducerResponse directItems(int items) {
            return JsonProducerResponse.ok((out, offset) -> JsonBufferWriter.reusable(out, offset)
                    .beginObject()
                    .fieldInt("items", items)
                    .endObject()
                    .result());
        }

        public JsonBodyProducer bodyProducerCity() {
            return (out, offset) -> JsonBufferWriter.reusable(out, offset)
                    .beginObject()
                    .fieldString("city", "İstanbul")
                    .comma()
                    .fieldInt("plate", 34)
                    .endObject()
                    .result();
        }

        public ResponseEntity<JsonBodyProducer> entityBodyProducerCity() {
            JsonBodyProducer producer = (out, offset) -> JsonBufferWriter.reusable(out, offset)
                    .beginObject()
                    .fieldString("city", "Ankara")
                    .comma()
                    .fieldInt("plate", 6)
                    .endObject()
                    .result();
            return ResponseEntity
                    .accepted(producer)
                    .header("X-Entity", "1");
        }

        @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
        public JsonBodyProducer directProducerItems(int items) {
            return (out, offset) -> JsonBufferWriter.reusable(out, offset)
                    .beginObject()
                    .fieldInt("items", items)
                    .endObject()
                    .result();
        }

        @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
        public RawResponse directRawItems(int items) {
            return RawResponse.json(("{\"items\":" + items + "}").getBytes(StandardCharsets.UTF_8));
        }

        @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 20_000)
        public CompletableFuture<JsonBodyProducer> asyncDirectProducerItems(int items) {
            JsonBodyProducer producer = (out, offset) -> JsonBufferWriter.reusable(out, offset)
                    .beginObject()
                    .fieldInt("items", items)
                    .endObject()
                    .result();
            return CompletableFuture.completedFuture(producer);
        }

        @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 20_000)
        public CompletableFuture<JsonBodyProducer> asyncLargeDirectProducerItems(int items) {
            JsonBodyProducer producer = (out, offset) -> {
                JsonBufferWriter writer = JsonBufferWriter.reusable(out, offset);
                writer.beginObject().fieldName("rows").beginArray();
                for (int i = 0; i < items; i++) {
                    if (i > 0) {
                        writer.comma();
                    }
                    writer.beginObject()
                            .fieldInt("id", i)
                            .comma()
                            .fieldStringAsciiPrefixInt("name", ITEM_PREFIX, i)
                            .endObject();
                }
                return writer.endArray().endObject().result();
            };
            return CompletableFuture.completedFuture(producer);
        }
    }

    static class TurkishResponseHandler {
        public ResponseEntity<String> jsonText() {
            return ResponseEntity.ok("İstanbul şeker ölçü");
        }

        @ContentType("application/vnd.reactor+json")
        public ResponseEntity<String> vendorJsonText() {
            return ResponseEntity.ok("İstanbul şeker ölçü");
        }

        public RawResponse rawText() {
            return RawResponse.text("İstanbul şeker ölçü\n", "text/plain");
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
    void responseEntityAddsUtf8JsonContentTypeAndPreservesTurkishCharacters() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        TurkishResponseHandler handler = new TurkishResponseHandler();
        Method method = TurkishResponseHandler.class.getDeclaredMethod("jsonText");

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

        assertTrue(encodedHeaders.contains("Content-Type: application/json; charset=utf-8"));
        assertEquals("\"İstanbul şeker ölçü\"", encodedBody);
    }

    @Test
    void contentTypeAnnotationAddsUtf8CharsetForJsonLikeTypes() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        TurkishResponseHandler handler = new TurkishResponseHandler();
        Method method = TurkishResponseHandler.class.getDeclaredMethod("vendorJsonText");

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("Content-Type: application/vnd.reactor+json; charset=utf-8"));
    }

    @Test
    void rawTextResponseUsesUtf8BytesAndUtf8ContentType() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        TurkishResponseHandler handler = new TurkishResponseHandler();
        Method method = TurkishResponseHandler.class.getDeclaredMethod("rawText");

        int handlerId = registry.registerHandler(handler, method, Void.class, RawResponse.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("Content-Type: text/plain; charset=utf-8"));
        assertEquals("İstanbul şeker ölçü\n", encodedBody);
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
    void asyncResponseFrameUsesHeapBufferByDefaultForLowRss() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        ModernHandler handler = new ModernHandler();
        Method method = ModernHandler.class.getDeclaredMethod("asyncCreated");

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);

        HandlerRegistry.AsyncResponseFrame responseFrame = registry
                .invokeAsyncFrame(handlerId, new byte[0], "", "", "")
                .join();

        assertTrue(!responseFrame.buffer().isDirect());
        byte[] frameBytes = responseFrame.toByteArray();

        assertArrayEquals(FRAME_MAGIC, Arrays.copyOfRange(frameBytes, 0, 8));

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(201, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);
        assertEquals("\"async-created\"", encodedBody);
    }

    @Test
    void asyncAnnotatedParamsUseCompiledInvokerAndClearThreadLocalMaps() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        ModernHandler handler = new ModernHandler();
        Method method = ModernHandler.class.getDeclaredMethod("asyncName", String.class);

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);

        HandlerRegistry.AsyncResponseFrame responseFrame = registry
                .invokeAsyncFrame(handlerId, new byte[0], "", "name=Mustafa+Korkmaz", "")
                .join();

        byte[] frameBytes = responseFrame.toByteArray();
        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);
        assertEquals("\"async:Mustafa Korkmaz\"", encodedBody);
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
    void directQueryLongInvokesPrimitiveFastPath() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        DirectPrimitiveHandler handler = new DirectPrimitiveHandler();
        Method method = DirectPrimitiveHandler.class.getDeclaredMethod("byLong", ByteBuffer.class, int.class, long.class);

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBufferedQueryLong(handlerId, out, 0, 42L);

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);
        assertEquals("\"long:42\"", encodedBody);
    }

    @Test
    void directQueryBooleanInvokesPrimitiveFastPath() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        DirectPrimitiveHandler handler = new DirectPrimitiveHandler();
        Method method = DirectPrimitiveHandler.class.getDeclaredMethod("byBoolean", ByteBuffer.class, int.class, boolean.class);

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBufferedQueryBoolean(handlerId, out, 0, true);

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);
        assertEquals("\"bool:true\"", encodedBody);
    }

    @Test
    void directQueryDoubleAndShortInvokePrimitiveFastPath() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        DirectPrimitiveHandler handler = new DirectPrimitiveHandler();
        Method doubleMethod = DirectPrimitiveHandler.class.getDeclaredMethod("byDouble", ByteBuffer.class, int.class, double.class);
        Method shortMethod = DirectPrimitiveHandler.class.getDeclaredMethod("byShort", ByteBuffer.class, int.class, short.class);

        int doubleHandlerId = registry.registerHandler(handler, doubleMethod, Void.class, ResponseEntity.class);
        int shortHandlerId = registry.registerHandler(handler, shortMethod, Void.class, ResponseEntity.class);
        ByteBuffer doubleOut = ByteBuffer.allocate(1024);
        ByteBuffer shortOut = ByteBuffer.allocate(1024);

        int doubleWritten = registry.invokeBufferedQueryDouble(doubleHandlerId, doubleOut, 0, 42.5d);
        int shortWritten = registry.invokeBufferedQueryShort(shortHandlerId, shortOut, 0, (short) 7);

        assertEquals("\"double:42.5\"", frameBody(doubleOut, doubleWritten));
        assertEquals("\"short:7\"", frameBody(shortOut, shortWritten));
    }

    @Test
    void directPathIntUsesSamePrimitiveFastPathWithoutAnnotatedParams() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        DirectPrimitiveHandler handler = new DirectPrimitiveHandler();
        Method method = DirectPrimitiveHandler.class.getDeclaredMethod("byPathInt", ByteBuffer.class, int.class, int.class);

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBufferedQueryInt(handlerId, out, 0, 77);

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);
        assertEquals("\"path-int:77\"", encodedBody);
    }

    @Test
    void directPathLongAndBooleanUsePrimitiveFastPath() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        DirectPrimitiveHandler handler = new DirectPrimitiveHandler();
        Method longMethod = DirectPrimitiveHandler.class.getDeclaredMethod("byPathLong", ByteBuffer.class, int.class, long.class);
        Method booleanMethod = DirectPrimitiveHandler.class.getDeclaredMethod("byPathBoolean", ByteBuffer.class, int.class, boolean.class);

        int longHandlerId = registry.registerHandler(handler, longMethod, Void.class, ResponseEntity.class);
        int booleanHandlerId = registry.registerHandler(handler, booleanMethod, Void.class, ResponseEntity.class);
        ByteBuffer longOut = ByteBuffer.allocate(1024);
        ByteBuffer booleanOut = ByteBuffer.allocate(1024);

        int longWritten = registry.invokeBufferedQueryLong(longHandlerId, longOut, 0, 9001L);
        int booleanWritten = registry.invokeBufferedQueryBoolean(booleanHandlerId, booleanOut, 0, true);

        byte[] longFrameBytes = new byte[longWritten];
        longOut.position(0);
        longOut.get(longFrameBytes);
        ByteBuffer longFrame = ByteBuffer.wrap(longFrameBytes);
        longFrame.position(8);
        assertEquals(200, longFrame.getShort() & 0xFFFF);
        int longHeadersLen = longFrame.getInt();
        int longBodyLen = longFrame.getInt();
        String longBody = new String(longFrameBytes, 18 + longHeadersLen, longBodyLen, StandardCharsets.UTF_8);
        assertEquals("\"path-long:9001\"", longBody);

        byte[] booleanFrameBytes = new byte[booleanWritten];
        booleanOut.position(0);
        booleanOut.get(booleanFrameBytes);
        ByteBuffer booleanFrame = ByteBuffer.wrap(booleanFrameBytes);
        booleanFrame.position(8);
        assertEquals(200, booleanFrame.getShort() & 0xFFFF);
        int booleanHeadersLen = booleanFrame.getInt();
        int booleanBodyLen = booleanFrame.getInt();
        String booleanBody = new String(booleanFrameBytes, 18 + booleanHeadersLen, booleanBodyLen, StandardCharsets.UTF_8);
        assertEquals("\"path-bool:true\"", booleanBody);
    }

    @Test
    void directPathDoubleAndShortUsePrimitiveFastPath() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        DirectPrimitiveHandler handler = new DirectPrimitiveHandler();
        Method doubleMethod = DirectPrimitiveHandler.class.getDeclaredMethod("byPathDouble", ByteBuffer.class, int.class, double.class);
        Method shortMethod = DirectPrimitiveHandler.class.getDeclaredMethod("byPathShort", ByteBuffer.class, int.class, short.class);

        int doubleHandlerId = registry.registerHandler(handler, doubleMethod, Void.class, ResponseEntity.class);
        int shortHandlerId = registry.registerHandler(handler, shortMethod, Void.class, ResponseEntity.class);
        ByteBuffer doubleOut = ByteBuffer.allocate(1024);
        ByteBuffer shortOut = ByteBuffer.allocate(1024);

        int doubleWritten = registry.invokeBufferedQueryDouble(doubleHandlerId, doubleOut, 0, 11.25d);
        int shortWritten = registry.invokeBufferedQueryShort(shortHandlerId, shortOut, 0, (short) 9);

        assertEquals("\"path-double:11.25\"", frameBody(doubleOut, doubleWritten));
        assertEquals("\"path-short:9\"", frameBody(shortOut, shortWritten));
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
    void singleAnnotatedPathQueryHeaderAndCookieUseRawValueFastPathSemantics() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        SingleAnnotatedHandler handler = new SingleAnnotatedHandler();
        int pathHandlerId = registry.registerHandler(
                handler,
                SingleAnnotatedHandler.class.getDeclaredMethod("byPath", String.class),
                Void.class,
                ResponseEntity.class
        );
        int queryHandlerId = registry.registerHandler(
                handler,
                SingleAnnotatedHandler.class.getDeclaredMethod("byQuery", String.class),
                Void.class,
                ResponseEntity.class
        );
        int headerHandlerId = registry.registerHandler(
                handler,
                SingleAnnotatedHandler.class.getDeclaredMethod("byHeader", String.class),
                Void.class,
                ResponseEntity.class
        );
        int cookieHandlerId = registry.registerHandler(
                handler,
                SingleAnnotatedHandler.class.getDeclaredMethod("byCookie", String.class),
                Void.class,
                ResponseEntity.class
        );

        ByteBuffer pathOut = ByteBuffer.allocate(1024);
        ByteBuffer queryOut = ByteBuffer.allocate(1024);
        ByteBuffer headerOut = ByteBuffer.allocate(1024);
        ByteBuffer cookieOut = ByteBuffer.allocate(1024);

        int pathWritten = registry.invokeBuffered(
                pathHandlerId,
                pathOut,
                0,
                new byte[0],
                "unused=noise&id=42",
                "ignored=query",
                "Ignored: header\n"
        );
        int queryWritten = registry.invokeBuffered(
                queryHandlerId,
                queryOut,
                0,
                new byte[0],
                "ignored=path",
                "unused=noise&name=old&name=Mustafa+Korkmaz",
                "Ignored: header\n"
        );
        int headerWritten = registry.invokeBuffered(
                headerHandlerId,
                headerOut,
                0,
                new byte[0],
                "",
                "ignored=query",
                "Ignored: header\nx-trace: first\nX-Trace: abc-123\n"
        );
        int cookieWritten = registry.invokeBuffered(
                cookieHandlerId,
                cookieOut,
                0,
                new byte[0],
                "",
                "ignored=query",
                "Ignored: header\nCookie: city=old\nCookie: city=%C4%B0stanbul%20%C5%9Feker; session=abc\n"
        );

        assertEquals("\"path:42\"", frameBody(pathOut, pathWritten));
        assertEquals("\"query:Mustafa Korkmaz\"", frameBody(queryOut, queryWritten));
        assertEquals("\"header:abc-123\"", frameBody(headerOut, headerWritten));
        assertEquals("\"cookie:İstanbul şeker\"", frameBody(cookieOut, cookieWritten));
    }

    @Test
    void annotatedPathAndQueryParamsDecodeUtf8UrlComponents() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        TurkishRequestParamHandler handler = new TurkishRequestParamHandler();
        Method method = TurkishRequestParamHandler.class.getDeclaredMethod(
                "combine",
                String.class,
                String.class,
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
                "city=%C4%B0stanbul%20%C5%9Feker&slug=mustafa+korkmaz",
                "name=Mustafa+Korkmaz&note=%C3%B6l%C3%A7%C3%BC%20%C5%9Feker",
                ""
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

        assertEquals("\"İstanbul şeker|mustafa+korkmaz|Mustafa Korkmaz|ölçü şeker\"", encodedBody);
    }

    @Test
    void annotatedCookieValueDecodesUtf8UrlComponent() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        TurkishCookieHandler handler = new TurkishCookieHandler();
        Method method = TurkishCookieHandler.class.getDeclaredMethod("cookie", String.class);

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(
                handlerId,
                out,
                0,
                new byte[0],
                "",
                "",
                "Cookie: city=%C4%B0stanbul%20%C5%9Feker; session=abc\n"
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

        assertEquals("\"İstanbul şeker\"", encodedBody);
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
    void compiledAnnotatedInvokerSupportsEnumQueryParameters() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        EnumQueryHandler handler = new EnumQueryHandler();
        Method method = EnumQueryHandler.class.getDeclaredMethod("filter", StatusFilter.class);

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(
                handlerId,
                out,
                0,
                new byte[0],
                "",
                "status=active",
                ""
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
        assertEquals("\"ACTIVE\"", encodedBody);
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
        assertTrue(!encodedHeaders.contains("Content-Type: application/json"));
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
    void rawResponseCachesEncodedHeadersUntilHeadersChange() {
        RawResponse response = RawResponse.text("metric 1\n", "text/plain");

        byte[] first = response.getEncodedHeaders();
        byte[] second = response.getEncodedHeaders();
        assertTrue(first == second);

        response.header("X-Test", "1");
        byte[] third = response.getEncodedHeaders();
        assertTrue(first != third);

        String encodedHeaders = new String(third, StandardCharsets.UTF_8);
        assertTrue(encodedHeaders.contains("Content-Type: text/plain; charset=utf-8"));
        assertTrue(encodedHeaders.contains("X-Test: 1"));
    }

    @Test
    void directJsonResponseWritesFrameWithoutDslJsonSerialization() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        DirectJsonResponseHandler handler = new DirectJsonResponseHandler();
        Method method = DirectJsonResponseHandler.class.getDeclaredMethod("city");

        int handlerId = registry.registerHandler(handler, method, Void.class, DirectJsonResponse.class);
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

        assertTrue(encodedHeaders.contains("Content-Type: application/json; charset=utf-8"));
        assertTrue(encodedHeaders.contains("X-Direct: 1"));
        assertEquals("{\"city\":\"İstanbul\",\"plate\":34}", encodedBody);
    }

    @Test
    void directJsonResponseOverflowReturnsRequiredFrameSizeForNativeRetry() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        DirectJsonResponseHandler handler = new DirectJsonResponseHandler();
        Method method = DirectJsonResponseHandler.class.getDeclaredMethod("city");

        int handlerId = registry.registerHandler(handler, method, Void.class, DirectJsonResponse.class);
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

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);

        assertEquals("{\"city\":\"İstanbul\",\"plate\":34}", encodedBody);
    }

    @Test
    void directJsonResponseCachesEncodedHeadersUntilHeadersChange() {
        DirectJsonResponse<DirectCity> response =
                DirectJsonResponse.ok(new DirectCity("İstanbul", 34), DirectCityJsonWriter.INSTANCE)
                        .header("X-Direct", "1");

        byte[] first = response.getEncodedHeadersWithDefaultJson();
        byte[] second = response.getEncodedHeadersWithDefaultJson();
        assertTrue(first == second);

        String encodedHeaders = new String(first, StandardCharsets.UTF_8);
        assertTrue(encodedHeaders.contains("Content-Type: application/json; charset=utf-8"));
        assertTrue(encodedHeaders.contains("X-Direct: 1"));

        response.header("X-Trace", "abc");
        byte[] third = response.getEncodedHeadersWithDefaultJson();
        assertTrue(first != third);

        String changedHeaders = new String(third, StandardCharsets.UTF_8);
        assertTrue(changedHeaders.contains("X-Trace: abc"));
    }

    @Test
    void directJsonResponseSupportsLazyHeadersAndMutableGetHeaders() {
        DirectJsonResponse<DirectCity> response =
                DirectJsonResponse.ok(new DirectCity("İstanbul", 34), DirectCityJsonWriter.INSTANCE);

        byte[] first = response.getEncodedHeadersWithDefaultJson();
        byte[] second = response.getEncodedHeadersWithDefaultJson();
        assertTrue(first == second);
        assertEquals("Content-Type: application/json; charset=utf-8\n",
                new String(first, StandardCharsets.UTF_8));

        response.getHeaders().put("X-Lazy", "1");
        byte[] third = response.getEncodedHeadersWithDefaultJson();
        assertTrue(first != third);
        String changedHeaders = new String(third, StandardCharsets.UTF_8);
        assertTrue(changedHeaders.contains("Content-Type: application/json; charset=utf-8"));
        assertTrue(changedHeaders.contains("X-Lazy: 1"));
    }

    @Test
    void responseEntityCanWrapDirectJsonResponseAndMergeHeaders() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        DirectJsonResponseHandler handler = new DirectJsonResponseHandler();
        Method method = DirectJsonResponseHandler.class.getDeclaredMethod("entityCity");

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(202, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("Content-Type: application/json; charset=utf-8"));
        assertTrue(encodedHeaders.contains("X-Entity: 1"));
        assertTrue(encodedHeaders.contains("X-Direct: 1"));
        assertEquals("{\"city\":\"Ankara\",\"plate\":6}", encodedBody);
    }

    @Test
    void jsonProducerResponseWritesFrameWithoutDtoGraph() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        JsonProducerResponseHandler handler = new JsonProducerResponseHandler();
        Method method = JsonProducerResponseHandler.class.getDeclaredMethod("city");

        int handlerId = registry.registerHandler(handler, method, Void.class, JsonProducerResponse.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("Content-Type: application/json; charset=utf-8"));
        assertTrue(encodedHeaders.contains("X-Producer: 1"));
        assertEquals("{\"city\":\"İstanbul\",\"plate\":34}", encodedBody);
    }

    @Test
    void jsonBodyProducerWritesFrameWithoutResponseWrapper() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        JsonProducerResponseHandler handler = new JsonProducerResponseHandler();
        Method method = JsonProducerResponseHandler.class.getDeclaredMethod("bodyProducerCity");

        int handlerId = registry.registerHandler(handler, method, Void.class, JsonBodyProducer.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(200, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("Content-Type: application/json; charset=utf-8"));
        assertEquals("{\"city\":\"İstanbul\",\"plate\":34}", encodedBody);
    }

    @Test
    void responseEntityCanWrapJsonBodyProducerAndMergeHeaders() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        JsonProducerResponseHandler handler = new JsonProducerResponseHandler();
        Method method = JsonProducerResponseHandler.class.getDeclaredMethod("entityBodyProducerCity");

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(202, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("Content-Type: application/json; charset=utf-8"));
        assertTrue(encodedHeaders.contains("X-Entity: 1"));
        assertEquals("{\"city\":\"Ankara\",\"plate\":6}", encodedBody);
    }

    @Test
    void jsonProducerResponseSupportsLazyHeadersAndMutableGetHeaders() {
        JsonProducerResponse response =
                JsonProducerResponse.ok((out, offset) -> JsonBufferWriter.reusable(out, offset)
                        .beginObject()
                        .fieldString("status", "ok")
                        .endObject()
                        .result());

        byte[] first = response.getEncodedHeadersWithDefaultJson();
        byte[] second = response.getEncodedHeadersWithDefaultJson();
        assertTrue(first == second);
        assertEquals("Content-Type: application/json; charset=utf-8\n",
                new String(first, StandardCharsets.UTF_8));

        response.getHeaders().put("X-Lazy", "1");
        byte[] third = response.getEncodedHeadersWithDefaultJson();
        assertTrue(first != third);
        String changedHeaders = new String(third, StandardCharsets.UTF_8);
        assertTrue(changedHeaders.contains("Content-Type: application/json; charset=utf-8"));
        assertTrue(changedHeaders.contains("X-Lazy: 1"));
    }

    @Test
    void responseEntityCanWrapJsonProducerResponseAndMergeHeaders() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        JsonProducerResponseHandler handler = new JsonProducerResponseHandler();
        Method method = JsonProducerResponseHandler.class.getDeclaredMethod("entityCity");

        int handlerId = registry.registerHandler(handler, method, Void.class, ResponseEntity.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBuffered(handlerId, out, 0, new byte[0], "", "", "");

        byte[] frameBytes = new byte[written];
        out.position(0);
        out.get(frameBytes);

        ByteBuffer frame = ByteBuffer.wrap(frameBytes);
        frame.position(8);
        assertEquals(202, frame.getShort() & 0xFFFF);

        int headersLen = frame.getInt();
        int bodyLen = frame.getInt();
        String encodedHeaders = new String(frameBytes, 18, headersLen, StandardCharsets.UTF_8);
        String encodedBody = new String(frameBytes, 18 + headersLen, bodyLen, StandardCharsets.UTF_8);

        assertTrue(encodedHeaders.contains("Content-Type: application/json; charset=utf-8"));
        assertTrue(encodedHeaders.contains("X-Entity: 1"));
        assertTrue(encodedHeaders.contains("X-Producer: 1"));
        assertEquals("{\"city\":\"Ankara\",\"plate\":6}", encodedBody);
    }

    @Test
    void jsonProducerResponseSupportsDirectQueryIntScalarSignature() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        JsonProducerResponseHandler handler = new JsonProducerResponseHandler();
        Method method = JsonProducerResponseHandler.class.getDeclaredMethod("directItems", int.class);

        int handlerId = registry.registerHandler(handler, method, Void.class, JsonProducerResponse.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBufferedQueryInt(handlerId, out, 0, 42);

        assertEquals("{\"items\":42}", frameBody(out, written));
    }

    @Test
    void jsonBodyProducerSupportsDirectQueryIntScalarSignature() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        JsonProducerResponseHandler handler = new JsonProducerResponseHandler();
        Method method = JsonProducerResponseHandler.class.getDeclaredMethod("directProducerItems", int.class);

        int handlerId = registry.registerHandler(handler, method, Void.class, JsonBodyProducer.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBufferedQueryInt(handlerId, out, 0, 42);

        assertEquals("{\"items\":42}", frameBody(out, written));
    }

    @Test
    void rawResponseSupportsDirectQueryIntScalarSignature() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        JsonProducerResponseHandler handler = new JsonProducerResponseHandler();
        Method method = JsonProducerResponseHandler.class.getDeclaredMethod("directRawItems", int.class);

        int handlerId = registry.registerHandler(handler, method, Void.class, RawResponse.class);
        ByteBuffer out = ByteBuffer.allocate(1024);

        int written = registry.invokeBufferedQueryInt(handlerId, out, 0, 42);

        assertEquals("{\"items\":42}", frameBody(out, written));
    }

    @Test
    void asyncJsonBodyProducerSupportsDirectQueryIntScalarSignature() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        JsonProducerResponseHandler handler = new JsonProducerResponseHandler();
        Method method = JsonProducerResponseHandler.class.getDeclaredMethod("asyncDirectProducerItems", int.class);

        int handlerId = registry.registerHandler(handler, method, Void.class, JsonBodyProducer.class);

        HandlerRegistry.AsyncResponseFrame frame = registry.invokeAsyncFrameQueryInt(handlerId, 42).join();

        assertEquals("{\"items\":42}", frameBody(frame.buffer(), frame.length()));
    }

    @Test
    void asyncJsonBodyProducerRetriesWhenFrameExceedsInitialBuffer() throws Exception {
        HandlerRegistry registry = HandlerRegistry.getInstance();
        JsonProducerResponseHandler handler = new JsonProducerResponseHandler();
        Method method = JsonProducerResponseHandler.class.getDeclaredMethod("asyncLargeDirectProducerItems", int.class);

        int handlerId = registry.registerHandler(handler, method, Void.class, JsonBodyProducer.class);

        HandlerRegistry.AsyncResponseFrame frame = registry.invokeAsyncFrameQueryInt(handlerId, 10_000).join();
        String body = frameBody(frame.buffer(), frame.length());

        assertTrue(frame.length() > 64 * 1024);
        assertTrue(body.contains("\"name\":\"item-9999\""));
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
        assertTrue(!encodedHeaders.contains("Content-Type: application/json"));
        assertEquals("entity_metric 2\n", encodedBody);
    }
}

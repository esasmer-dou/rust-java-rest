package com.reactor.rust.json;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectJsonWriterRegistryTest {

    record City(String name, int plate) {
    }

    @AfterEach
    void clear() {
        DirectJsonWriterRegistry.clearForTests();
    }

    @Test
    void registeredWriterBypassesDslJsonPathAndWritesToTargetBuffer() {
        DirectJsonWriterRegistry.register(City.class, (value, out, offset) ->
                JsonBufferWriter.reusable(out, offset)
                        .beginObject()
                        .fieldString("name", value.name())
                        .comma()
                        .fieldInt("plate", value.plate())
                        .endObject()
                        .result()
        );

        ByteBuffer out = ByteBuffer.allocate(128);
        int written = DslJsonService.writeToBuffer(new City("İstanbul", 34), out, 0);

        byte[] bytes = new byte[written];
        out.position(0);
        out.get(bytes);

        assertEquals("{\"name\":\"İstanbul\",\"plate\":34}", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void providerLookupIsCachedForExactDtoClass() {
        CountingProvider provider = new CountingProvider();
        DirectJsonWriterRegistry.registerProvider(provider);

        ByteBuffer first = ByteBuffer.allocate(128);
        ByteBuffer second = ByteBuffer.allocate(128);

        DslJsonService.writeToBuffer(new City("Ankara", 6), first, 0);
        DslJsonService.writeToBuffer(new City("Ankara", 6), second, 0);

        assertEquals(1, provider.calls);
    }

    @Test
    void providerWriterIsInitializedOnlyByFirstLookup() {
        CountingProvider provider = new CountingProvider();
        DirectJsonWriterRegistry.registerProvider(provider);

        assertEquals(0, provider.calls);
        DirectJsonWriterRegistry.findWriter(City.class);
        assertEquals(1, provider.calls);
    }

    private static final class CountingProvider implements DirectJsonWriterProvider {
        private int calls;

        @Override
        public DirectJsonWriter<?> findWriter(Class<?> type) {
            calls++;
            if (type == City.class) {
                return (DirectJsonWriter<City>) (value, out, offset) ->
                        JsonBufferWriter.reusable(out, offset)
                                .beginObject()
                                .fieldString("name", value.name())
                                .comma()
                                .fieldInt("plate", value.plate())
                                .endObject()
                                .result();
            }
            return null;
        }
    }
}

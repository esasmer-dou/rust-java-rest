package com.reactor.rust.example.handler;

import com.reactor.rust.json.JsonBufferWriter;

import java.nio.ByteBuffer;

/**
 * Object-graph-free heavy response producer.
 *
 * <p>This is the production shape for heavy dynamic JSON: Java business logic decides scalar inputs,
 * then writes JSON directly into the native response buffer.</p>
 */
final class HeavyResponseDirectWriter {

    private HeavyResponseDirectWriter() {}

    static int write(ByteBuffer out, int offset, int itemCount, long timestamp, long nanosBase) {
        JsonBufferWriter json = JsonBufferWriter.wrap(out, offset);
        json.beginObject()
                .fieldName("requestId").beginString().stringAsciiFragment("HEAVY-")
                .stringLongFragment(timestamp).endString().comma()
                .fieldName("message").beginString().stringAsciiFragment("Heavy payload response with ")
                .stringIntFragment(itemCount).stringAsciiFragment(" items").endString().comma()
                .fieldInt("itemCount", itemCount).comma()
                .fieldLong("timestamp", timestamp).comma()
                .fieldName("items").beginArray();

        for (int i = 0; i < itemCount; i++) {
            if (i > 0) {
                json.comma();
            }
            json.beginObject()
                    .fieldName("id").beginString().stringAsciiFragment("ITEM-")
                    .stringIntFragment(i).stringAsciiFragment("-").stringLongFragment(nanosBase + i)
                    .endString().comma()
                    .fieldName("description").beginString()
                    .stringAsciiFragment("Detailed description for item number ")
                    .stringIntFragment(i)
                    .stringAsciiFragment(" with some additional text to increase payload size")
                    .endString().comma()
                    .fieldFixed2Cents("price", 9_999L + i).comma()
                    .fieldBoolean("available", i % 5 == 0).comma()
                    .fieldName("metadata").beginObject()
                    .fieldName("category").beginString().stringAsciiFragment("category-")
                    .stringIntFragment(i % 10).endString().comma()
                    .fieldName("warehouse").beginString().stringAsciiFragment("warehouse-")
                    .stringIntFragment(i % 3).endString().comma()
                    .fieldLong("timestamp", timestamp)
                    .endObject()
                    .endObject();
        }

        json.endArray().endObject();
        return json.result();
    }
}

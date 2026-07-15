package com.reactor.rust.example.handler;

import com.reactor.rust.json.JsonBufferWriter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Object-graph-free heavy response producer.
 *
 * <p>This is the production shape for heavy dynamic JSON: Java business logic decides scalar inputs,
 * then writes JSON directly into the native response buffer.</p>
 */
final class HeavyResponseDirectWriter {

    private static final byte[] HEADER_REQUEST_ID = ascii("{\"requestId\":\"HEAVY-");
    private static final byte[] MESSAGE_PREFIX = ascii("\",\"message\":\"Heavy payload response with ");
    private static final byte[] MESSAGE_SUFFIX_ITEM_COUNT = ascii(" items\",\"itemCount\":");
    private static final byte[] TIMESTAMP_AND_ITEMS_PREFIX = ascii(",\"timestamp\":");
    private static final byte[] ITEMS_PREFIX = ascii(",\"items\":[");
    private static final byte[] ITEM_ID_PREFIX = ascii("{\"id\":\"ITEM-");
    private static final byte[] DESCRIPTION_PREFIX = ascii("\",\"description\":\"Detailed description for item number ");
    private static final byte[] DESCRIPTION_SUFFIX = ascii(" with some additional text to increase payload size\",\"price\":");
    private static final byte[] AVAILABLE_PREFIX = ascii(",\"available\":");
    private static final byte[] METADATA_CATEGORY_PREFIX = ascii(",\"metadata\":{\"category\":\"category-");
    private static final byte[] WAREHOUSE_PREFIX = ascii("\",\"warehouse\":\"warehouse-");
    private static final byte[] METADATA_TIMESTAMP_PREFIX = ascii("\",\"timestamp\":");
    private static final byte[] ITEM_SUFFIX = ascii("}}");
    private static final byte[] ARRAY_OBJECT_SUFFIX = ascii("]}");

    private HeavyResponseDirectWriter() {}

    static int write(ByteBuffer out, int offset, int itemCount, long timestamp, long nanosBase) {
        JsonBufferWriter json = JsonBufferWriter.reusable(out, offset);
        json.rawAscii(HEADER_REQUEST_ID)
                .stringLongFragment(timestamp)
                .rawAscii(MESSAGE_PREFIX)
                .stringIntFragment(itemCount)
                .rawAscii(MESSAGE_SUFFIX_ITEM_COUNT)
                .stringIntFragment(itemCount)
                .rawAscii(TIMESTAMP_AND_ITEMS_PREFIX)
                .stringLongFragment(timestamp)
                .rawAscii(ITEMS_PREFIX);

        for (int i = 0; i < itemCount; i++) {
            if (i > 0) {
                json.comma();
            }
            json.rawAscii(ITEM_ID_PREFIX)
                    .stringIntFragment(i)
                    .stringAsciiFragment("-")
                    .stringLongFragment(nanosBase + i)
                    .rawAscii(DESCRIPTION_PREFIX)
                    .stringIntFragment(i)
                    .rawAscii(DESCRIPTION_SUFFIX)
                    .fixed2Cents(9_999L + i)
                    .rawAscii(AVAILABLE_PREFIX)
                    .bool(i % 5 == 0)
                    .rawAscii(METADATA_CATEGORY_PREFIX)
                    .stringIntFragment(i % 10)
                    .rawAscii(WAREHOUSE_PREFIX)
                    .stringIntFragment(i % 3)
                    .rawAscii(METADATA_TIMESTAMP_PREFIX)
                    .stringLongFragment(timestamp)
                    .rawAscii(ITEM_SUFFIX);
        }

        json.rawAscii(ARRAY_OBJECT_SUFFIX);
        return json.result();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}

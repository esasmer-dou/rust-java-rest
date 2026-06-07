package com.reactor.rust.example.handler;

import com.reactor.rust.example.handler.BenchmarkHandler.BenchmarkItem;
import com.reactor.rust.example.handler.BenchmarkHandler.BenchmarkOrderRequest;
import com.reactor.rust.json.DirectJsonWriter;
import com.reactor.rust.json.JsonBufferWriter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Generated-style direct writer prototype for a selected DTO.
 *
 * <p>This intentionally mirrors what an annotation processor should generate: no reflection,
 * no Object[] dispatch and no serializer-owned byte[] copy.</p>
 */
final class BenchmarkOrderRequestJsonWriter implements DirectJsonWriter<BenchmarkOrderRequest> {

    static final BenchmarkOrderRequestJsonWriter INSTANCE = new BenchmarkOrderRequestJsonWriter();
    private static final byte[] TEST_PREFIX = "test".getBytes(StandardCharsets.US_ASCII);

    private BenchmarkOrderRequestJsonWriter() {}

    public int writeSampleOrder(ByteBuffer out, int offset) {
        JsonBufferWriter json = JsonBufferWriter.reusable(out, offset);
        json.beginObject()
                .fieldString("orderId", "ORD-1001").comma()
                .fieldFixed2Cents("amount", 35075).comma()
                .fieldBoolean("paid", true).comma()
                .fieldName("address")
                .beginObject()
                .fieldString("city", "Ankara").comma()
                .fieldString("street", "Ataturk Cd.")
                .endObject().comma()
                .fieldName("customer")
                .beginObject()
                .fieldString("name", "mustafa customer a.ş").comma()
                .fieldString("email", "mustafa@gmai.com")
                .endObject().comma()
                .fieldName("items")
                .beginArray();

        for (int i = 0; i < 19; i++) {
            if (i > 0) {
                json.comma();
            }
            json.beginObject()
                    .fieldStringAsciiPrefixInt("name", TEST_PREFIX, i).comma()
                    .fieldFixed2Cents("price", Math.round((12.89d + i) * 100.0d))
                    .endObject();
        }

        json.endArray().endObject();
        return json.result();
    }

    @Override
    public int write(BenchmarkOrderRequest value, ByteBuffer out, int offset) {
        JsonBufferWriter json = JsonBufferWriter.reusable(out, offset);
        if (value == null) {
            return json.nullValue().result();
        }

        json.beginObject()
                .fieldString("orderId", value.orderId()).comma()
                .fieldFixed2Cents("amount", Math.round(value.amount() * 100.0d)).comma()
                .fieldBoolean("paid", value.paid()).comma()
                .fieldName("address");
        if (value.address() == null) {
            json.nullValue();
        } else {
            json.beginObject()
                    .fieldString("city", value.address().city()).comma()
                    .fieldString("street", value.address().street())
                    .endObject();
        }

        List<BenchmarkItem> items = value.items();
        json.comma().fieldName("customer");
        if (value.customer() == null) {
            json.nullValue();
        } else {
            json.beginObject()
                    .fieldString("name", value.customer().name()).comma()
                    .fieldString("email", value.customer().email())
                    .endObject();
        }

        json.comma().fieldName("items");
        if (items == null) {
            json.nullValue();
        } else {
            json.beginArray();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    json.comma();
                }
                BenchmarkItem item = items.get(i);
                if (item == null) {
                    json.nullValue();
                } else {
                    json.beginObject()
                            .fieldString("name", item.name()).comma()
                            .fieldFixed2Cents("price", Math.round(item.price() * 100.0d))
                            .endObject();
                }
            }
            json.endArray();
        }

        json.endObject();
        return json.result();
    }
}

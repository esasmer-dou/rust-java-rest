package com.reactor.examples.streaming;

import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.RequestParam;
import com.reactor.rust.http.FileResponse;
import com.reactor.rust.http.JsonProducerResponse;
import com.reactor.rust.json.JsonBufferWriter;

import java.nio.file.Path;

public final class StreamingHandler {

    private static final byte[] ORDER_PREFIX = "ORD-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private final FileResponse export;

    public StreamingHandler(Path exportFile) {
        this.export = FileResponse.download(exportFile, "orders.csv", "text/csv");
    }

    @GetMapping(value = "/api/v1/orders/export", responseType = FileResponse.class)
    public FileResponse export() {
        return export;
    }

    @GetMapping(value = "/api/v1/orders/live", responseType = JsonProducerResponse.class)
    public JsonProducerResponse liveOrders(
            @RequestParam(value = "count", defaultValue = "100") int count) {
        int bounded = Math.max(1, Math.min(count, 10_000));
        return JsonProducerResponse.ok((out, offset) -> {
            JsonBufferWriter json = JsonBufferWriter.reusable(out, offset).beginArray();
            for (int index = 0; index < bounded; index++) {
                if (index > 0) {
                    json.comma();
                }
                json.beginObject()
                        .fieldStringAsciiPrefixInt("orderId", ORDER_PREFIX, index + 1)
                        .comma()
                        .fieldLong("amountCents", 10_000L + index)
                        .comma()
                        .fieldString("status", "READY")
                        .endObject();
            }
            return json.endArray().result();
        });
    }
}

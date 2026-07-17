package com.reactor.examples.benchmark;

import com.reactor.rust.annotations.DirectQueryInt;
import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.NativeStaticRoute;
import com.reactor.rust.annotations.RouteWorkload;
import com.reactor.rust.http.JsonProducerResponse;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.json.JsonBufferWriter;

import java.nio.charset.StandardCharsets;

public final class BenchmarkHandler {

    private static final byte[] STATIC_BODY =
            "{\"id\":1,\"status\":\"READY\",\"active\":true}"
                    .getBytes(StandardCharsets.UTF_8);

    @GetMapping(value = "/bench/native-static", responseType = RawResponse.class)
    @NativeStaticRoute
    @RouteWorkload(RouteWorkload.Type.RAW_STATIC)
    public RawResponse nativeStatic() {
        return RawResponse.registeredJson(STATIC_BODY);
    }

    @GetMapping(value = "/bench/direct-record", responseType = BenchmarkResponse.class)
    @RouteWorkload(RouteWorkload.Type.SMALL_JSON)
    public BenchmarkResponse directRecord() {
        return new BenchmarkResponse(1, "READY", true);
    }

    @GetMapping(value = "/bench/producer", responseType = JsonProducerResponse.class)
    @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 10_000)
    @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "benchmark-producer")
    public JsonProducerResponse producer(int items) {
        return JsonProducerResponse.ok((out, offset) -> {
            JsonBufferWriter json = JsonBufferWriter.reusable(out, offset).beginArray();
            for (int index = 0; index < items; index++) {
                if (index > 0) {
                    json.comma();
                }
                json.beginObject()
                        .fieldInt("id", index + 1)
                        .comma()
                        .fieldString("status", "READY")
                        .endObject();
            }
            return json.endArray().result();
        });
    }
}

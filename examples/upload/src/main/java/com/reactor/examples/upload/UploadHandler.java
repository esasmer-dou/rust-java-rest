package com.reactor.examples.upload;

import com.reactor.rust.annotations.HeaderParam;
import com.reactor.rust.annotations.MaxRequestBodySize;
import com.reactor.rust.annotations.PostMapping;
import com.reactor.rust.annotations.RequestBody;
import com.reactor.rust.http.JsonProducerResponse;
import com.reactor.rust.json.JsonBufferWriter;
import com.reactor.rust.multipart.MultipartFile;
import com.reactor.rust.multipart.MultipartParser;

public final class UploadHandler {

    @PostMapping(value = "/api/v1/files", responseType = JsonProducerResponse.class)
    @MaxRequestBodySize(8 * 1024 * 1024)
    public JsonProducerResponse upload(
            @HeaderParam("Content-Type") String contentType,
            @RequestBody byte[] body) {
        MultipartFile file = MultipartParser.parse(body, contentType).values().stream()
                .filter(MultipartFile.class::isInstance)
                .map(MultipartFile.class::cast)
                .findFirst()
                .orElse(null);
        if (file == null) {
            return JsonProducerResponse.status(400, (out, offset) -> JsonBufferWriter.reusable(out, offset)
                    .beginObject()
                    .fieldString("error", "file part is required")
                    .endObject()
                    .result());
        }
        return JsonProducerResponse.ok((out, offset) -> JsonBufferWriter.reusable(out, offset)
                .beginObject()
                .fieldString("field", file.getName())
                .comma()
                .fieldString("fileName", file.getOriginalFilename())
                .comma()
                .fieldString("contentType", file.getContentType())
                .comma()
                .fieldLong("size", file.getSize())
                .endObject()
                .result());
    }
}

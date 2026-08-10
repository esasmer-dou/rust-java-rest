package com.reactor.rust.openapi;

import com.reactor.rust.http.RawResponse;

import java.io.IOException;
import java.io.InputStream;

/** Access to the build-time generated OpenAPI document without runtime route scanning. */
public final class OpenApiDocument {

    private static final String RESOURCE = "META-INF/reactor/openapi.json";

    private OpenApiDocument() {}

    public static RawResponse response() {
        return Holder.RESPONSE;
    }

    public static byte[] bytes() {
        return Holder.BYTES.clone();
    }

    private static byte[] load() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = OpenApiDocument.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Generated OpenAPI document is missing; enable the rust-java-rest codegen processor");
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read generated OpenAPI document", failure);
        }
    }

    private static final class Holder {
        private static final byte[] BYTES = load();
        private static final RawResponse RESPONSE = RawResponse.json(BYTES);
    }
}

package com.reactor.examples.streaming;

import com.reactor.rust.app.RestApplication;
import com.reactor.rust.config.PropertiesLoader;

import java.nio.file.Path;

public final class StreamingApplication {

    private StreamingApplication() {}

    public static void main(String[] args) {
        RestApplication.run(context -> context.handlers(new StreamingHandler(
                Path.of(PropertiesLoader.get("sample.export-file", "README.md")))));
    }
}

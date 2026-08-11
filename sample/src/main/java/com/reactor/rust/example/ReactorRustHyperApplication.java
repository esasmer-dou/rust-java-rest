package com.reactor.rust.example;

import com.reactor.rust.annotations.ReactorApplication;
import com.reactor.rust.app.RestApplication;

@ReactorApplication(
        name = "Rust-Java REST Sample",
        version = "1.0.0",
        description = "Copy-paste REST examples with generated startup metadata",
        scanBasePackages = "com.reactor.rust.example",
        standardRuntime = true,
        metrics = true)
public final class ReactorRustHyperApplication {

    private ReactorRustHyperApplication() {}

    public static void main(String[] args) {
        RestApplication.run(ReactorRustHyperApplication.class, args);
    }
}

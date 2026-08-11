package com.reactor.examples.benchmark;

import com.reactor.rust.annotations.ReactorApplication;
import com.reactor.rust.app.RestApplication;

@ReactorApplication(
        name = "Rust-Java REST Benchmark",
        description = "Generated route and response writer benchmark endpoints",
        scanBasePackages = "com.reactor.examples.benchmark")
public final class BenchmarkApplication {

    private BenchmarkApplication() {}

    public static void main(String[] args) {
        RestApplication.run(BenchmarkApplication.class, args);
    }
}

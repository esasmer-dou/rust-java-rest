package com.reactor.examples.benchmark;

import com.reactor.rust.app.RestApplication;

public final class BenchmarkApplication {

    private BenchmarkApplication() {}

    public static void main(String[] args) {
        RestApplication.run(context -> context.handlers(new BenchmarkHandler()));
    }
}

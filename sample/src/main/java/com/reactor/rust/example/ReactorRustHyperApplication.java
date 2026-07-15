package com.reactor.rust.example;

import com.reactor.rust.app.RestApplication;
import com.reactor.rust.example.handler.BenchmarkHandler;
import com.reactor.rust.example.handler.FeatureHandler;
import com.reactor.rust.example.handler.FileUploadHandler;
import com.reactor.rust.example.handler.OrderHandler;
import com.reactor.rust.example.handler.UserHandler;
import com.reactor.rust.metrics.MetricsHandler;

public final class ReactorRustHyperApplication {

    private ReactorRustHyperApplication() {}

    public static void main(String[] args) {
        RestApplication.builder()
                .scan("com.reactor.rust.example")
                .handlers(
                        OrderHandler.class,
                        BenchmarkHandler.class,
                        UserHandler.class,
                        FeatureHandler.class,
                        FileUploadHandler.class)
                .handlerInstances(new MetricsHandler())
                .shutdownThreadName("rust-hyper-shutdown")
                .standardRuntimeFeatures()
                .start();
    }
}

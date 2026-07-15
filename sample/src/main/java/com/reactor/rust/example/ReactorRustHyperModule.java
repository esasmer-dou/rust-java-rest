package com.reactor.rust.example;

import com.reactor.rust.app.RestApplication;
import com.reactor.rust.example.handler.BenchmarkHandler;
import com.reactor.rust.example.handler.FeatureHandler;
import com.reactor.rust.example.handler.FileUploadHandler;
import com.reactor.rust.example.handler.OrderHandler;
import com.reactor.rust.example.handler.UserHandler;
import com.reactor.rust.metrics.MetricsHandler;

public final class ReactorRustHyperModule implements RestApplication.Module {

    public static final ReactorRustHyperModule INSTANCE = new ReactorRustHyperModule();

    private ReactorRustHyperModule() {}

    @Override
    public void configure(RestApplication.ModuleContext context) {
        context.scan("com.reactor.rust.example")
                .handlerTypes(
                        OrderHandler.class,
                        BenchmarkHandler.class,
                        UserHandler.class,
                        FeatureHandler.class,
                        FileUploadHandler.class)
                .handlers(new MetricsHandler());
    }
}

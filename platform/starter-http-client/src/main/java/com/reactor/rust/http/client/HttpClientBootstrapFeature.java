package com.reactor.rust.http.client;

import com.reactor.rust.app.ApplicationBootstrapFeature;
import com.reactor.rust.app.ApplicationBootstrapFeatureContext;

/** Installs one bounded HTTP runtime before generated client beans are constructed. */
public final class HttpClientBootstrapFeature implements ApplicationBootstrapFeature {
    @Override
    public void configure(ApplicationBootstrapFeatureContext context) {
        if (context.beans().hasBean(ReactorHttpClientRuntime.class)) return;
        context.beans().registerGeneratedOnDemandFactory(
                ReactorHttpClientRuntime.class,
                () -> {
                    ReactorHttpClientRuntime runtime = ReactorHttpClientRuntime.fromProperties();
                    context.manage(runtime);
                    return runtime;
                },
                "reactorHttpClientRuntime",
                true);
    }
}

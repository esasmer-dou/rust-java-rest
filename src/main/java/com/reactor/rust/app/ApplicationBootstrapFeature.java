package com.reactor.rust.app;

/** Starter SPI executed before generated application beans are constructed. */
public interface ApplicationBootstrapFeature {
    default int order() {
        return 100;
    }

    void configure(ApplicationBootstrapFeatureContext context);
}

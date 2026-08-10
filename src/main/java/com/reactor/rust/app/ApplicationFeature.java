package com.reactor.rust.app;

/** Starter SPI executed once during startup, before routes are frozen. */
public interface ApplicationFeature {
    default int order() {
        return 100;
    }

    void configure(ApplicationFeatureContext context);
}

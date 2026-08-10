package com.reactor.rust.scheduler;

import com.reactor.rust.app.ApplicationBootstrapFeature;
import com.reactor.rust.app.ApplicationBootstrapFeatureContext;

/** Installs an application-owned task registry before generated components are registered. */
public final class SchedulerBootstrapFeature implements ApplicationBootstrapFeature {
    @Override
    public void configure(ApplicationBootstrapFeatureContext context) {
        if (context.beans().hasBean(ScheduledTaskRegistry.class)) return;
        context.beans().registerGeneratedBean(
                ScheduledTaskRegistry.class,
                new ScheduledTaskRegistry(),
                "reactorScheduledTaskRegistry",
                true);
    }
}

package com.reactor.rust.startup;

import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.json.DirectJsonWriterRegistry;
import com.reactor.rust.json.DslJsonService;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.metrics.Metrics;

import java.util.HashSet;
import java.util.Set;

/**
 * Readiness prewarmer for deployments that prefer first-request stability over minimum startup work.
 */
public final class StartupPrewarmer {

    private StartupPrewarmer() {
    }

    public static void prewarmIfEnabled() {
        if (!PropertiesLoader.getBoolean("reactor.startup.prewarm.enabled", false)) {
            return;
        }
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("startup.prewarm")) {
            int handlers = 0;
            int responseTypes = 0;
            try (StartupTimeline.Scope ignoredHandlers = StartupTimeline.phase("startup.prewarm.handlers")) {
                handlers = HandlerRegistry.getInstance().descriptorsSnapshot().size();
            }
            if (PropertiesLoader.getBoolean("reactor.startup.prewarm.json", true)) {
                try (StartupTimeline.Scope ignoredJson = StartupTimeline.phase("startup.prewarm.json")) {
                    DslJsonService.warmup();
                    responseTypes = prewarmDirectWriters();
                }
            }
            Metrics.getInstance().setGauge("reactor.startup.prewarm.handlers", handlers);
            Metrics.getInstance().setGauge("reactor.startup.prewarm.response_types", responseTypes);
            FrameworkLogger.info("[startup] Prewarm completed: handlers=" + handlers
                    + " responseTypes=" + responseTypes);
        }
    }

    private static int prewarmDirectWriters() {
        Set<Class<?>> responseTypes = new HashSet<>();
        for (HandlerRegistry.HandlerDescriptor descriptor : HandlerRegistry.getInstance().descriptorsSnapshot()) {
            if (descriptor.responseType != null
                    && descriptor.responseType != Void.class
                    && descriptor.responseType != void.class) {
                responseTypes.add(descriptor.responseType);
            }
        }
        for (Class<?> type : responseTypes) {
            DirectJsonWriterRegistry.findWriter(type);
        }
        return responseTypes.size();
    }
}

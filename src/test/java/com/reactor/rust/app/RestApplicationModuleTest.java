package com.reactor.rust.app;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestApplicationModuleTest {

    @Test
    void closesModuleResourcesWhenConfigurationFails() {
        AtomicBoolean closed = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () -> RestApplication.builder()
                .loadProperties(false)
                .applyRuntimeProfiles(false)
                .module(context -> {
                    context.manage(() -> closed.set(true));
                    throw new IllegalStateException("configuration failed");
                })
                .startAsync());

        assertTrue(closed.get());
    }

    @Test
    void builderCannotBeRestartedAfterStartupFailure() {
        RestApplication.Builder builder = RestApplication.builder()
                .loadProperties(false)
                .applyRuntimeProfiles(false)
                .module(context -> {
                    throw new IllegalStateException("configuration failed");
                });

        assertThrows(IllegalStateException.class, builder::startAsync);
        assertThrows(IllegalStateException.class, builder::startAsync);
    }

    @Test
    void simpleLauncherKeepsModuleResourceOwnership() {
        AtomicBoolean closed = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () -> RestApplication.startAsync(context -> {
            context.manage(() -> closed.set(true));
            throw new IllegalStateException("configuration failed");
        }));

        assertTrue(closed.get());
    }

    @Test
    void simpleLauncherRequiresAnExplicitModule() {
        assertThrows(IllegalArgumentException.class, RestApplication::startAsync);
    }
}

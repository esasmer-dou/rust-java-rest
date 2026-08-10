package com.reactor.rust.app;

/** Minimal, fail-closed entry point for build-time generated applications. */
public final class Reactor {

    private Reactor() {
    }

    public static void run(Class<?> applicationType, String... args) {
        RestApplication.run(applicationType, args);
    }

    public static RestApplication.RunningApplication start(
            Class<?> applicationType,
            String... args) {
        return RestApplication.startAsync(applicationType, args);
    }
}

package com.reactor.rust.example;

public final class ApplicationVersion {

    private static final String DEVELOPMENT_VERSION = "development";

    private ApplicationVersion() {
    }

    public static String current() {
        String version = ApplicationVersion.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? DEVELOPMENT_VERSION : version;
    }
}

package com.reactor.rust.startup;

import com.reactor.rust.config.PropertiesLoader;

import java.util.Locale;

/** Controls whether startup may use compatibility reflection paths. */
public enum StartupMode {
    AOT,
    COMPATIBILITY;

    private static volatile StartupMode active = COMPATIBILITY;

    public static StartupMode active() {
        return active;
    }

    public static boolean isAot() {
        return active == AOT;
    }

    public static void configure(boolean aotDefault) {
        String configured = PropertiesLoader.get(
                "reactor.startup.mode",
                aotDefault ? "aot" : "compatibility");
        active = parse(configured);
    }

    public static StartupMode parse(String value) {
        if (value == null || value.isBlank()) {
            return AOT;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "aot", "strict", "production" -> AOT;
            case "compat", "compatibility", "reflection" -> COMPATIBILITY;
            default -> throw new IllegalArgumentException(
                    "Unsupported reactor.startup.mode='" + value
                            + "'; expected aot or compatibility");
        };
    }

    public static void requireDescriptor(String basePackage) {
        if (isAot() && !ApplicationDescriptors.hasApplicationDescriptor(basePackage)) {
            throw new IllegalStateException(
                    "AOT startup requires generated application metadata for package " + basePackage
                            + ". Add the rust-java-rest codegen processor or use "
                            + "-Dreactor.startup.mode=compatibility with the compatibility runtime.");
        }
    }
}

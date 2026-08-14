package com.reactor.rust.telemetry;

/** Bounded telemetry surfaces that can be switched at runtime. */
public enum TelemetryProfile {
    MICRO("micro"),
    JVM("jvm"),
    SQL("sql"),
    FULL("full"),
    DIAGNOSTIC("diagnostic");

    private final String propertyValue;

    TelemetryProfile(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    public String propertyValue() {
        return propertyValue;
    }

    static TelemetryProfile fromPropertyValue(String value) {
        for (TelemetryProfile profile : values()) {
            if (profile.propertyValue.equals(value)) return profile;
        }
        throw new IllegalStateException("Unknown native telemetry profile: " + value);
    }
}

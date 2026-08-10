package com.reactor.rust.bridge;

/** Build-time primitive request binding consumed directly by the Rust HTTP plane. */
public record GeneratedPrimitiveBinding(
        Source source,
        Kind kind,
        String name,
        String defaultValue,
        Mode mode) {

    public enum Source { QUERY, PATH }

    public enum Kind { INT, LONG, BOOLEAN, DOUBLE, SHORT }

    public enum Mode {
        LEGACY(0),
        STRICT_DEFAULT(1),
        STRICT_REQUIRED(2);

        private final int nativeValue;

        Mode(int nativeValue) {
            this.nativeValue = nativeValue;
        }

        public int nativeValue() {
            return nativeValue;
        }
    }

    public GeneratedPrimitiveBinding {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Generated primitive binding name must not be blank");
        }
        defaultValue = defaultValue == null ? "" : defaultValue;
    }
}

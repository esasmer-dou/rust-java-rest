package com.reactor.rust.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

final class NativeProvenance {

    private static final String MANIFEST_RESOURCE = "native/native-provenance.properties";

    private NativeProvenance() {}

    static Manifest verifyPackagedBinary(
            ClassLoader classLoader,
            String platform,
            byte[] binary,
            int expectedRestAbi) {
        Properties properties = loadManifest(classLoader);
        requireEquals(properties, "schema", "2");
        requireEquals(properties, "rest.abi", Integer.toString(expectedRestAbi));
        requireEquals(
                properties,
                "dubbo.abi",
                Integer.toString(NativeBridge.EXPECTED_DUBBO_NATIVE_ABI_VERSION)
        );
        requireEquals(
                properties,
                "redis.abi",
                Integer.toString(NativeBridge.EXPECTED_REDIS_NATIVE_ABI_VERSION)
        );

        String expectedHash = required(properties, platform + ".sha256");
        String actualHash = sha256(binary);
        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            throw new IllegalStateException(
                    "Packaged native binary hash mismatch for " + platform
                            + ": expected " + expectedHash + " but found " + actualHash
            );
        }

        return new Manifest(
                required(properties, "source.revision"),
                required(properties, "crate.version"),
                parsePositiveInt(properties, "dubbo.abi"),
                parsePositiveInt(properties, "redis.abi"),
                actualHash
        );
    }

    static BuildInfo parseBuildInfo(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Loaded native binary did not report build provenance");
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(value));
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot parse native build provenance", impossible);
        }
        requireEquals(properties, "schema", "2");
        return new BuildInfo(
                required(properties, "crate"),
                required(properties, "crateVersion"),
                required(properties, "sourceRevision"),
                required(properties, "target"),
                required(properties, "profile"),
                required(properties, "features"),
                parsePositiveInt(properties, "restAbi"),
                parsePositiveInt(properties, "dubboAbi"),
                parsePositiveInt(properties, "redisAbi")
        );
    }

    private static Properties loadManifest(ClassLoader classLoader) {
        try (InputStream input = classLoader.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Packaged native provenance manifest is missing: " + MANIFEST_RESOURCE
                );
            }
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read packaged native provenance manifest", error);
        }
    }

    private static int parsePositiveInt(Properties properties, String key) {
        String value = required(properties, key);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalStateException("Native provenance field " + key + " is invalid: " + value, error);
        }
    }

    private static void requireEquals(Properties properties, String key, String expected) {
        String actual = required(properties, key);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Native provenance field " + key + " must be " + expected + " but was " + actual
            );
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Native provenance field is missing: " + key);
        }
        return value.trim();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 digest is not available", error);
        }
    }

    record Manifest(
            String sourceRevision,
            String crateVersion,
            int dubboAbi,
            int redisAbi,
            String sha256) {}

    record BuildInfo(
            String crate,
            String crateVersion,
            String sourceRevision,
            String target,
            String profile,
            String features,
            int restAbi,
            int dubboAbi,
            int redisAbi) {}
}

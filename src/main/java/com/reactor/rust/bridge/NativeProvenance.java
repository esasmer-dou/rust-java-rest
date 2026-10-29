package com.reactor.rust.bridge;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Properties;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

final class NativeProvenance {

    private static final String MANIFEST_RESOURCE = "native/native-provenance.properties";

    private NativeProvenance() {}

    static Manifest verifyPackagedBinary(
            ClassLoader classLoader,
            String platform,
            byte[] binary,
            int expectedRestAbi) {
        return verify(loadManifest(classLoader, platform), platform, binary, expectedRestAbi);
    }

    static Manifest verifyPackagedBinary(URL binaryResource, String platform, byte[] binary, int expectedRestAbi) {
        return verify(loadManifest(binaryResource), platform, binary, expectedRestAbi);
    }

    private static Manifest verify(Properties properties, String platform, byte[] binary, int expectedRestAbi) {
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
        requireEquals(
                properties,
                "glowroot.abi",
                Integer.toString(NativeBridge.EXPECTED_GLOWROOT_NATIVE_ABI_VERSION)
        );

        String expectedHash = required(properties, platform + ".sha256");
        String actualHash = NativeArtifactDigest.sha256Hex(binary);
        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            throw new IllegalStateException(
                    "Packaged native binary hash mismatch for " + platform
                            + ": expected " + expectedHash + " but found " + actualHash
            );
        }

        return new Manifest(
                properties.getProperty(platform + ".source.revision", required(properties, "source.revision")).trim(),
                required(properties, "crate.version"),
                parsePositiveInt(properties, "dubbo.abi"),
                parsePositiveInt(properties, "redis.abi"),
                parsePositiveInt(properties, "glowroot.abi"),
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
                parsePositiveInt(properties, "redisAbi"),
                parsePositiveInt(properties, "glowrootAbi")
        );
    }

    private static Properties loadManifest(ClassLoader classLoader, String platform) {
        InputStream selected = classLoader.getResourceAsStream("native/" + platform + "/native-provenance.properties");
        if (selected == null) selected = classLoader.getResourceAsStream(MANIFEST_RESOURCE);
        try (InputStream input = selected) {
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

    private static Properties loadManifest(URL binaryResource) {
        try {
            try {
                return readManifest(new URL(binaryResource, "native-provenance.properties"));
            } catch (FileNotFoundException missingPlatformManifest) {
                return readManifest(new URL(binaryResource, "../native-provenance.properties"));
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read native provenance from the selected binary artifact", error);
        }
    }

    private static Properties readManifest(URL resource) throws IOException {
        try (InputStream input = openManifest(resource)) {
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        }
    }

    private static InputStream openManifest(URL resource) throws IOException {
        if ("file".equals(resource.getProtocol())) {
            try {
                return Files.newInputStream(Path.of(resource.toURI()));
            } catch (java.nio.file.NoSuchFileException missing) {
                throw new FileNotFoundException(resource.toString());
            } catch (java.net.URISyntaxException malformed) {
                throw new IOException("Invalid native manifest URI", malformed);
            }
        }
        var connection = resource.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
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

    record Manifest(
            String sourceRevision,
            String crateVersion,
            int dubboAbi,
            int redisAbi,
            int glowrootAbi,
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
            int redisAbi,
            int glowrootAbi) {}
}

package com.reactor.rust.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** JDK-only build/release gate; also runnable directly with Java source-file mode. */
public final class PackagedNativeProvenanceVerifier {
    private static final List<String> PLATFORMS = List.of(
            "windows-x64", "linux-x64", "linux-arm64", "macos-arm64");
    private static final List<String> ABI_KEYS = List.of(
            "rest.abi", "dubbo.abi", "redis.abi", "glowroot.abi");
    private static final String ROOT_MANIFEST = "native/native-provenance.properties";
    private static final int MAX_MANIFEST_BYTES = 65536;

    private PackagedNativeProvenanceVerifier() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Expected: <contract.properties> <runtime-jar> [runtime-jar...]");
        }
        Path contract = Path.of(args[0]);
        for (int index = 1; index < args.length; index++) {
            Path jar = Path.of(args[index]);
            verify(contract, jar);
            System.out.println("Native provenance verified: " + jar);
        }
    }

    static void verify(Path contractFile, Path jarPath) throws IOException {
        Properties contract;
        try (InputStream input = Files.newInputStream(contractFile)) {
            contract = readProperties(input, contractFile.toString());
        }
        for (String abi : ABI_KEYS) {
            if (!required(contract, abi).matches("[1-9][0-9]*")) {
                throw new IllegalStateException("Invalid ABI contract: " + abi);
            }
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var names = new HashSet<String>();
            for (var entries = jar.entries(); entries.hasMoreElements();) {
                String name = entries.nextElement().getName();
                if (name.startsWith("native/") && !names.add(name)) {
                    throw new IllegalStateException(jarPath + ": duplicate native entry " + name);
                }
            }
            for (String platform : PLATFORMS) {
                String binaryName = required(contract, platform + ".resource");
                if (!binaryName.startsWith("native/" + platform + "/") || binaryName.contains("..")) {
                    throw new IllegalStateException("Invalid binary resource contract: " + binaryName);
                }
                JarEntry binary = requireEntry(jar, binaryName);
                String sidecar = "native/" + platform + "/native-provenance.properties";
                // An existing but invalid sidecar must never fall back to the root manifest.
                String selected = jar.getJarEntry(sidecar) == null ? ROOT_MANIFEST : sidecar;
                Properties manifest;
                try (InputStream input = jar.getInputStream(requireEntry(jar, selected))) {
                    manifest = readProperties(input, jarPath + "!/" + selected);
                }
                requireEquals(manifest, "schema", "2", selected);
                for (String abi : ABI_KEYS) {
                    requireEquals(manifest, abi, required(contract, abi), selected);
                }
                required(manifest, "source.revision");
                required(manifest, "crate.version");
                String expected = required(manifest, platform + ".sha256");
                String actual;
                try (InputStream input = jar.getInputStream(binary)) {
                    actual = sha256(input);
                }
                if (!expected.matches("[a-fA-F0-9]{64}") || !expected.equalsIgnoreCase(actual)) {
                    throw new IllegalStateException(jarPath + "!/" + selected + ": " + platform
                            + " SHA-256 mismatch: expected " + expected + " but found " + actual);
                }
            }
        }
    }

    private static JarEntry requireEntry(JarFile jar, String name) {
        JarEntry entry = jar.getJarEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() == 0) {
            throw new IllegalStateException(jar.getName() + ": missing or empty native entry " + name);
        }
        return entry;
    }

    private static Properties readProperties(InputStream input, String name) throws IOException {
        byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new IllegalStateException("Native provenance properties exceed limit: " + name);
        }
        Properties properties = new Properties();
        properties.load(new java.io.ByteArrayInputStream(bytes));
        return properties;
    }

    private static String sha256(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", impossible);
        }
        byte[] buffer = new byte[16384];
        for (int length; (length = input.read(buffer)) != -1;) {
            digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireEquals(Properties properties, String key, String expected, String selected) {
        String actual = required(properties, key);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(selected + ": " + key + " must be " + expected + " but was " + actual);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Native provenance field is missing: " + key);
        }
        return value.trim();
    }
}

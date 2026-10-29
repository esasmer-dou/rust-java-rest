package com.reactor.rust.codegen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedNativeProvenanceVerifierTest {
    private static final Path CONTRACT = Path.of("scripts/native-provenance-gate.properties");
    private static final String ROOT = "native/native-provenance.properties";
    private static final Map<String, String> RESOURCES = Map.of(
            "windows-x64", "native/windows-x64/rust_hyper.dll",
            "linux-x64", "native/linux-x64/librust_hyper.so",
            "linux-arm64", "native/linux-arm64/librust_hyper.so",
            "macos-arm64", "native/macos-arm64/librust_hyper.dylib");
    @TempDir Path directory;

    @Test
    void acceptsAllPlatformsUsingRootFallback() throws Exception {
        assertDoesNotThrow(() -> PackagedNativeProvenanceVerifier.verify(CONTRACT, jar("root.jar", entries())));
    }

    @Test
    void platformSidecarsTakePrecedenceOverRootHashes() throws Exception {
        Map<String, byte[]> entries = entries();
        String valid = manifest();
        for (String platform : RESOURCES.keySet()) {
            entries.put(sidecar(platform), bytes(valid));
        }
        entries.put(ROOT, bytes("schema=1\n"));
        assertDoesNotThrow(() -> PackagedNativeProvenanceVerifier.verify(CONTRACT, jar("sidecars.jar", entries)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"windows-x64", "linux-x64", "linux-arm64", "macos-arm64"})
    void rejectsChangedBinaryBytes(String platform) throws Exception {
        Map<String, byte[]> entries = entries();
        entries.put(RESOURCES.get(platform), bytes("changed"));
        var error = assertThrows(IllegalStateException.class,
                () -> PackagedNativeProvenanceVerifier.verify(CONTRACT, jar("changed.jar", entries)));
        assertTrue(error.getMessage().contains(platform + " SHA-256 mismatch"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"linux-arm64", "macos-arm64"})
    void rejectsStaleSidecarEvenWhenRootMatches(String platform) throws Exception {
        Map<String, byte[]> entries = entries();
        entries.put(sidecar(platform), bytes(manifest().replace(hash(bytes(platform)), "0".repeat(64))));
        var error = assertThrows(IllegalStateException.class,
                () -> PackagedNativeProvenanceVerifier.verify(CONTRACT, jar("stale.jar", entries)));
        assertTrue(error.getMessage().contains(sidecar(platform)));
    }

    @Test
    void rejectsInvalidPresentSidecarWithoutRootFallback() throws Exception {
        Map<String, byte[]> entries = entries();
        entries.put(sidecar("linux-arm64"), bytes("schema=1\n"));
        assertThrows(IllegalStateException.class,
                () -> PackagedNativeProvenanceVerifier.verify(CONTRACT, jar("schema.jar", entries)));
    }

    @ParameterizedTest
    @CsvSource({"rest.abi,29,28", "dubbo.abi,7,6", "redis.abi,6,5", "glowroot.abi,6,5"})
    void rejectsComponentAbiMismatch(String key, String expected, String wrong) throws Exception {
        Map<String, byte[]> entries = entries();
        entries.put(ROOT, bytes(manifest().replace(key + "=" + expected, key + "=" + wrong)));
        var error = assertThrows(IllegalStateException.class,
                () -> PackagedNativeProvenanceVerifier.verify(CONTRACT, jar("abi.jar", entries)));
        assertTrue(error.getMessage().contains(key + " must be " + expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"windows-x64", "linux-x64", "linux-arm64", "macos-arm64"})
    void rejectsMissingPackagedPlatform(String platform) throws Exception {
        Map<String, byte[]> entries = entries();
        entries.remove(RESOURCES.get(platform));
        assertThrows(IllegalStateException.class,
                () -> PackagedNativeProvenanceVerifier.verify(CONTRACT, jar("missing.jar", entries)));
    }

    @Test
    void rejectsMissingManifestAndEmptyBinary() throws Exception {
        Map<String, byte[]> entries = entries();
        entries.remove(ROOT);
        assertThrows(IllegalStateException.class,
                () -> PackagedNativeProvenanceVerifier.verify(CONTRACT, jar("no-manifest.jar", entries)));
        Map<String, byte[]> emptyEntries = entries();
        emptyEntries.put(RESOURCES.get("linux-arm64"), new byte[0]);
        Path empty = jar("empty.jar", emptyEntries);
        assertThrows(IllegalStateException.class, () -> PackagedNativeProvenanceVerifier.verify(CONTRACT, empty));
    }

    @Test
    void commandLineChecksEverySuppliedRuntimeJar() throws Exception {
        Path main = jar("main.jar", entries());
        Path runtime = jar("core-runtime.jar", entries());
        assertDoesNotThrow(() -> PackagedNativeProvenanceVerifier.main(
                new String[]{CONTRACT.toString(), main.toString(), runtime.toString()}));
        Map<String, byte[]> entries = entries();
        entries.put(sidecar("macos-arm64"), bytes("schema=1\n"));
        Path invalid = jar("invalid-runtime.jar", entries);
        assertThrows(IllegalStateException.class, () -> PackagedNativeProvenanceVerifier.main(
                new String[]{CONTRACT.toString(), main.toString(), invalid.toString()}));
    }

    private Map<String, byte[]> entries() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (var resource : RESOURCES.entrySet()) {
            entries.put(resource.getValue(), bytes(resource.getKey()));
        }
        entries.put(ROOT, bytes(manifest()));
        return entries;
    }

    private String manifest() throws Exception {
        var manifest = new StringBuilder("schema=2\nrest.abi=29\ndubbo.abi=7\nredis.abi=6\nglowroot.abi=6\n"
                + "crate.version=0.1.0\nsource.revision=3418671b5010afb5dc049897f2a7287dc6a58daf\n");
        for (String platform : RESOURCES.keySet()) {
            manifest.append(platform).append(".sha256=").append(hash(bytes(platform))).append('\n');
        }
        return manifest.toString();
    }

    private Path jar(String name, Map<String, byte[]> entries) throws Exception {
        Path path = directory.resolve(name);
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }

    private static String sidecar(String platform) {
        return "native/" + platform + "/native-provenance.properties";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

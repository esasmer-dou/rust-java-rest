package com.reactor.rust.bridge;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Metadata-only ARM simulation. No native library is loaded by these tests. */
class NativePlatformProvenanceTest {
    private static final byte[] BINARY = {1, 2, 3, 4};
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"linux-arm64", "macos-arm64"})
    void historicalPlatformManifestPreservesCleanRevisionAndBaseRuntime(String platform) throws Exception {
        Path folder = Files.createDirectories(directory.resolve("native/" + platform));
        Path binary = Files.write(folder.resolve("library.bin"), BINARY);
        Files.writeString(folder.getParent().resolve("native-provenance.properties"), manifest(platform, "current-dirty"));
        Files.writeString(folder.resolve("native-provenance.properties"), manifest(platform, "historical-clean"));
        NativeProvenance.Manifest selected = NativeProvenance.verifyPackagedBinary(binary.toUri().toURL(), platform, BINARY, 29);
        assertEquals("historical-clean", selected.sourceRevision());
        assertEquals(NativeArtifactDigest.sha256Hex(BINARY), selected.sha256());
        NativeProvenance.BuildInfo info = NativeLibraryLoader.validateRuntimeProvenance(buildInfo("historical-clean"), 29, selected);
        assertEquals("historical-clean", info.sourceRevision());
        assertEquals("default,dubbo,glowroot,redis,websocket", info.features());
    }

    @Test void legacyRootOnlyManifestStillWorks() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("native/windows-x64"));
        Path binary = Files.write(folder.resolve("library.bin"), BINARY);
        Files.writeString(folder.getParent().resolve("native-provenance.properties"), manifest("windows-x64", "current-dirty"));
        assertEquals("current-dirty", NativeProvenance.verifyPackagedBinary(binary.toUri().toURL(), "windows-x64", BINARY, 29).sourceRevision());
    }

    @Test void globalPlatformRevisionOverrideWorksWithoutSidecar() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("native/linux-arm64"));
        Path binary = Files.write(folder.resolve("library.bin"), BINARY);
        Files.writeString(folder.getParent().resolve("native-provenance.properties"),
                manifest("linux-arm64", "current-dirty") + "linux-arm64.source.revision=historical-clean\n");
        assertEquals("historical-clean", NativeProvenance.verifyPackagedBinary(binary.toUri().toURL(), "linux-arm64", BINARY, 29).sourceRevision());
    }

    @Test void invalidPresentPlatformManifestNeverFallsBackToValidRoot() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("native/linux-arm64"));
        Path binary = Files.write(folder.resolve("library.bin"), BINARY);
        Files.writeString(folder.getParent().resolve("native-provenance.properties"), manifest("linux-arm64", "historical-clean"));
        Files.writeString(folder.resolve("native-provenance.properties"), "schema=1\n");
        assertThrows(IllegalStateException.class, () -> NativeProvenance.verifyPackagedBinary(binary.toUri().toURL(), "linux-arm64", BINARY, 29));
        Files.writeString(folder.resolve("native-provenance.properties"), manifest("linux-arm64", "historical-clean").replace(NativeArtifactDigest.sha256Hex(BINARY), "0".repeat(64)));
        assertThrows(IllegalStateException.class, () -> NativeProvenance.verifyPackagedBinary(binary.toUri().toURL(), "linux-arm64", BINARY, 29));
    }

    @Test void selectedJarOwnsBothPlatformAndFallbackMetadataDespiteClasspathShadowing() throws Exception {
        Path shadow = jar("shadow.jar", Map.of("native/linux-arm64/native-provenance.properties", "schema=1\n".getBytes(StandardCharsets.ISO_8859_1)));
        Path selected = jar("selected.jar", Map.of(
                "native/linux-arm64/library.bin", BINARY,
                "native/linux-arm64/native-provenance.properties", manifest("linux-arm64", "historical-clean").getBytes(StandardCharsets.ISO_8859_1),
                "native/native-provenance.properties", manifest("linux-arm64", "current-dirty").getBytes(StandardCharsets.ISO_8859_1)));
        try (var loader = new URLClassLoader(new URL[]{shadow.toUri().toURL(), selected.toUri().toURL()}, null)) {
            URL binary = loader.getResource("native/linux-arm64/library.bin");
            assertNotNull(binary);
            assertEquals("historical-clean", NativeProvenance.verifyPackagedBinary(binary, "linux-arm64", BINARY, 29).sourceRevision());
        }
    }

    @Test void sourceMismatchStillFailsClosed() {
        NativeProvenance.Manifest selected = new NativeProvenance.Manifest("historical-clean", "0.1.0", 7, 6, 6, "hash");
        assertThrows(IllegalStateException.class, () -> NativeLibraryLoader.validateRuntimeProvenance(buildInfo("current-dirty"), 29, selected));
    }

    @Test void selectedJarRootFallbackIsNotAnUnrelatedClasspathManifest() throws Exception {
        Path shadow = jar("shadow-root.jar", Map.of("native/native-provenance.properties", "schema=1\n".getBytes(StandardCharsets.ISO_8859_1)));
        Path selected = jar("root-fallback.jar", Map.of(
                "native/linux-arm64/library.bin", BINARY,
                "native/native-provenance.properties", manifest("linux-arm64", "historical-clean").getBytes(StandardCharsets.ISO_8859_1)));
        try (var loader = new URLClassLoader(new URL[]{shadow.toUri().toURL(), selected.toUri().toURL()}, null)) {
            assertEquals("historical-clean", NativeProvenance.verifyPackagedBinary(loader.getResource("native/linux-arm64/library.bin"), "linux-arm64", BINARY, 29).sourceRevision());
        }
    }

    @Test void directoryInPlaceOfSidecarFailsClosedRatherThanUsingRoot() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("native/linux-arm64"));
        Path binary = Files.write(folder.resolve("library.bin"), BINARY);
        Files.writeString(folder.getParent().resolve("native-provenance.properties"), manifest("linux-arm64", "historical-clean"));
        Files.createDirectory(folder.resolve("native-provenance.properties"));
        assertThrows(IllegalStateException.class, () -> NativeProvenance.verifyPackagedBinary(binary.toUri().toURL(), "linux-arm64", BINARY, 29));
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

    private static String manifest(String platform, String revision) {
        return "schema=2\nrest.abi=29\ndubbo.abi=7\nredis.abi=6\nglowroot.abi=6\ncrate.version=0.1.0\nsource.revision="
                + revision + "\n" + platform + ".sha256=" + NativeArtifactDigest.sha256Hex(BINARY) + "\n";
    }

    private static String buildInfo(String revision) {
        return "schema=2\ncrate=rust-spring\ncrateVersion=0.1.0\nsourceRevision=" + revision
                + "\ntarget=aarch64-unknown-linux-gnu\nprofile=release\nfeatures=default,dubbo,glowroot,redis,websocket"
                + "\nrestAbi=29\ndubboAbi=7\nredisAbi=6\nglowrootAbi=6\n";
    }
}

package com.reactor.rust.bridge;

import java.io.InputStream;
import java.net.URL;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies real packaged resources as bytes; never loads a native library. */
class PackagedNativeProvenanceConsistencyTest {
    private static final int REST_ABI = 29;

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "windows-x64, native/windows-x64/rust_hyper.dll",
            "linux-x64, native/linux-x64/librust_hyper.so",
            "linux-arm64, native/linux-arm64/librust_hyper.so",
            "macos-arm64, native/macos-arm64/librust_hyper.dylib"
    })
    void packagedBinaryMatchesProvenanceThroughBothOverloads(String platform, String resourcePath) throws Exception {
        ClassLoader loader = PackagedNativeProvenanceConsistencyTest.class.getClassLoader();
        URL binaryResource = loader.getResource(resourcePath);
        assertNotNull(binaryResource, "Missing packaged native binary: " + resourcePath);

        byte[] binary;
        try (InputStream input = binaryResource.openStream()) {
            binary = input.readAllBytes();
        }
        assertTrue(binary.length > 0, "Empty packaged native binary: " + resourcePath);

        assertAll(resourcePath,
                () -> assertDoesNotThrow(
                        () -> NativeProvenance.verifyPackagedBinary(loader, platform, binary, REST_ABI),
                        "ClassLoader provenance verification: " + resourcePath),
                () -> assertDoesNotThrow(
                        () -> NativeProvenance.verifyPackagedBinary(binaryResource, platform, binary, REST_ABI),
                        "URL provenance verification: " + resourcePath));
    }
}

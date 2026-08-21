package com.reactor.rust.bridge;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeProvenanceTest {

    @Test
    void verifiesAllPackagedNativeAbiFields() throws Exception {
        byte[] binary = {1, 2, 3, 4};
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        ClassLoader loader = manifestLoader("""
                schema=2
                rest.abi=29
                dubbo.abi=7
                redis.abi=6
                glowroot.abi=4
                crate.version=0.1.0
                source.revision=abc123
                windows-x64.sha256=%s
                """.formatted(hash));

        NativeProvenance.Manifest manifest =
                NativeProvenance.verifyPackagedBinary(
                        loader,
                        "windows-x64",
                        binary,
                        NativeBridge.EXPECTED_NATIVE_ABI_VERSION
                );

        assertEquals(7, manifest.dubboAbi());
        assertEquals(6, manifest.redisAbi());
        assertEquals(4, manifest.glowrootAbi());
        assertEquals(hash, manifest.sha256());
    }

    @Test
    void parsesRuntimeBuildInformation() {
        NativeProvenance.BuildInfo info = NativeProvenance.parseBuildInfo("""
                schema=2
                crate=rust-spring
                crateVersion=0.1.0
                sourceRevision=abc123-dirty
                target=x86_64-pc-windows-msvc
                profile=release
                features=default
                restAbi=24
                dubboAbi=7
                redisAbi=6
                glowrootAbi=1
                """);

        assertEquals(24, info.restAbi());
        assertEquals(7, info.dubboAbi());
        assertEquals(6, info.redisAbi());
        assertEquals(1, info.glowrootAbi());
        assertEquals("abc123-dirty", info.sourceRevision());
    }

    @Test
    void rejectsMissingAbiFields() {
        assertThrows(
                IllegalStateException.class,
                () -> NativeProvenance.parseBuildInfo("schema=2\ncrate=rust-spring\n")
        );
    }

    @Test
    void rejectsMismatchedGlowrootAbiBeforeStartingTheServer() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> NativeLibraryLoader.validateRuntimeProvenance("""
                        schema=2
                        crate=rust-spring
                        crateVersion=0.1.0
                        sourceRevision=abc123
                        target=x86_64-unknown-linux-gnu
                        profile=release
                        features=default
                        restAbi=29
                        dubboAbi=7
                        redisAbi=6
                        glowrootAbi=1
                        """, NativeBridge.EXPECTED_NATIVE_ABI_VERSION)
        );

        assertEquals(
                "Native Glowroot build provenance ABI mismatch: expected 4 but binary reported 1",
                error.getMessage()
        );
    }

    private static ClassLoader manifestLoader(String manifest) {
        byte[] bytes = manifest.getBytes(StandardCharsets.ISO_8859_1);
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return "native/native-provenance.properties".equals(name)
                        ? new ByteArrayInputStream(bytes)
                        : null;
            }
        };
    }
}

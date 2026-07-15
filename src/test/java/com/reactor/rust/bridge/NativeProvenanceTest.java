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
                rest.abi=23
                dubbo.abi=5
                redis.abi=5
                crate.version=0.1.0
                source.revision=abc123
                windows-x64.sha256=%s
                """.formatted(hash));

        NativeProvenance.Manifest manifest =
                NativeProvenance.verifyPackagedBinary(loader, "windows-x64", binary, 23);

        assertEquals(5, manifest.dubboAbi());
        assertEquals(5, manifest.redisAbi());
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
                restAbi=23
                dubboAbi=5
                redisAbi=5
                """);

        assertEquals(23, info.restAbi());
        assertEquals(5, info.dubboAbi());
        assertEquals(5, info.redisAbi());
        assertEquals("abc123-dirty", info.sourceRevision());
    }

    @Test
    void rejectsMissingAbiFields() {
        assertThrows(
                IllegalStateException.class,
                () -> NativeProvenance.parseBuildInfo("schema=2\ncrate=rust-spring\n")
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

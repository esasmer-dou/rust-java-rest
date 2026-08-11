package com.reactor.rust.bridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeArtifactDigestTest {

    @Test
    void matchesPublishedSha256Vectors() {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                NativeArtifactDigest.sha256Hex(new byte[0])
        );
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                NativeArtifactDigest.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII))
        );
        assertEquals(
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
                NativeArtifactDigest.sha256Hex((
                        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")
                        .getBytes(StandardCharsets.US_ASCII))
        );
    }

    @Test
    void matchesJcaAcrossBlockBoundaries() throws Exception {
        for (int size : new int[] {1, 55, 56, 63, 64, 65, 127, 128, 129, 16 * 1024 + 31}) {
            byte[] input = deterministicBytes(size);
            String expected = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input)
            );
            assertEquals(expected, NativeArtifactDigest.sha256Hex(input), "size=" + size);
        }
    }

    @Test
    void hashesFilesWithoutMaterializingThem() throws Exception {
        byte[] input = deterministicBytes(64 * 1024 + 17);
        Path file = Files.createTempFile("native-artifact-digest", ".bin");
        try {
            Files.write(file, input);
            assertEquals(
                    NativeArtifactDigest.sha256Hex(input),
                    NativeArtifactDigest.sha256Hex(file)
            );
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static byte[] deterministicBytes(int size) {
        byte[] input = new byte[size];
        for (int index = 0; index < input.length; index++) {
            input[index] = (byte) (index * 31 + 7);
        }
        return input;
    }
}

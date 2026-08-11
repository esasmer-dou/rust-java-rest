package com.reactor.rust.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLibraryLoaderTempFileTest {

    @TempDir
    Path directory;

    @Test
    void createsUniqueFilesWithTheRequestedShape() throws Exception {
        Path first = NativeLibraryLoader.createArtifactTempFile(directory, "librust_hyper", ".tmp");
        Path second = NativeLibraryLoader.createArtifactTempFile(directory, "librust_hyper", ".tmp");

        assertNotEquals(first, second);
        assertTrue(Files.isRegularFile(first));
        assertTrue(Files.isRegularFile(second));
        assertTrue(first.getFileName().toString().startsWith("librust_hyper-"));
        assertTrue(first.getFileName().toString().endsWith(".tmp"));
    }
}

package com.reactor.rust.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class NativeLibraryLoaderTest {

    @Test
    void resolvesMacosArm64PackagedResource() {
        assertEquals(
                "native/macos-arm64/librust_hyper.dylib",
                NativeLibraryLoader.libraryResourcePath("Mac OS X", "aarch64")
        );
    }

    @Test
    void resolvesLinuxArm64PackagedResource() {
        assertEquals(
                "native/linux-arm64/librust_hyper.so",
                NativeLibraryLoader.libraryResourcePath("Linux", "aarch64")
        );
    }

    @Test
    void preservesExistingX64ResourcePaths() {
        assertEquals("native/windows-x64/rust_hyper.dll",
                NativeLibraryLoader.libraryResourcePath("Windows 11", "amd64"));
        assertEquals("native/linux-x64/librust_hyper.so",
                NativeLibraryLoader.libraryResourcePath("Linux", "x86_64"));
        assertEquals("native/macos-x64/librust_hyper.dylib",
                NativeLibraryLoader.libraryResourcePath("Mac OS X", "x86_64"));
    }
}

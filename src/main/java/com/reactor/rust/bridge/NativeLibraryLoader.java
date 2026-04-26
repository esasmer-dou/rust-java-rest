package com.reactor.rust.bridge;

import com.reactor.rust.logging.FrameworkLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Native Library Loader - Extracts and loads platform-specific Rust library from JAR resources.
 *
 * Supported platforms:
 * - Linux x64: native/linux-x64/librust_hyper.so
 * - Windows x64: native/windows-x64/rust_hyper.dll
 *
 * Coming soon:
 * - macOS x64: native/macos-x64/librust_hyper.dylib
 * - macOS ARM64: native/macos-arm64/librust_hyper.dylib
 *
 * Usage:
 *   NativeLibraryLoader.load(); // Auto-detect platform
 *   NativeLibraryLoader.load("/path/to/custom/library.so"); // Custom path
 */
public final class NativeLibraryLoader {

    private static final String LIBRARY_NAME = "rust_hyper";
    private static boolean loaded = false;

    private NativeLibraryLoader() {
        // Utility class
    }

    /**
     * Load native library from JAR resources (auto-detect platform).
     *
     * @throws UnsatisfiedLinkError if library cannot be loaded
     */
    public static synchronized void load() {
        if (loaded) {
            return;
        }

        // Check for custom library path (e.g., for development)
        String customPath = System.getProperty("rust.lib.path");
        if (customPath != null) {
            loadFromCustomPath(customPath);
            loaded = true;
            return;
        }

        // Check for java.library.path
        String javaLibPath = System.getProperty("java.library.path");
        if (javaLibPath != null && !javaLibPath.isEmpty()) {
            try {
                System.loadLibrary(LIBRARY_NAME);
                loaded = true;
                FrameworkLogger.info("[NativeLibraryLoader] Loaded from java.library.path: " + LIBRARY_NAME);
                return;
            } catch (UnsatisfiedLinkError e) {
                // Fall through to JAR extraction
            }
        }

        // Extract from JAR resources
        loadFromResources();
        loaded = true;
    }

    /**
     * Load native library from custom path.
     *
     * @param path Absolute path to the native library
     * @throws UnsatisfiedLinkError if library cannot be loaded
     */
    public static synchronized void load(String path) {
        if (loaded) {
            return;
        }
        System.load(path);
        loaded = true;
        FrameworkLogger.info("[NativeLibraryLoader] Loaded from custom path: " + path);
    }

    /**
     * Load library from JAR resources.
     */
    private static void loadFromResources() {
        Platform platform = detectPlatform();
        String resourcePath = platform.getLibraryResourcePath();
        String libraryFileName = platform.getLibraryFileName();

        FrameworkLogger.info("[NativeLibraryLoader] Detected platform: " + platform);
        FrameworkLogger.info("[NativeLibraryLoader] Looking for resource: " + resourcePath);

        // Extract library from JAR to temp file
        Path tempFile = extractLibrary(resourcePath, libraryFileName);

        // Load the extracted library
        System.load(tempFile.toString());
        FrameworkLogger.info("[NativeLibraryLoader] Loaded from extracted: " + tempFile);

        // Delete on exit (best effort)
        tempFile.toFile().deleteOnExit();
    }

    /**
     * Load from custom path specified by system property.
     */
    private static void loadFromCustomPath(String customPath) {
        Path path = Path.of(customPath);
        if (Files.isDirectory(path)) {
            // It's a directory, append library name
            Platform platform = detectPlatform();
            path = path.resolve(platform.getLibraryFileName());
        }

        if (!Files.exists(path)) {
            throw new UnsatisfiedLinkError("Native library not found at: " + path);
        }

        System.load(path.toString());
        FrameworkLogger.info("[NativeLibraryLoader] Loaded from custom path: " + path);
    }

    /**
     * Extract library from JAR resources to temp file.
     */
    private static Path extractLibrary(String resourcePath, String libraryFileName) {
        try (InputStream is = NativeLibraryLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                Platform platform = detectPlatform();
                String macOSNote = platform.os == OsType.MACOS
                    ? "\n\nmacOS support is coming soon! For now, you can build from source:\n" +
                      "  1. Install Rust: https://rustup.rs\n" +
                      "  2. cd rust-spring && cargo build --release\n" +
                      "  3. Run with: -Drust.lib.path=/path/to/librust_hyper.dylib"
                    : "";

                throw new UnsatisfiedLinkError(
                    "Native library not found in JAR resources: " + resourcePath + "\n" +
                    "Supported platforms: linux-x64, windows-x64" +
                    macOSNote + "\n\n" +
                    "Alternative: set -Drust.lib.path=/path/to/library"
                );
            }

            // Create temp file with correct extension
            String prefix = LIBRARY_NAME;
            String suffix = libraryFileName.substring(libraryFileName.lastIndexOf('.'));
            Path tempFile = Files.createTempFile(prefix, suffix);

            // Copy library to temp file
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);

            // Make executable (for Unix-like systems)
            try {
                tempFile.toFile().setExecutable(true);
            } catch (Exception e) {
                // Ignore on Windows
            }

            return tempFile;

        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract native library: " + e.getMessage());
        }
    }

    /**
     * Detect current platform (OS + architecture).
     */
    private static Platform detectPlatform() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

        // Detect OS
        OsType osType;
        if (osName.contains("linux")) {
            osType = OsType.LINUX;
        } else if (osName.contains("windows")) {
            osType = OsType.WINDOWS;
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            osType = OsType.MACOS;
        } else {
            throw new UnsatisfiedLinkError("Unsupported OS: " + osName);
        }

        // Detect Architecture
        ArchType archType;
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            archType = ArchType.ARM64;
        } else if (osArch.contains("x86_64") || osArch.contains("amd64") || osArch.contains("x64")) {
            archType = ArchType.X64;
        } else {
            throw new UnsatisfiedLinkError("Unsupported architecture: " + osArch);
        }

        return new Platform(osType, archType);
    }

    /**
     * Check if native library is already loaded.
     */
    public static boolean isLoaded() {
        return loaded;
    }

    // ==================== Platform Detection ====================

    private enum OsType {
        LINUX("linux", ".so", "lib"),
        WINDOWS("windows", ".dll", ""),
        MACOS("macos", ".dylib", "lib");

        private final String name;
        private final String extension;
        private final String prefix;

        OsType(String name, String extension, String prefix) {
            this.name = name;
            this.extension = extension;
            this.prefix = prefix;
        }
    }

    private enum ArchType {
        X64("x64"),
        ARM64("arm64");

        private final String name;

        ArchType(String name) {
            this.name = name;
        }
    }

    private static final class Platform {
        private final OsType os;
        private final ArchType arch;

        Platform(OsType os, ArchType arch) {
            this.os = os;
            this.arch = arch;
        }

        String getLibraryResourcePath() {
            return "native/" + os.name + "-" + arch.name + "/" + getLibraryFileName();
        }

        String getLibraryFileName() {
            return os.prefix + LIBRARY_NAME + os.extension;
        }

        @Override
        public String toString() {
            return os.name + "-" + arch.name;
        }
    }
}

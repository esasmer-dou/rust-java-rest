package com.reactor.rust.bridge;

import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.startup.StartupTimeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final long TEMP_FILE_NONCE = System.nanoTime() ^ System.currentTimeMillis();
    private static final AtomicLong TEMP_FILE_SEQUENCE = new AtomicLong();
    private static boolean loaded = false;
    private static volatile LoadedArtifact loadedArtifact =
            LoadedArtifact.external("not-loaded", "unknown", "unknown");

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
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("native.load")) {

            // Check for custom library path (e.g., for development)
            String customPath = System.getProperty("rust.lib.path");
            if (customPath != null) {
                loadFromCustomPath(customPath);
                loaded = true;
                return;
            }

            // Check for java.library.path
            String javaLibPath = System.getProperty("java.library.path");
            boolean tryJavaLibraryPath = PropertiesLoader.getBoolean(
                    "reactor.native.load.java-library-path-first",
                    false
            );
            if (tryJavaLibraryPath && javaLibPath != null && !javaLibPath.isEmpty()) {
                try {
                    System.loadLibrary(LIBRARY_NAME);
                    loadedArtifact = LoadedArtifact.external(
                            "java.library.path",
                            javaLibPath,
                            "unknown"
                    );
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
        Path nativePath = Path.of(path).toAbsolutePath().normalize();
        System.load(nativePath.toString());
        loadedArtifact = LoadedArtifact.external(
                "explicit-api-path",
                nativePath.toString(),
                fileHash(nativePath)
        );
        loaded = true;
        FrameworkLogger.info("[NativeLibraryLoader] Loaded from custom path: " + nativePath);
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
        ExtractedLibrary extracted = extractLibrary(resourcePath, libraryFileName, platform);

        // Load the extracted library
        System.load(extracted.path().toString());
        loadedArtifact = new LoadedArtifact(
                "jar-resource",
                extracted.path().toString(),
                extracted.manifest().sha256(),
                extracted.manifest()
        );
        FrameworkLogger.info("[NativeLibraryLoader] Loaded verified native resource: "
                + extracted.path() + " sha256=" + extracted.manifest().sha256());

        if (!isExtractionCacheEnabled()) {
            // Delete on exit (best effort). Cached native files are intentionally kept for faster cold starts.
            extracted.path().toFile().deleteOnExit();
        }
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
        loadedArtifact = LoadedArtifact.external("custom-path", path.toString(), fileHash(path));
        FrameworkLogger.info("[NativeLibraryLoader] Loaded from custom path: " + path);
    }

    /**
     * Extract library from JAR resources to temp file.
     */
    private static ExtractedLibrary extractLibrary(
            String resourcePath,
            String libraryFileName,
            Platform platform) {
        try (InputStream is = NativeLibraryLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
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

            byte[] bytes = is.readAllBytes();
            NativeProvenance.Manifest manifest = NativeProvenance.verifyPackagedBinary(
                    NativeLibraryLoader.class.getClassLoader(),
                    platform.toString(),
                    bytes,
                    NativeBridge.EXPECTED_NATIVE_ABI_VERSION
            );
            if (isExtractionCacheEnabled()) {
                return new ExtractedLibrary(
                        extractToCache(bytes, libraryFileName, platform, manifest.sha256()),
                        manifest
                );
            }

            // Create temp file with correct extension
            String prefix = LIBRARY_NAME;
            String suffix = libraryFileName.substring(libraryFileName.lastIndexOf('.'));
            Path tempFile = createArtifactTempFile(
                    Path.of(System.getProperty("java.io.tmpdir")),
                    prefix,
                    suffix
            );

            // Copy library to temp file
            Files.write(tempFile, bytes);

            // Make executable (for Unix-like systems)
            try {
                tempFile.toFile().setExecutable(true);
            } catch (Exception e) {
                // Ignore on Windows
            }

            return new ExtractedLibrary(tempFile, manifest);

        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract native library: " + e.getMessage());
        }
    }

    private static boolean isExtractionCacheEnabled() {
        return PropertiesLoader.getBoolean("reactor.native.extract.cache.enabled", true);
    }

    private static Path extractToCache(
            byte[] bytes,
            String libraryFileName,
            Platform platform,
            String hash) throws IOException {
        String cacheDir = PropertiesLoader.get(
                "reactor.native.extract.cache-dir",
                Path.of(System.getProperty("user.home"), ".reactor", "native").toString()
        );
        if (cacheDir == null || cacheDir.isBlank()) {
            cacheDir = Path.of(System.getProperty("user.home"), ".reactor", "native").toString();
        }
        Path targetDir = Path.of(cacheDir)
                .resolve("abi-" + NativeBridge.EXPECTED_NATIVE_ABI_VERSION)
                .resolve(platform.toString())
                .resolve(hash.substring(0, 16));
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(libraryFileName);

        if (isValidCachedFile(target, bytes.length, hash)) {
            FrameworkLogger.info("[NativeLibraryLoader] Using cached native library: " + target);
            return target;
        }

        Path temp = createArtifactTempFile(targetDir, libraryFileName, ".tmp");
        Files.write(temp, bytes);
        try {
            temp.toFile().setExecutable(true);
        } catch (Exception ignored) {
            // Ignore on Windows
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        FrameworkLogger.info("[NativeLibraryLoader] Cached native library: " + target);
        return target;
    }

    static Path createArtifactTempFile(Path directory, String prefix, String suffix) throws IOException {
        Files.createDirectories(directory);
        String processId = Long.toUnsignedString(ProcessHandle.current().pid(), 36);
        String processNonce = Long.toUnsignedString(TEMP_FILE_NONCE, 36);
        for (int attempt = 0; attempt < 64; attempt++) {
            String sequence = Long.toUnsignedString(TEMP_FILE_SEQUENCE.incrementAndGet(), 36);
            Path candidate = directory.resolve(
                    prefix + "-" + processId + "-" + processNonce + "-" + sequence + suffix
            );
            try {
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException ignored) {
                // A shared extraction directory can be used by multiple JVMs; retry with a new sequence.
            }
        }
        throw new IOException("Cannot allocate a unique native artifact temp file in " + directory);
    }

    private static boolean isValidCachedFile(Path target, int expectedSize, String expectedHash) throws IOException {
        if (!Files.exists(target) || Files.size(target) != expectedSize) {
            return false;
        }
        return expectedHash.equals(NativeArtifactDigest.sha256Hex(target));
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

    public static String loadedArtifactInfo() {
        LoadedArtifact artifact = loadedArtifact;
        return "source=" + artifact.source()
                + ", location=" + artifact.location()
                + ", sha256=" + artifact.sha256();
    }

    static NativeProvenance.BuildInfo validateRuntimeProvenance(
            String nativeBuildInfo,
            int expectedRestAbi) {
        NativeProvenance.BuildInfo buildInfo = NativeProvenance.parseBuildInfo(nativeBuildInfo);
        if (buildInfo.restAbi() != expectedRestAbi) {
            throw new IllegalStateException(
                    "Native build provenance ABI mismatch: expected " + expectedRestAbi
                            + " but binary reported " + buildInfo.restAbi()
            );
        }
        if (buildInfo.redisAbi() != NativeBridge.EXPECTED_REDIS_NATIVE_ABI_VERSION) {
            throw new IllegalStateException(
                    "Native Redis build provenance ABI mismatch: expected "
                            + NativeBridge.EXPECTED_REDIS_NATIVE_ABI_VERSION
                            + " but binary reported " + buildInfo.redisAbi()
            );
        }
        if (buildInfo.dubboAbi() != NativeBridge.EXPECTED_DUBBO_NATIVE_ABI_VERSION) {
            throw new IllegalStateException(
                    "Native Dubbo build provenance ABI mismatch: expected "
                            + NativeBridge.EXPECTED_DUBBO_NATIVE_ABI_VERSION
                            + " but binary reported " + buildInfo.dubboAbi()
            );
        }

        NativeProvenance.Manifest manifest = loadedArtifact.manifest();
        if (manifest != null) {
            if (!manifest.sourceRevision().equals(buildInfo.sourceRevision())) {
                throw new IllegalStateException(
                        "Packaged native source revision mismatch: manifest="
                                + manifest.sourceRevision() + " binary=" + buildInfo.sourceRevision()
                );
            }
            if (!manifest.crateVersion().equals(buildInfo.crateVersion())) {
                throw new IllegalStateException(
                        "Packaged native crate version mismatch: manifest="
                                + manifest.crateVersion() + " binary=" + buildInfo.crateVersion()
                );
            }
            if (manifest.redisAbi() != buildInfo.redisAbi()) {
                throw new IllegalStateException(
                        "Packaged native Redis ABI mismatch: manifest="
                                + manifest.redisAbi() + " binary=" + buildInfo.redisAbi()
                );
            }
            if (manifest.dubboAbi() != buildInfo.dubboAbi()) {
                throw new IllegalStateException(
                        "Packaged native Dubbo ABI mismatch: manifest="
                                + manifest.dubboAbi() + " binary=" + buildInfo.dubboAbi()
                );
            }
        }
        return buildInfo;
    }

    private static String fileHash(Path path) {
        try {
            return NativeArtifactDigest.sha256Hex(path);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot hash native library: " + path, error);
        }
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

    private record ExtractedLibrary(Path path, NativeProvenance.Manifest manifest) {}

    private record LoadedArtifact(
            String source,
            String location,
            String sha256,
            NativeProvenance.Manifest manifest) {

        static LoadedArtifact external(String source, String location, String sha256) {
            return new LoadedArtifact(source, location, sha256, null);
        }
    }
}

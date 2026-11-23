package com.reactor.rust.http;

import com.reactor.rust.bridge.NativeBridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/** Build-time preparation and startup-only registration for immutable classpath assets. */
public final class PackagedAsset {
    private static final long MAX_DISK_BYTES = 64L << 20;
    private static final long MAX_INLINE_BYTES = 1L << 20;
    private static final int MAX_ASSETS = 256;
    private static final Set<String> REPRESENTATION_HEADERS = Set.of("etag", "content-type",
            "content-encoding", "content-length", "accept-ranges", "content-range");
    private static long diskBytes;
    private static long inlineBytes;
    private static int entries;

    private record Variant(long size, String hash) {
        String etag() { return '"' + hash + '"'; }

        static Variant parse(String line) throws IOException {
            if (!line.matches("[0-9]{1,8} [0-9a-f]{64}")) {
                throw new IOException("Invalid packaged asset size or SHA-256");
            }
            int split = line.indexOf(' ');
            return new Variant(Long.parseLong(line.substring(0, split)), line.substring(split + 1));
        }

        static Variant describe(Path file) throws IOException {
            var digest = sha256();
            try (var in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return new Variant(Files.size(file), HexFormat.of().formatHex(digest.digest()));
        }

        String serialize() { return size + " " + hash; }
    }

    private final Path identityFile;
    private final Path gzipFile;
    private final String type;
    private final Variant identity;
    private final Variant gzip;

    private PackagedAsset(Path identityFile, Path gzipFile, String type, Variant identity, Variant gzip) {
        this.identityFile = identityFile;
        this.gzipFile = gzipFile;
        this.type = type;
        this.identity = identity;
        this.gzip = gzip;
    }

    /** Run on an asset in the build's resource output directory, before creating the JAR. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: PackagedAsset <resource-output-file> <content-type>");
        }
        Path asset = Path.of(args[0]);
        String type = checkedType(args[1]);
        if (!Files.isRegularFile(asset) || Files.size(asset) > MAX_DISK_BYTES) {
            throw new IOException("Asset must be a regular file of at most 64 MiB: " + asset);
        }
        Variant identity = Variant.describe(asset);
        Path gzipFile = asset.resolveSibling(asset.getFileName() + ".asset-gzip");
        try (var gzip = new GZIPOutputStream(Files.newOutputStream(gzipFile))) {
            Files.copy(asset, gzip);
        }
        String metadata = type + "\n" + identity.serialize() + "\n";
        if (Files.size(gzipFile) < identity.size) {
            metadata += Variant.describe(gzipFile).serialize() + "\n";
        } else {
            Files.delete(gzipFile);
        }
        Files.writeString(asset.resolveSibling(asset.getFileName() + ".asset"), metadata, StandardCharsets.US_ASCII);
    }

    static synchronized PackagedAsset extract(Class<?> anchor, String resource) {
        Objects.requireNonNull(anchor, "anchor");
        if (resource == null || !resource.matches("/(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Packaged asset requires an absolute classpath resource name");
        }
        for (String part : resource.split("/")) {
            if (part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Traversal in packaged asset name: " + resource);
            }
        }
        Path directory = null;
        Path identityFile = null;
        Path gzipFile = null;
        try {
            byte[] manifest;
            try (var in = open(anchor, resource + ".asset")) {
                manifest = in.readNBytes(513);
            }
            String[] lines = new String(manifest, StandardCharsets.US_ASCII).split("\n", -1);
            if (manifest.length > 512 || (lines.length != 3 && lines.length != 4)
                    || !lines[lines.length - 1].isEmpty()) {
                throw new IOException("Invalid packaged asset manifest: " + resource);
            }
            String type = checkedType(lines[0]);
            Variant identity = Variant.parse(lines[1]);
            Variant gzip = lines.length == 4 ? Variant.parse(lines[2]) : null;
            long bytes = identity.size + (gzip == null ? 0 : gzip.size);
            if (entries >= MAX_ASSETS || bytes > MAX_DISK_BYTES - diskBytes) {
                throw new IOException("Packaged asset budget exhausted (256 entries/64 MiB)");
            }
            directory = Files.createTempDirectory("reactor-packaged-asset-");
            identityFile = directory.resolve("identity");
            copyVerified(anchor, resource, identityFile, identity);
            if (gzip != null) {
                gzipFile = directory.resolve("gzip");
                copyVerified(anchor, resource + ".asset-gzip", gzipFile, gzip);
            }
            directory.toFile().deleteOnExit();
            identityFile.toFile().deleteOnExit();
            if (gzipFile != null) {
                gzipFile.toFile().deleteOnExit();
            }
            diskBytes += bytes;
            entries++;
            return new PackagedAsset(identityFile, gzipFile, type, identity, gzip);
        } catch (IOException failure) {
            remove(gzipFile, failure);
            remove(identityFile, failure);
            remove(directory, failure);
            throw new UncheckedIOException("Cannot prepare packaged asset " + resource, failure);
        }
    }

    private static void remove(Path path, IOException failure) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    int register(FileResponse response, long inlineLimit) {
        var headers = new StringBuilder("Content-Type: ").append(type)
                .append("\nVary: Accept-Encoding\nAccept-Ranges: none\n");
        // Representation headers come only from the validated manifest.
        response.getHeaders().forEach((name, value) -> {
            if (name != null && value != null && !REPRESENTATION_HEADERS.contains(name.trim().toLowerCase(Locale.ROOT))) {
                if (name.indexOf(':') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0
                        || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("Invalid packaged asset response header");
                }
                headers.append(name).append(": ").append(value).append('\n');
            }
        });
        int identityId = registerVariant(identityFile, identity, headers.toString(), inlineLimit);
        int gzipId = gzip == null ? 0 : registerVariant(gzipFile, gzip, headers + "Content-Encoding: gzip\n", inlineLimit);
        if (identityId <= 0 || (gzip != null && gzipId <= 0)
                || !NativeBridge.linkPackagedStaticFileResponse(identityId, gzipId)) {
            throw new IllegalStateException("Native packaged asset registration failed: " + identityFile);
        }
        return identityId;
    }

    private static synchronized int registerVariant(Path path, Variant variant, String headers, long inlineLimit) {
        long threshold = Math.min(inlineLimit, MAX_INLINE_BYTES - inlineBytes);
        int id = NativeBridge.registerStaticFileResponse(path.toString(), headers + "ETag: " + variant.etag() + "\n",
                200, threshold);
        if (id > 0 && threshold > 0 && variant.size <= threshold) {
            inlineBytes += variant.size;
        }
        return id;
    }

    private static String checkedType(String value) throws IOException {
        if (value.isBlank() || value.length() > 128 || !value.contains("/")
                || !value.chars().allMatch(c -> c >= 32 && c <= 126)) {
            throw new IOException("Invalid packaged asset content type");
        }
        return value;
    }

    private static InputStream open(Class<?> anchor, String name) throws IOException {
        InputStream in = anchor.getResourceAsStream(name);
        if (in == null) {
            throw new IOException("Missing classpath resource: " + name);
        }
        return in;
    }

    private static void copyVerified(Class<?> anchor, String name, Path target, Variant variant) throws IOException {
        var digest = sha256();
        try (var in = new DigestInputStream(open(anchor, name), digest);
                var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8 * 1024];
            long remaining = variant.size;
            while (remaining > 0) {
                int count = in.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (count < 0) {
                    break;
                }
                out.write(buffer, 0, count);
                remaining -= count;
            }
            if (remaining != 0 || in.read() != -1 || !variant.hash.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IOException("Packaged asset does not match manifest: " + name);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

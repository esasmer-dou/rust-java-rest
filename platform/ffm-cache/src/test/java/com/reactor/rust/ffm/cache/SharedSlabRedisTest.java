package com.reactor.rust.ffm.cache;

import com.reactor.rust.cache.api.CacheReadSnapshot;
import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.internal.nativebridge.NativeRedisBridge;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "reactor.ffm.redis.test", matches = "true")
class SharedSlabRedisTest {
    @Test void realRedisOwnershipFallbackAndNoReplayInFreshProcesses() throws Exception {
        for (String mode : List.of("ffm", "jni-slab", "jni-key-limits", "jni", "no-preview", "no-access", "large-limit")) {
            List<String> args = new ArrayList<>();
            args.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            args.addAll(List.of("-Xms8m", "-Xmx48m", "-XX:ActiveProcessorCount=1"));
            if (mode.equals("ffm") || mode.equals("large-limit")) args.addAll(List.of("--enable-preview", "--enable-native-access=ALL-UNNAMED"));
            if (mode.equals("no-access")) args.add("--enable-preview");
            args.addAll(List.of("-Drust.lib.path=" + System.getProperty("rust.lib.path"), "-cp",
                    System.getProperty("java.class.path"), Probe.class.getName(), mode));
            Path log = Files.createTempFile("slab-" + mode, ".log");
            Process child = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(child.waitFor(60, TimeUnit.SECONDS), mode + " timed out");
                String output = Files.readString(log);
                System.out.println(output);
                assertEquals(0, child.exitValue(), output);
                assertTrue(output.contains("SLAB_REDIS_PASS=" + mode), output);
            } finally { if (child.isAlive()) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
        }
    }

    public static final class Probe {
        public static void main(String[] args) {
            String mode = args[0];
            byte[] value = "{\"name\":\"\u00c7a\u011fr\u0131 \ud83d\ude00\"}".getBytes(StandardCharsets.UTF_8);
            String key = "phase54:\u00e7a\u011fr\u0131:\ud83d\ude00";
            int writer = NativeRedisBridge.createClient(config(131072));
            SharedSlabCacheReader reader = null;
            try {
                require(NativeRedisBridge.set(writer, key, value, 60000), "Seed");
                require(NativeRedisBridge.set(writer, "phase54:empty", new byte[0], 60000), "Empty seed");
                byte[] large = new byte[65537]; Arrays.fill(large, (byte) 'X');
                require(NativeRedisBridge.set(writer, "phase54:oversized", large, 60000), "Large seed");
                boolean openj9 = System.getProperty("java.vm.name", "").contains("OpenJ9");
                boolean expectedFast = mode.equals("ffm") && !openj9;
                boolean nativeSlab = mode.equals("jni-slab") || mode.equals("jni-key-limits");
                boolean expectedSlab = expectedFast || nativeSlab;
                int keyLimit = mode.equals("jni-key-limits") ? 4096 : 256;
                reader = nativeSlab ? SharedSlabCacheReader.openNativeSlab(config(65536), true, 4, keyLimit)
                        : SharedSlabCacheReader.open(config(mode.equals("large-limit") ? 131072 : 65536),
                        !mode.equals("jni"), expectedFast, 4, 256);
                require(reader.ffmActive() == expectedFast, "Backend selection " + mode);
                require(reader.slabActive() == expectedSlab, "Slab transport " + mode);
                require(reader.slabPayloadBytes() == (expectedSlab ? 262144 : 0), "Slab bound");
                for (String invalid : List.of("", "\ud800", "\udc00", "\ud800x", "x".repeat(keyLimit + 1), "\u20ac".repeat(keyLimit / 3 + 1))) {
                    try { reader.read(invalid).close(); throw new AssertionError("Invalid key admitted"); }
                    catch (IllegalArgumentException expected) { }
                }
                for (String valid : List.of("x".repeat(keyLimit), "\u00e7".repeat(keyLimit / 2), "\ud83d\ude00".repeat(keyLimit / 4), "x".repeat(127) + "\ud83d\ude00", "nul\0key")) {
                    require(NativeRedisBridge.set(writer, valid, value, 60000), "Bounded key seed");
                    try (CacheReadSnapshot body = reader.read(valid)) { expect(body, value); }
                }
                try (CacheReadSnapshot body = reader.read(key)) {
                    require(body.hit(), "UTF8 hit");
                    require(body.writeTo(ByteBuffer.allocateDirect(1), 0) == -value.length, "Capacity retry");
                    require(NativeRedisBridge.set(writer, key, new byte[]{'x'}, 60000), "Concurrent refresh");
                    expect(body, value);
                }
                try (CacheReadSnapshot body = reader.read(key)) { expect(body, new byte[]{'x'}); }
                try (CacheReadSnapshot body = reader.read("phase54:missing")) { require(!body.hit(), "Miss"); }
                try (CacheReadSnapshot body = reader.read("phase54:empty")) { require(body.hit() && body.length() == 0, "Empty hit"); }
                if (expectedSlab) {
                    CacheReadSnapshot[] held = new CacheReadSnapshot[4];
                    try {
                        for (int i = 0; i < held.length; i++) held[i] = reader.read(key);
                        try { reader.read(key); throw new AssertionError("Unbounded admission"); }
                        catch (FfmReadException expected) { require(expected.nativeStatus() == 2, "Busy status"); }
                    } finally { for (CacheReadSnapshot body : held) if (body != null) body.close(); }
                    try { reader.read("phase54:oversized"); throw new AssertionError("Oversized FFM read replayed through JNI"); }
                    catch (FfmReadException expected) { require(expected.getMessage().contains("no JNI replay"), "Error status"); }
                } else if (mode.equals("large-limit")) {
                    try (CacheReadSnapshot body = reader.read("phase54:oversized")) { expect(body, large); }
                }
                if (expectedSlab) {
                    ByteBuffer output = ByteBuffer.allocateDirect(16);
                    for (int i = 0; i < 150_000; i++) {
                        try (CacheReadSnapshot snapshot = reader.read(key)) {
                            require(snapshot.writeTo(output, 0) == 1 && output.get(0) == 'x', "Sustained ownership/read");
                        }
                    }
                    System.out.println("SUSTAINED_SLAB_READS=150000;heapLimitMiB=48;transport=" + mode);
                }
                try (CacheReadSnapshot body = reader.read(key)) {
                    reader.close(); reader.close();
                    expect(body, new byte[]{'x'});
                }
                try { reader.read(key); throw new AssertionError("Closed reader admitted a request"); }
                catch (IllegalStateException expected) { require(expected.getMessage().contains("closed"), "Close status"); }
                System.out.println("SLAB_REDIS_PASS=" + mode + ";FFM=" + expectedFast + ";utf8,empty,miss,retry,refresh,release,close");
            } finally {
                if (reader != null) reader.close();
                NativeRedisBridge.closeClient(writer);
            }
        }
        public static RustCacheConfig config(int maximum) {
            return RustCacheConfig.builder().host("127.0.0.1").port(6379).readConnections(1).writeConnections(1)
                    .maxReadInflight(1).maxWriteInflight(1).maxResponseBytes(maximum).build();
        }
        private static void expect(CacheReadSnapshot body, byte[] expected) {
            ByteBuffer out = ByteBuffer.allocateDirect(expected.length + 16);
            require(body.writeTo(out, 7) == expected.length, "Response size");
            for (int i = 0; i < expected.length; i++) require(out.get(7 + i) == expected[i], "Response byte " + i);
        }
        private static void require(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
    }
}

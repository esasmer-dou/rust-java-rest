package com.reactor.rust.codegen;

import com.reactor.rust.json.DirectJsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SwarJsonCodegenTest {
    @TempDir Path temp;

    @Test void defaultGenerationKeepsTheExistingFieldPrefixPath() throws Exception {
        Path classes = compile(null);
        String source = Files.readString(classes.getParent().resolve("generated/fixture/AccountDirectJsonWriter.java"));
        assertTrue(source.contains("json.fieldPrefix(FIELD_PREFIXES,"));
        assertFalse(source.contains("writePrebakedLong"));
        assertFalse(source.contains("writePrebakedLiteral"));
        Path disabled = compile("false");
        assertEquals(source, Files.readString(disabled.getParent()
                .resolve("generated/fixture/AccountDirectJsonWriter.java")));
        assertArrayEquals(Files.readAllBytes(classes.resolve("fixture/AccountDirectJsonWriter.class")),
                Files.readAllBytes(disabled.resolve("fixture/AccountDirectJsonWriter.class")));
    }

    @Test void enabledGenerationMatchesUtf8OutputAndCapacityRetries() throws Exception {
        Path classes = compile("true");
        String source = Files.readString(classes.getParent().resolve("generated/fixture/AccountDirectJsonWriter.java"));
        assertTrue(source.contains("json.writePrebakedLiteral(0x"));
        assertFalse(source.contains("FIELD_PREFIXES"));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = loader.loadClass("fixture.Account");
            Object value = type.getConstructor(String.class, long.class, String.class)
                    .newInstance("Istanbul", Long.MIN_VALUE, "\u0130\u011f\ud83d\ude80");
            @SuppressWarnings("unchecked")
            DirectJsonWriter<Object> writer = (DirectJsonWriter<Object>) loader
                    .loadClass("fixture.AccountDirectJsonWriter").getField("INSTANCE").get(null);
            byte[] expected = ("{\"customerName\":\"Istanbul\",\"balanceCents\":-9223372036854775808,"
                    + "\"\u015fehir\":\"\u0130\u011f\ud83d\ude80\"}").getBytes(StandardCharsets.UTF_8);
            for (ByteOrder order : new ByteOrder[]{ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
                for (int size : new int[]{1, 7, 8, expected.length - 1, expected.length}) {
                    ByteBuffer out = ByteBuffer.allocateDirect(size).order(order);
                    int actual = writer.write(value, out, 0);
                    assertEquals(size < expected.length ? -expected.length : expected.length, actual);
                    assertSame(order, out.order());
                    byte[] prefix = new byte[size];
                    out.get(0, prefix);
                    assertArrayEquals(java.util.Arrays.copyOf(expected, size), prefix);
                }
            }
        }
    }

    @Test void invalidBuildOptionsFailRatherThanSilentlySelectingTheWrongArm() throws Exception {
        for (String option : new String[]{"TRUE", "yes", "", "${reactor.json.swar}"}) {
            compile(option, accountSource(), false);
        }
    }

    @Test void nestedWritersMatchAcrossAllTruncationPointsWithoutDuplicatingLiteralWords() throws Exception {
        String fixture = """
                package fixture;
                @com.reactor.rust.annotations.GenerateDirectJsonWriter
                public record Account(String a, String abcde, String abcdef, String aaaa\u011f\u011f,
                        Nested nested, java.util.List<Nested> children, java.util.Optional<Nested> optional) {
                    public record Nested(String customerName, long balanceCents) {}
                }
                """;
        Path scalarClasses = compile("false", fixture, true);
        Path swarClasses = compile("true", fixture, true);
        String scalarSource = Files.readString(scalarClasses.getParent()
                .resolve("generated/fixture/AccountDirectJsonWriter.java"));
        String swarSource = Files.readString(swarClasses.getParent()
                .resolve("generated/fixture/AccountDirectJsonWriter.java"));
        assertFalse(swarSource.contains("reflect"));
        assertFalse(swarSource.contains("getProperty"));
        assertTrue(swarSource.contains("writePrebakedLiteral"));
        assertTrue(fieldTable(swarSource).length() < fieldTable(scalarSource).length());
        String text = "\u0130\u011f\ud83d\ude80\"\\\n\u0000\ud800x\udc00";
        for (int nullMode = 0; nullMode < 3; nullMode++) {
            byte[] expected = renderFixture(scalarClasses, text, nullMode);
            assertArrayEquals(expected, renderFixture(swarClasses, text, nullMode));
        }
    }

    @Test void exactWordPrefixesNeedNoRetainedByteTable() throws Exception {
        Path classes = compile("true", """
                package fixture;
                @com.reactor.rust.annotations.GenerateDirectJsonWriter
                public record Account(String abcde) {}
                """, true);
        String source = Files.readString(classes.getParent().resolve("generated/fixture/AccountDirectJsonWriter.java"));
        assertTrue(source.contains("writePrebakedLiteral"));
        assertFalse(source.contains("FIELD_PREFIXES"));
    }

    @Test void everyFusedUtf8LengthUsesOneCallAndNoRetainedTail() throws Exception {
        List<String> names = new ArrayList<>();
        for (int length = 8; length <= 16; length++) {
            names.add("a".repeat(length - 3));
            names.add("\u011f" + "a".repeat(length - 5));
            names.add("\ud801\udc00" + "a".repeat(length - 7));
        }
        String fixture = "package fixture;\n@com.reactor.rust.annotations.GenerateDirectJsonWriter\n"
                + "public record Account(" + String.join(", ", names.stream().map(name -> "String " + name).toList())
                + ") {}\n";
        Path classes = compile("true", fixture, true);
        String source = Files.readString(classes.getParent().resolve("generated/fixture/AccountDirectJsonWriter.java"));
        assertFalse(source.contains("FIELD_PREFIXES"));
        assertFalse(source.contains("writePrebakedLong"));
        assertFalse(source.contains("new long["));
        assertEquals(names.size(), source.lines().filter(line -> line.contains("json.writePrebakedLiteral(")).count());
        for (String name : names) {
            byte[] prefix = ("\"" + name + "\":").getBytes(StandardCharsets.UTF_8);
            assertTrue(source.contains("json.writePrebakedLiteral(0x" + Long.toHexString(word(prefix, 0))
                    + "L, 0x" + Long.toHexString(word(prefix, prefix.length - 8)) + "L, " + prefix.length + ");"));
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = loader.loadClass("fixture.Account");
            Object[] values = new Object[names.size()];
            Arrays.fill(values, "\u0130\ud83d\ude80");
            Object value = type.getConstructors()[0].newInstance(values);
            @SuppressWarnings("unchecked")
            DirectJsonWriter<Object> writer = (DirectJsonWriter<Object>) loader
                    .loadClass("fixture.AccountDirectJsonWriter").getField("INSTANCE").get(null);
            byte[] expected = ("{" + String.join(",", names.stream()
                    .map(name -> "\"" + name + "\":\"\u0130\ud83d\ude80\"").toList()) + "}")
                    .getBytes(StandardCharsets.UTF_8);
            assertCapacityPrefixes(writer, value, expected);
        }
    }

    @Test void shorterAndLongerPrefixesKeepTheExistingTableAndWordTailPaths() throws Exception {
        Path classes = compile("true", """
                package fixture;
                @com.reactor.rust.annotations.GenerateDirectJsonWriter
                public record Account(String abcd, String abcdefghijklmn, String abcdefghijklmnopqrstu) {}
                """, true);
        String source = Files.readString(classes.getParent().resolve("generated/fixture/AccountDirectJsonWriter.java"));
        assertFalse(source.contains("writePrebakedLiteral"));
        assertEquals(5, source.lines().filter(line -> line.contains("json.writePrebakedLong(")).count());
        assertTrue(source.contains("json.fieldPrefix(FIELD_PREFIXES, 0, 7);"));
        assertTrue(source.contains("json.fieldPrefix(FIELD_PREFIXES, 7, 1);"));
        assertEquals("FIELD_PREFIXES = {34, 97, 98, 99, 100, 34, 58, 58", fieldTable(source));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Object value = loader.loadClass("fixture.Account").getConstructors()[0].newInstance("x", "y", "z");
            @SuppressWarnings("unchecked")
            DirectJsonWriter<Object> writer = (DirectJsonWriter<Object>) loader
                    .loadClass("fixture.AccountDirectJsonWriter").getField("INSTANCE").get(null);
            assertCapacityPrefixes(writer, value,
                    "{\"abcd\":\"x\",\"abcdefghijklmn\":\"y\",\"abcdefghijklmnopqrstu\":\"z\"}"
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    private byte[] renderFixture(Path classes, String text, int nullMode) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> nestedType = loader.loadClass("fixture.Account$Nested");
            Object nested = nestedType.getConstructor(String.class, long.class).newInstance(text, Long.MIN_VALUE);
            Class<?> type = loader.loadClass("fixture.Account");
            Object value = nullMode == 2 ? null : type.getConstructors()[0].newInstance(
                    text, null, "", text, nullMode == 1 ? null : nested,
                    nullMode == 1 ? null : Arrays.asList(nested, null),
                    nullMode == 1 ? java.util.Optional.empty() : java.util.Optional.of(nested));
            @SuppressWarnings("unchecked")
            DirectJsonWriter<Object> writer = (DirectJsonWriter<Object>) loader
                    .loadClass("fixture.AccountDirectJsonWriter").getField("INSTANCE").get(null);
            int required = -writer.write(value, null, 0);
            assertTrue(required > 0);
            ByteBuffer full = ByteBuffer.allocate(required);
            assertEquals(required, writer.write(value, full, 0));
            byte[] expected = full.array();
            assertCapacityPrefixes(writer, value, expected);
            return expected;
        }
    }

    private static void assertCapacityPrefixes(DirectJsonWriter<Object> writer, Object value, byte[] expected) {
        int required = expected.length;
        assertEquals(-required, writer.write(value, null, 0));
        for (boolean direct : new boolean[]{false, true}) {
            for (ByteOrder order : new ByteOrder[]{ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
                for (int available = 0; available <= required; available++) {
                    int offset = 5;
                    ByteBuffer out = (direct ? ByteBuffer.allocateDirect(required + 16)
                            : ByteBuffer.allocate(required + 16)).order(order);
                    for (int i = 0; i < out.capacity(); i++) out.put(i, (byte) '!');
                    out.limit(offset + available).position(2).mark();
                    int actual = writer.write(value, out, offset);
                    assertEquals(available < required ? -required : required, actual);
                    assertEquals(2, out.position());
                    assertEquals(offset + available, out.limit());
                    assertSame(order, out.order());
                    out.reset();
                    byte[] prefix = new byte[available];
                    out.get(offset, prefix);
                    assertArrayEquals(Arrays.copyOf(expected, available), prefix);
                    ByteBuffer all = out.duplicate().clear();
                    for (int i = 0; i < offset; i++) assertEquals((byte) '!', all.get(i));
                    for (int i = offset + available; i < all.capacity(); i++) assertEquals((byte) '!', all.get(i));
                }
            }
        }
    }

    private static long word(byte[] bytes, int offset) {
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) result |= (bytes[offset + i] & 255L) << (i * Byte.SIZE);
        return result;
    }

    private static String fieldTable(String source) {
        return source.substring(source.indexOf("FIELD_PREFIXES = {"), source.indexOf("};"));
    }

    private Path compile(String option) throws Exception {
        return compile(option, accountSource(), true);
    }

    private static String accountSource() {
        return """
                package fixture;
                @com.reactor.rust.annotations.GenerateDirectJsonWriter
                public record Account(String customerName, long balanceCents, String \u015fehir) {}
                """;
    }

    private Path compile(String option, String fixture, boolean success) throws Exception {
        Path root = Files.createTempDirectory(temp, "compile-");
        Path source = root.resolve("Account.java");
        Files.writeString(source, fixture, StandardCharsets.UTF_8);
        Path classes = Files.createDirectory(root.resolve("classes"));
        Path generated = Files.createDirectory(root.resolve("generated"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var options = new ArrayList<>(List.of("--release", "21", "-proc:full", "-classpath",
                    System.getProperty("java.class.path"), "-d", classes.toString(), "-s", generated.toString()));
            if (option != null) options.add("-Areactor.json.swar" + (option.isEmpty() ? "" : "=" + option));
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            var task = compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjects(source));
            task.setProcessors(List.of(new DirectJsonWriterProcessor()));
            assertEquals(success, task.call(), diagnostics.getDiagnostics().toString());
            if (!success) {
                assertTrue(diagnostics.getDiagnostics().stream().anyMatch(
                        diagnostic -> diagnostic.getMessage(null).contains("must be true or false")));
            }
        }
        return classes;
    }
}

package com.reactor.rust.codegen;

import com.reactor.rust.view.NativeViewTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class NativeViewProcessorTest {
    @TempDir Path directory;

    @Test
    void generatedCodeCompilesAndPassesBinaryJsonBoundsAndLifetimeChecks() throws Exception {
        try (var loaded = NativeViewTestSupport.loadFixture(directory)) {
            loaded.fixture().check();
            try (var paths = Files.walk(loaded.compilation().generated())) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String generated = Files.readString(path);
                    assertFalse(generated.contains("java.lang.reflect"));
                    assertFalse(generated.contains("Class.forName"));
                    assertFalse(generated.contains(".order("));
                    assertFalse(generated.contains("ServiceLoader"));
                }
            }
            Class<?> schema = loaded.loader().loadClass("fixture.SampleAccountView");
            assertEquals(0, schema.getAnnotations().length);
            assertEquals(0, schema.getDeclaredMethods()[0].getAnnotations().length);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSchemas")
    void rejectsInvalidSchemas(String label, String declaration, String diagnostic) throws Exception {
        var result = NativeViewTestSupport.compile(directory, "Bad.java",
                "import com.reactor.rust.view.*;\n" + declaration);
        assertFalse(result.success(), label);
        assertTrue(result.diagnostics().contains(diagnostic), result.diagnostics());
    }

    static Stream<Arguments> invalidSchemas() {
        return Stream.of(
                Arguments.of("class", "@NativeView class Bad {}", "requires a non-generic"),
                Arguments.of("generic view", "@NativeView interface Bad<T> {}", "requires a non-generic"),
                Arguments.of("generic parent", "interface Base<T> {} @NativeView interface Bad extends Base<String> {}", "Generic view inheritance"),
                Arguments.of("raw generic parent", "interface Base<T> {} @NativeView interface Bad extends Base {}", "Generic view inheritance"),
                Arguments.of("schema version", "@NativeView(schemaVersion=0) interface Bad {}", "schemaVersion must be positive"),
                Arguments.of("missing annotation", "@NativeView interface Bad { long id(); }", "Every abstract view getter"),
                Arguments.of("getter parameters", "@NativeView interface Bad { @ViewField(index=0) long id(int x); }", "Every abstract view getter"),
                Arguments.of("generic getter", "@NativeView interface Bad { @ViewField(index=0) <T> T id(); }", "Every abstract view getter"),
                Arguments.of("throwing getter", "@NativeView interface Bad { @ViewField(index=0) long id() throws Exception; }", "Every abstract view getter"),
                Arguments.of("reserved name", "@NativeView interface Bad { @ViewField(index=0) int byteSize(); }", "Every abstract view getter"),
                Arguments.of("annotated default", "@NativeView interface Bad { @ViewField(index=0) default int id(){return 1;} }", "abstract instance getter"),
                Arguments.of("annotated static", "@NativeView interface Bad { @ViewField(index=0) static int id(){return 1;} }", "abstract instance getter"),
                Arguments.of("negative index", "@NativeView interface Bad { @ViewField(index=-1) long id(); }", "index must be nonnegative"),
                Arguments.of("duplicate index", "@NativeView interface Bad { @ViewField(index=0) long id(); @ViewField(index=0) long other(); }", "index must be nonnegative"),
                Arguments.of("negative offset", "@NativeView interface Bad { @ViewField(index=0,offset=-2) int id(); }", "offset must be -1"),
                Arguments.of("unaligned long", "@NativeView interface Bad { @ViewField(index=0,offset=4) long id(); }", "Misaligned or overflowing"),
                Arguments.of("descriptor alignment", "@NativeView interface Bad { @ViewField(index=0,offset=2) Utf8Slice text(); }", "Misaligned or overflowing"),
                Arguments.of("overlap", "@NativeView interface Bad { @ViewField(index=0) long id(); @ViewField(index=1,offset=4) int other(); }", "Overlapping"),
                Arguments.of("overflowing offset", "@NativeView interface Bad { @ViewField(index=0,offset=2147483640) long id(); }", "Misaligned or overflowing"),
                Arguments.of("String", "@NativeView interface Bad { @ViewField(index=0) String id(); }", "Unsupported view field"),
                Arguments.of("array", "@NativeView interface Bad { @ViewField(index=0) long[] id(); }", "Unsupported view field"),
                Arguments.of("void", "@NativeView interface Bad { @ViewField(index=0) void id(); }", "Unsupported view field"),
                Arguments.of("generic field", "@NativeView interface Bad { @ViewField(index=0) java.util.List<String> id(); }", "Unsupported view field"),
                Arguments.of("direct cycle", "@NativeView interface Bad { @ViewField(index=0) Bad next(); }", "Cyclic @NativeView"),
                Arguments.of("indirect cycle", "@NativeView interface Bad { @ViewField(index=0) Other next(); } @NativeView interface Other { @ViewField(index=0) Bad next(); }", "Cyclic @NativeView"),
                Arguments.of("empty enum", "enum E {} @NativeView interface Bad { @ViewField(index=0) E state(); }", "Enum must have 1..256"),
                Arguments.of("257 enums", "enum E {" + enumConstants(257) + "} @NativeView interface Bad { @ViewField(index=0) E state(); }", "Enum must have 1..256")
        );
    }

    @Test
    void all256EnumValuesAndMemberViewsRoundTripWithUnsignedOrdinal() throws Exception {
        String source = """
                import com.reactor.rust.view.*;
                import java.nio.*;
                public class Boundary implements Runnable {
                    public enum E { %s }
                    @NativeView public interface Entry { @ViewField(index=0) E state(); }
                    public void run() {
                        ByteBuffer bytes=ByteBuffer.allocateDirect(1).order(ByteOrder.LITTLE_ENDIAN);
                        var view=new Boundary_Entry_NativeView();
                        for(E value:E.values()) {
                            Boundary_Entry_NativeWriter.write(bytes,0,value);
                            view.wrap(bytes,0,1);
                            if(view.state()!=value) throw new AssertionError(value);
                            view.close();
                        }
                        if((bytes.get(0)&255)!=255) throw new AssertionError();
                        if(bytes.order()!=ByteOrder.LITTLE_ENDIAN) throw new AssertionError();
                    }
                }
                """.formatted(enumConstants(256));
        var result = NativeViewTestSupport.compile(directory, "Boundary.java", source);
        assertTrue(result.success(), result.diagnostics());
        try (var loader = new URLClassLoader(new java.net.URL[]{result.classes().toUri().toURL()}, getClass().getClassLoader())) {
            ((Runnable) loader.loadClass("Boundary").getConstructor().newInstance()).run();
        }
    }

    @Test
    void noAnnotationProducesNoGeneratedFiles() throws Exception {
        var result = NativeViewTestSupport.compile(directory, "Plain.java", "interface Plain { long id(); }");
        assertTrue(result.success(), result.diagnostics());
        try (var generated = Files.walk(result.generated())) {
            assertEquals(0, generated.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void processorServiceAppendsWithoutReplacingExistingProcessors() throws Exception {
        var entries = Files.readAllLines(Path.of("src/codegen/resources/META-INF/services/javax.annotation.processing.Processor"));
        assertTrue(entries.contains("com.reactor.rust.codegen.DirectJsonWriterProcessor"));
        assertTrue(entries.contains("com.reactor.rust.codegen.JdbcRecordMapperProcessor"));
        assertTrue(entries.contains("com.reactor.rust.codegen.ReactorStartupProcessor"));
        assertTrue(entries.contains("com.reactor.rust.codegen.ContractManifestProcessor"));
        assertEquals(1, entries.stream().filter("com.reactor.rust.codegen.NativeViewProcessor"::equals).count());
    }

    private static String enumConstants(int count) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) names.append(',');
            names.append('V').append(i);
        }
        return names.toString();
    }

    @Test
    void rejectsExponentialPreallocatedGraphEvenWhenAcyclic() throws Exception {
        StringBuilder schema = new StringBuilder("import com.reactor.rust.view.*; @NativeView interface Node0 {}\n");
        for (int i = 1; i <= 10; i++) {
            schema.append("@NativeView interface Node").append(i).append(" { @ViewField(index=0) Node")
                    .append(i - 1).append(" left(); @ViewField(index=1) Node").append(i - 1).append(" right(); }\n");
        }
        var result = NativeViewTestSupport.compile(directory, "Node0.java", schema.toString());
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("1024 retained view/slice objects"), result.diagnostics());
    }

    @Test
    void rejectsMoreThan256Fields() throws Exception {
        StringBuilder schema = new StringBuilder("import com.reactor.rust.view.*; @NativeView interface Bad {\n");
        for (int i = 0; i <= 256; i++) schema.append("@ViewField(index=").append(i).append(") int f").append(i).append("();\n");
        var result = NativeViewTestSupport.compile(directory, "Bad.java", schema.append('}').toString());
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("at most 256 fields"), result.diagnostics());
    }

    @Test
    void largeSchemasRespectJvmParameterSlotLimit() throws Exception {
        StringBuilder schema = new StringBuilder("import com.reactor.rust.view.*; @NativeView interface Large {\n");
        for (int i = 0; i < 256; i++) schema.append("@ViewField(index=").append(i).append(") long f").append(i).append("();\n");
        var result = NativeViewTestSupport.compile(directory, "Large.java", schema.append('}').toString());
        assertTrue(result.success(), result.diagnostics());
        String writer = Files.readString(result.generated().resolve("Large_NativeWriter.java"));
        assertTrue(writer.contains("write(Large value, ByteBuffer out, int offset)"));
        assertFalse(writer.contains("write(ByteBuffer out, int offset,"));
    }
}

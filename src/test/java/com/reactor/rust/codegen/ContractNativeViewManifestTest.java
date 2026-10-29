package com.reactor.rust.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContractNativeViewManifestTest {
    private static final String HEADER =
            "# reactor-contract-v1; conservative structural compatibility, not semantic proof\n";
    private static final String IMPORTS = "package fixture; import com.reactor.rust.view.*;\n";
    @TempDir Path directory;
    private int compilation;

    @Test
    void schemaVersionMutationChangesManifest() throws Exception {
        var first = successful(view("1", "@ViewField(index=0) long id();"));
        var second = successful(view("2", "@ViewField(index=0) long id();"));
        assertNotEquals(first.text(), second.text());
        assertTrue(first.text().contains("@com.reactor.rust.view.NativeView(schemaVersion=1)"));
        assertTrue(second.text().contains("@com.reactor.rust.view.NativeView(schemaVersion=2)"));
        assertTrue(Files.isRegularFile(first.classes().resolve("fixture/Sample_NativeView.class")));
        assertTrue(Files.isRegularFile(first.classes().resolve("fixture/Sample_NativeWriter.class")));
    }

    @Test
    void indexMutationChangesManifestWithoutChangingJavaMethods() throws Exception {
        var first = successful(view("1", """
                @ViewField(index=0) long id();
                @ViewField(index=1) int count();
                """));
        var second = successful(view("1", """
                @ViewField(index=1) long id();
                @ViewField(index=0) int count();
                """));
        assertNotEquals(first.text(), second.text());
        assertTrue(first.text().contains("id:()long@com.reactor.rust.view.ViewField(index=0,offset=-1)"));
        assertTrue(second.text().contains("id:()long@com.reactor.rust.view.ViewField(index=1,offset=-1)"));
    }

    @Test
    void offsetMutationChangesManifestWithoutChangingJavaMethods() throws Exception {
        var first = successful(view("1", """
                @ViewField(index=0) long id();
                @ViewField(index=1,offset=8) int count();
                """));
        var second = successful(view("1", """
                @ViewField(index=0) long id();
                @ViewField(index=1,offset=16) int count();
                """));
        assertNotEquals(first.text(), second.text());
        assertTrue(first.text().contains("ViewField(index=1,offset=8)"));
        assertTrue(second.text().contains("ViewField(index=1,offset=16)"));
    }

    @Test
    void sourceAndAnnotationArgumentOrderDoNotChangeManifest() throws Exception {
        Source other = new Source("Other.java", IMPORTS
                + "@NativeView public interface Other { @ViewField(index=0) boolean active(); }");
        Source first = view("3", """
                @ViewField(index=10,offset=8) long id();
                @ViewField(index=2,offset=0) int count();
                """);
        Source second = view("3", """
                @ViewField(offset=0,index=2) int count();
                @ViewField(offset=8,index=10) long id();
                """);
        var left = successful(first, other);
        var right = successful(other, second);
        assertArrayEquals(left.manifest(), right.manifest());
        int count = left.text().indexOf("count:()int@com.reactor.rust.view.ViewField(index=2,offset=0)");
        int id = left.text().indexOf("id:()long@com.reactor.rust.view.ViewField(index=10,offset=8)");
        assertTrue(count >= 0 && id > count, left.text());
        assertTrue(left.text().indexOf("fixture.Other\t") < left.text().indexOf("fixture.Sample\t"));
    }

    @Test
    void explicitAndOmittedDefaultsHaveIdenticalManifest() throws Exception {
        var omitted = successful(view("", "@ViewField(index=0) long id();"));
        var explicit = successful(view("1", "@ViewField(offset=-1,index=0) long id();"));
        assertArrayEquals(omitted.manifest(), explicit.manifest());
        assertTrue(omitted.text().contains("NativeView(schemaVersion=1)"));
        assertTrue(omitted.text().contains("ViewField(index=0,offset=-1)"));
    }

    @Test
    void publicNestedViewIsCapturedWithoutExtendingOtherNestedContracts() throws Exception {
        var result = successful(new Source("Envelope.java", IMPORTS + """
                public class Envelope {
                    @NativeView public interface Entry { @ViewField(index=0) long id(); }
                    @NativeView interface Hidden { @ViewField(index=0) int code(); }
                    public interface Ordinary { long id(); }
                    public record Data(long id) {}
                }
                """));
        assertEquals(HEADER + "fixture.Envelope.Entry\tINTERFACE|id:()long"
                + "|@com.reactor.rust.view.NativeView(schemaVersion=1)"
                + "|id:()long@com.reactor.rust.view.ViewField(index=0,offset=-1)\n", result.text());
        assertTrue(Files.isRegularFile(result.classes().resolve("fixture/Envelope_Entry_NativeView.class")));
    }

    @Test
    void inheritedViewFieldMetadataTracksOffsetChanges() throws Exception {
        var first = successful(new Source("Sample.java", IMPORTS + """
                interface Fields { @ViewField(index=0,offset=0) long id(); }
                @NativeView public interface Sample extends Fields {}
                """));
        var second = successful(new Source("Sample.java", IMPORTS + """
                interface Fields { @ViewField(index=0,offset=8) long id(); }
                @NativeView public interface Sample extends Fields {}
                """));
        assertNotEquals(first.text(), second.text());
        assertTrue(first.text().contains("id:()long@com.reactor.rust.view.ViewField(index=0,offset=0)"));
        assertTrue(second.text().contains("id:()long@com.reactor.rust.view.ViewField(index=0,offset=8)"));
    }

    @Test
    void ordinaryInterfaceAndRecordMatchLegacyGoldenBytes() throws Exception {
        var result = successful(new Source("Plain.java", """
                package fixture;
                interface Base<T> { T get(); }
                public interface Plain extends Base<String> {
                    int z(int input);
                    void a();
                    default long ignored() { return 1; }
                }
                """), new Source("Data.java", "package fixture; public record Data(long id, String name) {}"));
        String expected = HEADER + "fixture.Data\tRECORD|id:long:|name:java.lang.String:\n"
                + "fixture.Plain\tINTERFACE|a:()void|get:()java.lang.String|z:(int)int\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), result.manifest());
    }

    @Test
    void nestedEnumOrdinalReorderingChangesItsViewContract() throws Exception {
        String template = IMPORTS + """
                public class Envelope {
                    public enum Status { %s }
                    @NativeView public interface Entry { @ViewField(index=0) Status status(); }
                }
                """;
        var first = successful(new Source("Envelope.java", template.formatted("READY, DONE")));
        var second = successful(new Source("Envelope.java", template.formatted("DONE, READY")));
        assertTrue(first.text().contains(";enumConstants=[READY,DONE]"), first.text());
        assertTrue(second.text().contains(";enumConstants=[DONE,READY]"), second.text());
        assertNotEquals(first.text(), second.text());
    }

    @Test
    void fieldAnnotationsAloneDoNotAddNewNonViewSemantics() throws Exception {
        var plain = successful(new Source("Plain.java",
                "package fixture; public interface Plain { long id(); }"));
        var annotated = successful(new Source("Plain.java", IMPORTS
                + "public interface Plain { @ViewField(index=7,offset=16) long id(); }"));
        assertArrayEquals(plain.manifest(), annotated.manifest());
        assertEquals(HEADER + "fixture.Plain\tINTERFACE|id:()long\n", annotated.text());
    }

    @Test
    void emptyNestedViewDiscoveryDoesNotDependOnProcessorOrder() throws Exception {
        Source source = new Source("Envelope.java", IMPORTS
                + "public class Envelope { @NativeView public interface Empty {} }");
        var manifestFirst = compile(false, source);
        var viewFirst = compile(true, source);
        assertTrue(manifestFirst.success(), manifestFirst.diagnostics());
        assertTrue(viewFirst.success(), viewFirst.diagnostics());
        assertEquals(HEADER + "fixture.Envelope.Empty\tINTERFACE"
                + "|@com.reactor.rust.view.NativeView(schemaVersion=1)\n", manifestFirst.text());
        assertArrayEquals(manifestFirst.manifest(), viewFirst.manifest());
    }

    @Test
    void invalidLayoutStillFailsThroughNativeViewProcessor() throws Exception {
        var result = compile(false, view("1", """
                @ViewField(index=0) long id();
                @ViewField(index=0) long other();
                """));
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("ViewField index must be nonnegative and unique"),
                result.diagnostics());
    }

    private Source view(String version, String fields) {
        return new Source("Sample.java", IMPORTS + "@NativeView"
                + (version.isEmpty() ? "" : "(schemaVersion=" + version + ")")
                + " public interface Sample {\n" + fields + "\n}\n");
    }

    private Compilation successful(Source... sources) throws Exception {
        var result = compile(false, sources);
        assertTrue(result.success(), result.diagnostics());
        return result;
    }

    private Compilation compile(boolean nativeViewFirst, Source... sources) throws Exception {
        Path root = Files.createDirectory(directory.resolve("compile-" + compilation++));
        Path classes = Files.createDirectory(root.resolve("classes"));
        Path generated = Files.createDirectory(root.resolve("generated"));
        List<Path> files = new ArrayList<>();
        for (Source source : sources) {
            Path path = root.resolve(source.name());
            Files.writeString(path, source.text(), StandardCharsets.UTF_8);
            files.add(path);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        boolean success;
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "21", "-proc:full", "-encoding", "UTF-8", "-classpath",
                            System.getProperty("java.class.path"), "-d", classes.toString(), "-s", generated.toString()),
                    null, manager.getJavaFileObjectsFromPaths(files));
            List<Processor> processors = nativeViewFirst
                    ? List.of(new NativeViewProcessor(), new ContractManifestProcessor())
                    : List.of(new ContractManifestProcessor(), new NativeViewProcessor());
            task.setProcessors(processors);
            success = task.call();
        }
        Path manifest = classes.resolve("META-INF/reactor/contracts.tsv");
        return new Compilation(success, Files.exists(manifest) ? Files.readAllBytes(manifest) : new byte[0],
                classes, diagnostics.getDiagnostics().toString());
    }

    private record Source(String name, String text) {}
    private record Compilation(boolean success, byte[] manifest, Path classes, String diagnostics) {
        String text() { return new String(manifest, StandardCharsets.UTF_8); }
    }
}

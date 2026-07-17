package com.reactor.rust.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Processor;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorCodegenProcessorsTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesStartupDescriptorIndexesAndPropertyMetadata() throws Exception {
        Path source = source("generated/fixture/Handler.java", """
                package generated.fixture;

                import com.reactor.rust.annotations.GetMapping;
                import com.reactor.rust.annotations.RequestMapping;
                import com.reactor.rust.annotations.RustProperty;
                import com.reactor.rust.di.annotation.Component;

                @Component
                @RequestMapping("/items")
                public class Handler {
                    @RustProperty(value = "sample.limit", defaultValue = "8")
                    private int limit;

                    @GetMapping("/{id}")
                    public String get() { return "ok"; }
                }
                """);
        Compilation output = compile(source, new ReactorStartupProcessor());

        assertTrue(Files.readString(output.classes().resolve("META-INF/reactor/components.idx"))
                .contains("generated.fixture.Handler"));
        assertTrue(Files.readString(output.classes().resolve("META-INF/reactor/routes.idx"))
                .contains("GET /items/{id}"));
        assertTrue(Files.readString(output.classes().resolve("META-INF/reactor/properties.idx"))
                .contains("sample.limit\tint\t8"));
        assertTrue(Files.readString(output.generated().resolve(
                        "com/reactor/generated/ReactorApplicationDescriptor.java"))
                .contains("new generated.fixture.Handler()"));
    }

    @Test
    void generatesDirectJsonWriterAndProvider() throws Exception {
        Path source = source("generated/fixture/Response.java", """
                package generated.fixture;
                import com.reactor.rust.annotations.GenerateDirectJsonWriter;
                @GenerateDirectJsonWriter
                public record Response(long id, String name, boolean active) {}
                """);
        Compilation output = compile(source, new DirectJsonWriterProcessor());

        String writer = Files.readString(output.generated().resolve(
                "generated/fixture/ResponseDirectJsonWriter.java"));
        assertTrue(writer.contains("json.fieldLong(\"id\", value.id())"));
        assertTrue(writer.contains("json.fieldString(\"name\", value.name())"));
        assertTrue(Files.readString(output.classes().resolve(
                        "META-INF/services/com.reactor.rust.json.DirectJsonWriterProvider"))
                .contains("ReactorDirectJsonWriterProvider"));
    }

    @Test
    void generatedDirectJsonWriterSupportsNullableBoxedScalars() throws Exception {
        Path source = source("generated/fixture/NullableResponse.java", """
                package generated.fixture;
                import com.reactor.rust.annotations.GenerateDirectJsonWriter;
                @GenerateDirectJsonWriter
                public record NullableResponse(
                        Integer count, Long total, Double ratio, Boolean enabled) {}
                """);

        Compilation output = compile(source, new DirectJsonWriterProcessor());

        String generated = Files.readString(output.generated().resolve(
                "generated/fixture/NullableResponseDirectJsonWriter.java"));
        assertTrue(generated.contains("count() == null"));
        assertTrue(generated.contains("count().intValue()"));
        assertTrue(generated.contains("enabled().booleanValue()"));
    }

    @Test
    void generatesJdbcRecordMapper() throws Exception {
        Path source = source("generated/fixture/Customer.java", """
                package generated.fixture;
                import com.reactor.rust.annotations.GenerateJdbcMapper;
                @GenerateJdbcMapper
                public record Customer(long id, String customerNo, java.time.Instant createdAt) {}
                """);
        Compilation output = compile(source, new JdbcRecordMapperProcessor());

        String mapper = Files.readString(output.generated().resolve(
                "generated/fixture/CustomerJdbcMapper.java"));
        assertTrue(mapper.contains("row.getLong(\"id\")"));
        assertTrue(mapper.contains("row.getString(\"customer_no\")"));
        assertTrue(mapper.contains("instant(row, \"created_at\")"));
    }

    private Path source(String relativePath, String content) throws Exception {
        Path source = tempDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content, StandardCharsets.UTF_8);
        return source;
    }

    private Compilation compile(Path source, Processor processor) throws Exception {
        String name = processor.getClass().getSimpleName();
        Path generated = Files.createDirectories(tempDir.resolve(name + "-generated"));
        Path classes = Files.createDirectories(tempDir.resolve(name + "-classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    null,
                    List.of(
                            "--release", "21",
                            "-proc:only",
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString(),
                            "-s", generated.toString()),
                    null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(processor));
            assertTrue(task.call());
        }
        return new Compilation(generated, classes);
    }

    private record Compilation(Path generated, Path classes) {}
}

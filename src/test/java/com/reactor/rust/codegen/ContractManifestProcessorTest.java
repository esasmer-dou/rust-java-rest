package com.reactor.rust.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContractManifestProcessorTest {
    @TempDir Path directory;
    @Test void emitsDeterministicRecordFieldsAndMethodSignatures() throws Exception {
        Path source=directory.resolve("Customer.java");
        Files.writeString(source,"public record Customer(long id, String name) {}");
        var compiler=ToolProvider.getSystemJavaCompiler();
        try(var files=compiler.getStandardFileManager(null,null,null)) {
            var task=compiler.getTask(null,files,null,List.of("-proc:only","-d",directory.toString()),null,files.getJavaFileObjects(source));
            task.setProcessors(List.of(new ContractManifestProcessor()));
            assertTrue(task.call());
        }
        assertTrue(Files.readString(directory.resolve("META-INF/reactor/contracts.tsv")).contains("Customer\tRECORD|id:long:|name:java.lang.String:"));
    }
    @Test void resolvesInheritedGenericServiceMethods() throws Exception {
        Path source=directory.resolve("CustomerService.java");
        Files.writeString(source,"interface Base<T> { T get(); } public interface CustomerService extends Base<String> {}");
        var compiler=ToolProvider.getSystemJavaCompiler();
        try(var files=compiler.getStandardFileManager(null,null,null)) {
            var task=compiler.getTask(null,files,null,List.of("-proc:only","-d",directory.toString()),null,files.getJavaFileObjects(source));
            task.setProcessors(List.of(new ContractManifestProcessor()));
            assertTrue(task.call());
        }
        assertTrue(Files.readString(directory.resolve("META-INF/reactor/contracts.tsv"))
                .contains("CustomerService\tINTERFACE|get:()java.lang.String"));
    }
}

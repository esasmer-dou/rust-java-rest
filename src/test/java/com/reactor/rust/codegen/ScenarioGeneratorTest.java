package com.reactor.rust.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioGeneratorTest {
    @TempDir Path root;
    @Test void generatesPairsAndNeverOverwritesExistingDirectory() throws Exception {
        for (String kind : new String[]{"cache", "dubbo"}) {
            Path output = root.resolve(kind);
            ScenarioGenerator.generate(kind, output);
            assertTrue(Files.exists(output.resolve("compose.yaml")));
            assertTrue(Files.readString(output.resolve("smoke.ps1")).contains("ready"));
            assertThrows(FileAlreadyExistsException.class, () -> ScenarioGenerator.generate(kind, output));
        }
        assertTrue(Files.exists(root.resolve("dubbo/contract/src/main/java/com/example/app/EchoService.java")));
        assertFalse(Files.exists(root.resolve("dubbo/consumer/src/main/java/com/example/app/EchoService.java")));
    }
}

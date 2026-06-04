package com.reactor.rust.startup;

import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.RequestMapping;
import com.reactor.rust.annotations.StaticFiles;
import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.websocket.annotation.WebSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupIndexGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesComponentAndRouteIndexes() throws Exception {
        StartupIndexGenerator.main(new String[]{
                "--output", tempDir.toString(),
                "--packages", "com.reactor.rust.startup"
        });

        List<String> components = Files.readAllLines(tempDir.resolve("META-INF/reactor/components.idx"));
        List<String> routes = Files.readAllLines(tempDir.resolve("META-INF/reactor/routes.idx"));

        assertTrue(components.stream().anyMatch(line -> line.endsWith("StartupIndexGeneratorTest$IndexedHandler")));
        assertTrue(routes.stream().anyMatch(line -> line.contains("GET /indexed/ping")));
    }

    @Test
    void rejectsMissingArgumentValues() {
        assertThrows(IllegalArgumentException.class, () ->
                StartupIndexGenerator.main(new String[]{"--output", "--packages", "com.acme"})
        );
    }

    @Test
    void canExcludeOptionalWebSocketAndStaticFileComponents() throws Exception {
        StartupIndexGenerator.main(new String[]{
                "--output", tempDir.toString(),
                "--packages", "com.reactor.rust.startup",
                "--exclude-websocket",
                "--exclude-static-files"
        });

        List<String> components = Files.readAllLines(tempDir.resolve("META-INF/reactor/components.idx"));

        assertTrue(components.stream().anyMatch(line -> line.endsWith("StartupIndexGeneratorTest$IndexedHandler")));
        assertFalse(components.stream().anyMatch(line -> line.endsWith("StartupIndexGeneratorTest$IndexedWebSocket")));
        assertFalse(components.stream().anyMatch(line -> line.endsWith("StartupIndexGeneratorTest$IndexedStaticFiles")));
    }

    @Component
    @RequestMapping("/indexed")
    static class IndexedHandler {
        @GetMapping(value = "/ping", responseType = String.class)
        String ping() {
            return "pong";
        }
    }

    @Component
    @WebSocket("/indexed/ws")
    static class IndexedWebSocket {
    }

    @Component
    @StaticFiles(path = "/indexed-static", location = "static")
    static class IndexedStaticFiles {
    }
}

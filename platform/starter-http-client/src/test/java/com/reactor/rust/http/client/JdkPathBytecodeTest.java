package com.reactor.rust.http.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Compares instruction bodies against a frozen pre-change 4.7.1 source-build oracle. */
class JdkPathBytecodeTest {
    @Test
    void defaultBodyConstructionEncodingSubscriberAndCloseRetainPublishedBytecode() throws Exception {
        String before;
        try (var resource = getClass().getResourceAsStream("/jdk-bytecode-4.7.1.txt")) {
            assertNotNull(resource, "Frozen pre-change instructions must be packaged with the tests");
            before = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        String baselinePath = System.getProperty("reactor.http-client.baseline-jar");
        String actualBaseline = null;
        if (baselinePath != null) {
            Path baseline = Path.of(baselinePath);
            assertTrue(Files.isRegularFile(baseline), "Explicit baseline JAR must exist");
            try (var jar = new JarFile(baseline.toFile())) {
                assertNull(jar.getEntry("com/reactor/rust/http/client/ReactorHttpClientRuntime$NativeRuntime.class"),
                        "Baseline was replaced by the candidate; comparing the candidate with itself is not proof");
                assertNotNull(jar.getEntry("com/reactor/rust/http/client/ReactorHttpClientRuntime$Request.class"),
                        "Baseline must contain the genuine pre-change request implementation");
            }
            actualBaseline = inspect(baseline.toString(), "before");
        }
        String after = inspect(Path.of("target", "classes").toAbsolutePath().toString(), "after");
        for (String method : List.of("private java.net.http.HttpRequest build();",
                "private static byte[] encodeBody(java.lang.Object);", "public void close();",
                "public void onNext(java.util.List<java.nio.ByteBuffer>);", "private void ensureCapacity(int);")) {
            if (actualBaseline != null) assertEquals(body(before, method), body(actualBaseline, method),
                    "Frozen oracle differs from the explicit pre-change source build: " + method);
            assertEquals(body(before, method), body(after, method), "Default JDK instruction regression: " + method);
        }
        assertFalse(List.of(ReactorHttpClientRuntime.class.getDeclaredFields()).stream()
                .anyMatch(field -> field.getName().equals("transport")), "Default runtime must not allocate or retain a transport adapter");
        String execution = body(after, "executeInternal(").toString();
        assertFalse(execution.contains("Native"), execution);
        assertFalse(execution.contains("PropertiesLoader"), execution);
    }

    private static String inspect(String classpath, String suffix) throws Exception {
        String name = ReactorHttpClientRuntime.class.getName();
        Path output = Path.of("target", "jdk-bytecode-" + suffix + ".txt").toAbsolutePath();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "javap").toString(),
                "-J-Xmx64m", "-J-Xss512k", "-J-XX:ActiveProcessorCount=2", "-classpath", classpath,
                "-p", "-c", name, name + "$Request", name + "$BoundedBodySubscriber")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Bounded bytecode inspection timed out");
            String text = Files.readString(output);
            assertEquals(0, process.exitValue(), text);
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static List<String> body(String output, String method) {
        int start = output.indexOf(method);
        assertTrue(start >= 0, "Missing actual bytecode body: " + method);
        start = output.indexOf('\n', start);
        var next = Pattern.compile("(?m)^  \\S.*(?:;|\\{)\\s*$").matcher(output);
        int end = next.find(start) ? next.start() : output.length();
        var instructions = Pattern.compile("(?m)^\\s+(\\d+):\\s+(.+)$").matcher(output.substring(start, end));
        var offsets = new HashMap<String, Integer>();
        var lines = new ArrayList<String>();
        while (instructions.find()) {
            offsets.put(instructions.group(1), lines.size());
            lines.add(instructions.group(2).trim());
        }
        assertFalse(lines.isEmpty(), method);
        return lines.stream().map(line -> {
            String normalized = line.replaceAll("#\\d+", "#").replace("ldc_w ", "ldc ");
            var branch = Pattern.compile("^(if\\S+|goto|jsr)\\s+(\\d+)$").matcher(normalized);
            if (branch.matches()) normalized = branch.group(1) + " instruction:" + offsets.get(branch.group(2));
            return normalized.replaceAll("\\s+", " ");
        }).toList();
    }
}
